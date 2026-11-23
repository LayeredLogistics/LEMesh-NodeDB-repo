package us.lemesh.nodedb.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import us.lemesh.nodedb.data.PolarVisibilityField
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface CrispOverlaySource {
    val northLat: Double
    val southLat: Double
    val westLon: Double
    val eastLon: Double

    data class SingleField(
        val polarField: PolarVisibilityField,
        val tintColorArgb: Int? = null
    ) : CrispOverlaySource {
        override val northLat: Double get() = polarField.northLat
        override val southLat: Double get() = polarField.southLat
        override val westLon: Double get() = polarField.westLon
        override val eastLon: Double get() = polarField.eastLon
    }

    data class BridgeFields(
        val txField: PolarVisibilityField,
        val rxField: PolarVisibilityField,
        val sensitivityDbm: Double,
        override val northLat: Double,
        override val southLat: Double,
        override val westLon: Double,
        override val eastLon: Double
    ) : CrispOverlaySource

    data class AggregateFields(
        val fields: List<PolarVisibilityField>,
        val sensitivityDbm: Double,
        val signature: String,
        override val northLat: Double,
        override val southLat: Double,
        override val westLon: Double,
        override val eastLon: Double
    ) : CrispOverlaySource
}

class CrispOverlayPool(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val buffers = LinkedHashMap<String, ViewportOverlayBuffer>()

    fun obtainOverlay(
        key: String,
        source: CrispOverlaySource,
        opacity: Float
    ): CrispPolarFieldOverlay {
        val buffer = buffers.getOrPut(key) {
            ViewportOverlayBuffer(scope = scope, workerDispatcher = workerDispatcher)
        }
        buffer.bindSource(source)
        return CrispPolarFieldOverlay(buffer = buffer, opacity = opacity)
    }

    fun pruneExcept(activeKeys: Set<String>) {
        val iterator = buffers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in activeKeys) {
                entry.value.clear()
                iterator.remove()
            }
        }
    }

    fun dispose() {
        buffers.values.forEach { it.clear() }
        buffers.clear()
        scope.cancel()
    }
}

class CrispPolarFieldOverlay(
    private val buffer: ViewportOverlayBuffer,
    private val opacity: Float
) : Overlay() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()
    private val nwPoint = Point()
    private val sePoint = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val viewW = mapView.width
        val viewH = mapView.height
        if (viewW <= 0 || viewH <= 0) return

        val source = buffer.currentSource() ?: return
        val proj = mapView.projection

        val tl = proj.fromPixels(0, 0)
        val tr = proj.fromPixels(viewW, 0)
        val bl = proj.fromPixels(0, viewH)
        val br = proj.fromPixels(viewW, viewH)

        val screenNorth = maxOf(tl.latitude, tr.latitude, bl.latitude, br.latitude).coerceIn(-85.0, 85.0)
        val screenSouth = minOf(tl.latitude, tr.latitude, bl.latitude, br.latitude).coerceIn(-85.0, 85.0)
        val screenWest = minOf(tl.longitude, tr.longitude, bl.longitude, br.longitude)
        val screenEast = maxOf(tl.longitude, tr.longitude, bl.longitude, br.longitude)

        val visNorth = min(screenNorth, source.northLat)
        val visSouth = max(screenSouth, source.southLat)
        val visWest = max(screenWest, source.westLon)
        val visEast = min(screenEast, source.eastLon)
        if (visNorth <= visSouth || visEast <= visWest) {
            return
        }

        val currentZoom = mapView.zoomLevelDouble
        proj.toPixels(GeoPoint(visNorth, visWest), nwPoint)
        proj.toPixels(GeoPoint(visSouth, visEast), sePoint)
        val visPxW = abs(sePoint.x - nwPoint.x).coerceAtLeast(1)
        val visPxH = abs(sePoint.y - nwPoint.y).coerceAtLeast(1)

        buffer.ensureCrispCoverage(
            source = source,
            visNorth = visNorth,
            visSouth = visSouth,
            visWest = visWest,
            visEast = visEast,
            visPixelWidth = visPxW,
            visPixelHeight = visPxH,
            zoomLevel = currentZoom,
            mapView = mapView
        )

        val cached = buffer.cachedRender ?: return
        if (cached.bitmap.isRecycled) return

        proj.toPixels(GeoPoint(cached.northLat, cached.westLon), nwPoint)
        proj.toPixels(GeoPoint(cached.southLat, cached.eastLon), sePoint)
        dstRect.set(
            min(nwPoint.x, sePoint.x).toFloat(),
            min(nwPoint.y, sePoint.y).toFloat(),
            max(nwPoint.x, sePoint.x).toFloat(),
            max(nwPoint.y, sePoint.y).toFloat()
        )
        if (dstRect.width() <= 0f || dstRect.height() <= 0f) return

        paint.alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(cached.bitmap, null, dstRect, paint)
    }
}

