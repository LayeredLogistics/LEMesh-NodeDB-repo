package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.BuildConfig
import com.example.data.MeshNode
import com.example.data.ViewshedPointStatus
import com.example.ui.theme.NodeMultiHopSky
import com.example.ui.theme.NodeSelfAmber
import com.example.ui.theme.TacticalCanvas
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.hypot
import kotlin.math.max

const val CARTO_API_KEY_PLACEHOLDER = "CARTO_API_KEY_DEFAULT_VALUE"

fun resolveCartoApiKey(rawKey: String = BuildConfig.CARTO_API_KEY): String? {
    val trimmed = rawKey.trim()
    if (trimmed.isEmpty() || trimmed == CARTO_API_KEY_PLACEHOLDER) return null
    return trimmed
}

fun isCartoKeyConfigured(rawKey: String = BuildConfig.CARTO_API_KEY): Boolean {
    return resolveCartoApiKey(rawKey) != null
}

fun buildCartoTileUrl(
    baseUrl: String,
    zoom: Int,
    x: Int,
    y: Int,
    apiKey: String? = resolveCartoApiKey()
): String {
    val path = "$baseUrl$zoom/$x/$y.png"
    return if (!apiKey.isNullOrBlank()) {
        "$path?key=${Uri.encode(apiKey)}"
    } else {
        path
    }
}

fun createCartoTileSource(
    styleSlug: String,
    apiKey: String? = resolveCartoApiKey()
): OnlineTileSourceBase {
    val cleanSlug = styleSlug.trim('/')
    return object : OnlineTileSourceBase(
        "Carto_${cleanSlug}_256",
        0,
        20,
        256,
        ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/rastertiles/$cleanSlug/",
            "https://b.basemaps.cartocdn.com/rastertiles/$cleanSlug/",
            "https://c.basemaps.cartocdn.com/rastertiles/$cleanSlug/",
            "https://d.basemaps.cartocdn.com/rastertiles/$cleanSlug/"
        ),
        "© OpenStreetMap contributors © CARTO"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return buildCartoTileUrl(
                baseUrl = baseUrl,
                zoom = MapTileIndex.getZoom(pMapTileIndex),
                x = MapTileIndex.getX(pMapTileIndex),
                y = MapTileIndex.getY(pMapTileIndex),
                apiKey = apiKey
            )
        }
    }
}

private val SharedOpenTopoSource: OnlineTileSourceBase by lazy {
    object : OnlineTileSourceBase(
        "OpenTopoMap_Shared",
        0,
        17,
        256,
        ".png",
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/"
        ),
        "© OpenStreetMap contributors, SRTM | © OpenTopoMap"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return baseUrl +
                MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
        }
    }
}

private val cachedCartoTileSources: Map<MapBaseLayer, OnlineTileSourceBase> by lazy {
    val apiKey = resolveCartoApiKey()
    MapBaseLayer.entries
        .mapNotNull { layer ->
            layer.cartoStyleSlug?.let { slug ->
                layer to createCartoTileSource(styleSlug = slug, apiKey = apiKey)
            }
        }
        .toMap()
}

fun rfMapTileSource(layer: MapBaseLayer): ITileSource = when (layer) {
    MapBaseLayer.OSM_STANDARD -> TileSourceFactory.MAPNIK
    MapBaseLayer.OPEN_TOPO -> SharedOpenTopoSource
    else -> cachedCartoTileSources[layer] ?: TileSourceFactory.MAPNIK
}

enum class RfPinEndpoint {
    TX,
    RX
}

class RfPinDragController {
    var txMarker: Marker? = null
    var rxMarker: Marker? = null
    var connectingLine: Polyline? = null
    var draggingTarget: RfPinEndpoint? = null
    var downScreenX: Float = 0f
    var downScreenY: Float = 0f
    var touchOffsetX: Float = 0f
    var touchOffsetY: Float = 0f
    var hasMovedBeyondSlop: Boolean = false
}

class DirectRfPinDragOverlay(
    private val controller: RfPinDragController,
    private val onTxDragEnd: (Double, Double) -> Unit,
    private val onRxDragEnd: (Double, Double) -> Unit,
    private val onEndpointPinTap: (RfPinEndpoint) -> Unit
) : Overlay() {

    private val scratchPointA = Point()
    private val scratchPointB = Point()

    override fun draw(c: Canvas?, osmv: MapView?, shadow: Boolean) = Unit

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        val density = mapView.resources.displayMetrics.density
        val hitRadiusPx = 38f * density
        val touchSlopPx = 6f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount != 1) return false

                val txM = controller.txMarker
                val rxM = controller.rxMarker

                val txDist = if (txM != null) {
                    mapView.projection.toPixels(txM.position, scratchPointA)
                    hypot(event.x - scratchPointA.x.toFloat(), event.y - scratchPointA.y.toFloat())
                } else {
                    Float.MAX_VALUE
                }

                val rxDist = if (rxM != null) {
                    mapView.projection.toPixels(rxM.position, scratchPointB)
                    hypot(event.x - scratchPointB.x.toFloat(), event.y - scratchPointB.y.toFloat())
                } else {
                    Float.MAX_VALUE
                }

                val chosenTarget: RfPinEndpoint?
                val chosenPoint: Point?
                when {
                    txDist <= hitRadiusPx && txDist <= rxDist -> {
                        chosenTarget = RfPinEndpoint.TX
                        chosenPoint = scratchPointA
                    }
                    rxDist <= hitRadiusPx -> {
                        chosenTarget = RfPinEndpoint.RX
                        chosenPoint = scratchPointB
                    }
                    else -> {
                        chosenTarget = null
                        chosenPoint = null
                    }
                }

                if (chosenTarget != null && chosenPoint != null) {
                    controller.draggingTarget = chosenTarget
                    controller.downScreenX = event.x
                    controller.downScreenY = event.y
                    controller.touchOffsetX = chosenPoint.x.toFloat() - event.x
                    controller.touchOffsetY = chosenPoint.y.toFloat() - event.y
                    controller.hasMovedBeyondSlop = false
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val activeTarget = controller.draggingTarget ?: return false
                if (event.pointerCount > 1) return true

                val dx = event.x - controller.downScreenX
                val dy = event.y - controller.downScreenY
                if (!controller.hasMovedBeyondSlop && hypot(dx, dy) > touchSlopPx) {
                    controller.hasMovedBeyondSlop = true
                }

                if (controller.hasMovedBeyondSlop) {
                    val geo = mapView.projection.fromPixels(
                        (event.x + controller.touchOffsetX).toInt(),
                        (event.y + controller.touchOffsetY).toInt()
                    ) as GeoPoint

                    val draggedMarker = when (activeTarget) {
                        RfPinEndpoint.TX -> controller.txMarker
                        RfPinEndpoint.RX -> controller.rxMarker
                    }
                    draggedMarker?.position = geo

                    val txPos = controller.txMarker?.position
                    val rxPos = controller.rxMarker?.position
                    if (txPos != null && rxPos != null) {
                        controller.connectingLine?.setPoints(listOf(txPos, rxPos))
                    }
                    mapView.invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val finishedTarget = controller.draggingTarget ?: return false
                val moved = controller.hasMovedBeyondSlop
                controller.draggingTarget = null
                controller.hasMovedBeyondSlop = false

                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (moved) {
                        val finalPos = when (finishedTarget) {
                            RfPinEndpoint.TX -> controller.txMarker?.position
                            RfPinEndpoint.RX -> controller.rxMarker?.position
                        }
                        if (finalPos != null) {
                            when (finishedTarget) {
                                RfPinEndpoint.TX -> onTxDragEnd(finalPos.latitude, finalPos.longitude)
                                RfPinEndpoint.RX -> onRxDragEnd(finalPos.latitude, finalPos.longitude)
                            }
                        }
                    } else {
                        onEndpointPinTap(finishedTarget)
                    }
                }
                return true
            }
        }
        return false
    }
}