class ViewportOverlayBuffer(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    data class CachedRender(
        val bitmap: Bitmap,
        val northLat: Double,
        val southLat: Double,
        val westLon: Double,
        val eastLon: Double,
        val zoomLevel: Double,
        val widthPx: Int,
        val heightPx: Int
    )

    private var boundSource: CrispOverlaySource? = null
    @Volatile
    var cachedRender: CachedRender? = null
        private set

    private var activeJob: Job? = null
    private var lastRequestedSignature: RenderRequestSignature? = null

    private data class RenderRequestSignature(
        val northLat: Double,
        val southLat: Double,
        val westLon: Double,
        val eastLon: Double,
        val targetW: Int,
        val targetH: Int
    )

    fun currentSource(): CrispOverlaySource? = boundSource

    fun bindSource(source: CrispOverlaySource) {
        val existing = boundSource
        val unchanged = when {
            existing === source -> true
            existing is CrispOverlaySource.SingleField && source is CrispOverlaySource.SingleField ->
                existing.polarField === source.polarField && existing.tintColorArgb == source.tintColorArgb
            existing is CrispOverlaySource.BridgeFields && source is CrispOverlaySource.BridgeFields ->
                existing.txField === source.txField &&
                    existing.rxField === source.rxField &&
                    existing.sensitivityDbm == source.sensitivityDbm
            existing is CrispOverlaySource.AggregateFields && source is CrispOverlaySource.AggregateFields ->
                existing.signature == source.signature &&
                    existing.sensitivityDbm == source.sensitivityDbm &&
                    existing.fields.size == source.fields.size
            else -> false
        }
        if (unchanged) return

        activeJob?.cancel()
        activeJob = null
        lastRequestedSignature = null
        cachedRender = null
        boundSource = source
    }

    fun ensureCrispCoverage(
        source: CrispOverlaySource,
        visNorth: Double,
        visSouth: Double,
        visWest: Double,
        visEast: Double,
        visPixelWidth: Int,
        visPixelHeight: Int,
        zoomLevel: Double,
        mapView: MapView
    ) {
        val current = cachedRender
        val needsNewRender = if (current == null || current.bitmap.isRecycled) {
            true
        } else {
            val clippedOut = visNorth > current.northLat + 1e-6 ||
                visSouth < current.southLat - 1e-6 ||
                visWest < current.westLon - 1e-6 ||
                visEast > current.eastLon + 1e-6
            val zoomCoarser = zoomLevel - current.zoomLevel > 0.25
            val zoomMuchFiner = current.zoomLevel - zoomLevel > 1.25
            clippedOut || zoomCoarser || zoomMuchFiner
        }
        if (!needsNewRender) return

        // Expand slightly (12% overscan, clamped to source bounds) so small pans stay seamless
        val latPad = (visNorth - visSouth) * OVERSCAN_FRACTION
        val lonPad = (visEast - visWest) * OVERSCAN_FRACTION
        val renderNorth = min(source.northLat, visNorth + latPad)
        val renderSouth = max(source.southLat, visSouth - latPad)
        val renderWest = max(source.westLon, visWest - lonPad)
        val renderEast = min(source.eastLon, visEast + lonPad)

        val latScale = ((renderNorth - renderSouth) / (visNorth - visSouth).coerceAtLeast(1e-6))
            .coerceIn(1.0, 1.35)
        val lonScale = ((renderEast - renderWest) / (visEast - visWest).coerceAtLeast(1e-6))
            .coerceIn(1.0, 1.35)

        val desiredW = (visPixelWidth * lonScale).roundToInt().coerceAtLeast(MIN_RASTER_DIM)
        val desiredH = (visPixelHeight * latScale).roundToInt().coerceAtLeast(MIN_RASTER_DIM)
        val maxDim = max(desiredW, desiredH)
        val downscale = if (maxDim > MAX_RASTER_DIM) MAX_RASTER_DIM.toDouble() / maxDim else 1.0
        val targetW = (desiredW * downscale).roundToInt().coerceIn(MIN_RASTER_DIM, MAX_RASTER_DIM)
        val targetH = (desiredH * downscale).roundToInt().coerceIn(MIN_RASTER_DIM, MAX_RASTER_DIM)

        val signature = RenderRequestSignature(
            northLat = (renderNorth * 1_000_000.0).roundToInt() / 1_000_000.0,
            southLat = (renderSouth * 1_000_000.0).roundToInt() / 1_000_000.0,
            westLon = (renderWest * 1_000_000.0).roundToInt() / 1_000_000.0,
            eastLon = (renderEast * 1_000_000.0).roundToInt() / 1_000_000.0,
            targetW = targetW,
            targetH = targetH
        )
        if (activeJob?.isActive == true && lastRequestedSignature == signature) {
            return
        }

        val hadCached = current != null && !current.bitmap.isRecycled
        lastRequestedSignature = signature
        activeJob?.cancel()
        activeJob = scope.launch {
            if (hadCached) {
                delay(PAN_DEBOUNCE_MS)
            }
            val renderedBitmap = withContext(workerDispatcher) {
                renderSourceBitmap(
                    source = source,
                    northLat = renderNorth,
                    southLat = renderSouth,
                    westLon = renderWest,
                    eastLon = renderEast,
                    width = targetW,
                    height = targetH
                )
            }
            if (boundSource !== source) return@launch
            cachedRender = CachedRender(
                bitmap = renderedBitmap,
                northLat = renderNorth,
                southLat = renderSouth,
                westLon = renderWest,
                eastLon = renderEast,
                zoomLevel = zoomLevel,
                widthPx = targetW,
                heightPx = targetH
            )
            mapView.postInvalidate()
        }
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        lastRequestedSignature = null
        cachedRender = null
        boundSource = null
    }

    companion object {
        private const val OVERSCAN_FRACTION = 0.12
        private const val MIN_RASTER_DIM = 24
        private const val MAX_RASTER_DIM = 1152
        private const val PAN_DEBOUNCE_MS = 45L

        fun renderSourceBitmap(
            source: CrispOverlaySource,
            northLat: Double,
            southLat: Double,
            westLon: Double,
            eastLon: Double,
            width: Int,
            height: Int
        ): Bitmap {
            return when (source) {
                is CrispOverlaySource.SingleField -> source.polarField.rasterizeSubRegion(
                    renderNorthLat = northLat,
                    renderSouthLat = southLat,
                    renderWestLon = westLon,
                    renderEastLon = eastLon,
                    width = width,
                    height = height,
                    tintColorArgb = source.tintColorArgb
                )
                is CrispOverlaySource.BridgeFields -> PolarVisibilityField.rasterizeDualBridgeSubRegion(
                    txField = source.txField,
                    rxField = source.rxField,
                    sensitivityDbm = source.sensitivityDbm,
                    renderNorthLat = northLat,
                    renderSouthLat = southLat,
                    renderWestLon = westLon,
                    renderEastLon = eastLon,
                    width = width,
                    height = height
                )
                is CrispOverlaySource.AggregateFields -> PolarVisibilityField.rasterizeAggregateSubRegion(
                    fields = source.fields,
                    sensitivityDbm = source.sensitivityDbm,
                    renderNorthLat = northLat,
                    renderSouthLat = southLat,
                    renderWestLon = westLon,
                    renderEastLon = eastLon,
                    width = width,
                    height = height
                )
            }
        }
    }
}