@Composable
fun RfMapLifecycleEffect(
    lifecycleOwner: LifecycleOwner,
    mapView: MapView?
) {
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
fun RfCrosshairReticle(
    isTxTarget: Boolean,
    modifier: Modifier = Modifier
) {
    val crosshairColor = if (isTxTarget) NodeSelfAmber else NodeMultiHopSky
    ComposeCanvas(modifier = modifier.size(32.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val armOuter = size.minDimension * 0.44f
        val armInner = size.minDimension * 0.18f
        val outerStroke = 4.dp.toPx()
        val innerStroke = 2.dp.toPx()

        fun drawArm(start: Offset, end: Offset) {
            drawLine(
                color = TacticalCanvas.copy(alpha = 0.85f),
                start = start,
                end = end,
                strokeWidth = outerStroke
            )
            drawLine(
                color = crosshairColor.copy(alpha = 0.92f),
                start = start,
                end = end,
                strokeWidth = innerStroke
            )
        }

        drawArm(Offset(cx - armOuter, cy), Offset(cx - armInner, cy))
        drawArm(Offset(cx + armInner, cy), Offset(cx + armOuter, cy))
        drawArm(Offset(cx, cy - armOuter), Offset(cx, cy - armInner))
        drawArm(Offset(cx, cy + armInner), Offset(cx, cy + armOuter))

        drawCircle(
            color = TacticalCanvas.copy(alpha = 0.9f),
            radius = 3.2.dp.toPx(),
            center = Offset(cx, cy)
        )
        drawCircle(
            color = Color.White,
            radius = 1.6.dp.toPx(),
            center = Offset(cx, cy)
        )
    }
}

fun resolveInitialRfMapCenter(
    txLat: Double?,
    txLon: Double?,
    rxLat: Double?,
    rxLon: Double?,
    existingNodes: List<MeshNode>
): GeoPoint = when {
    txLat != null && txLon != null && rxLat != null && rxLon != null ->
        GeoPoint((txLat + rxLat) * 0.5, (txLon + rxLon) * 0.5)
    txLat != null && txLon != null -> GeoPoint(txLat, txLon)
    rxLat != null && rxLon != null -> GeoPoint(rxLat, rxLon)
    else -> {
        val fallbackNode = existingNodes.firstOrNull { it.isSelf && it.hasValidGps }
            ?: existingNodes.firstOrNull { it.hasValidGps }
        if (fallbackNode != null) {
            GeoPoint(fallbackNode.latitude!!, fallbackNode.longitude!!)
        } else {
            GeoPoint(42.1292, -80.0851)
        }
    }
}

fun zoomMapToBoundsSafely(
    mapView: MapView,
    northLat: Double,
    eastLon: Double,
    southLat: Double,
    westLon: Double
) {
    val box = BoundingBox(northLat, eastLon, southLat, westLon)
    mapView.post {
        if (mapView.isAttachedToWindow && mapView.width > 0 && mapView.height > 0) {
            mapView.zoomToBoundingBox(box, true, 36)
        }
    }
}

fun colorForRfHopStatus(status: ViewshedPointStatus): Int = when (status) {
    ViewshedPointStatus.CLEAR -> 0xFF22C55E.toInt()
    ViewshedPointStatus.GRAZING -> 0xFFF59E0B.toInt()
    ViewshedPointStatus.OBSTRUCTED -> 0xFFEF4444.toInt()
}

fun createRfNetworkDotDrawable(
    context: Context,
    node: MeshNode,
    isCoveredClear: Boolean = false
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (if (isCoveredClear) 24f else 18f) * density
    val bitmap = createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    if (isCoveredClear) {
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x5522C55E
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, size * 0.48f, haloPaint)
    }

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1120.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, (if (isCoveredClear) 7.5f else 6f) * density, borderPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isCoveredClear) 0xFF22C55E.toInt() else node.effectiveColorInt
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, (if (isCoveredClear) 5.8f else 4.4f) * density, fillPaint)
    return bitmap.toDrawable(context.resources)
}

fun createRfEndpointBadgeDrawable(
    context: Context,
    label: String,
    accentColor: Int
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1120.toInt()
        textSize = 10f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val pillW = max(textPaint.measureText(label) + 12f * density, 36f * density)
    val pillH = 16f * density
    val ringR = 11f * density
    val w = max(pillW + 4f * density, ringR * 2f + 6f * density).toInt()
    val h = (ringR * 2f + pillH + 10f * density).toInt()

    val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = w / 2f
    val cy = ringR + 2f * density

    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (accentColor and 0x00FFFFFF) or 0x55000000
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, ringR, halo)

    val darkBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1120.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 8.5f * density, darkBorder)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 6.5f * density, fill)

    val innerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 2.5f * density, innerDot)

    val pillTop = cy + ringR - 1f * density
    val rect = RectF(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
    canvas.drawRoundRect(rect, 5f * density, 5f * density, fill)
    val base = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, cx, base, textPaint)

    return bitmap.toDrawable(context.resources)
}
