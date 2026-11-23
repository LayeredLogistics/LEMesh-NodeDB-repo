package com.example

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.BridgeParams
import com.example.data.LAYER_TINT_PALETTE
import com.example.data.LayerRenderMode
import com.example.data.PolarVisibilityField
import com.example.data.RelayBridgeEngine
import com.example.data.TerrariumDemRepository
import com.example.data.TerrariumTileGrid
import com.example.data.ViewshedEngine
import com.example.data.ViewshedLayerRepository
import com.example.data.ViewshedParams
import com.example.ui.CrispOverlayPool
import com.example.ui.CrispOverlaySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HighZoomCrispViewshedTest {

    private fun buildRidgeTileGrid(
        originLat: Double,
        originLon: Double,
        radiusKm: Double,
        ridgeMinLat: Double
    ): TerrariumTileGrid {
        val bounds = TerrariumDemRepository.paddedBoundsForRadius(originLat, originLon, radiusKm)
        val zoom = TerrariumDemRepository.recommendedZoomForBounds(
            northLat = bounds[0],
            southLat = bounds[1],
            westLon = bounds[2],
            eastLon = bounds[3],
            preferredRadiusKm = radiusKm.toInt()
        )
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(bounds[0], bounds[2], zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(bounds[1], bounds[3], zoom)

        // Encode 200m baseline (r=128, g=200, b=0) and 420m ridge crest (r=129, g=164, b=0)
        val flatRgb = Color.rgb(128, 200, 0)
        val ridgeRgb = Color.rgb(129, 164, 0)

        fun latToGlobalPy(lat: Double): Double {
            val latRad = Math.toRadians(lat)
            val n = (1 shl zoom).toDouble()
            return (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n * 256.0
        }

        val ridgeSouthPy = latToGlobalPy(ridgeMinLat)
        val ridgeNorthPy = latToGlobalPy(ridgeMinLat + 0.0035)

        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                val arr = IntArray(256 * 256)
                for (py in 0 until 256) {
                    val globalPy = ty * 256 + py
                    val isRidge = globalPy >= ridgeNorthPy && globalPy <= ridgeSouthPy
                    val color = if (isRidge) ridgeRgb else flatRgb
                    val rowStart = py * 256
                    for (px in 0 until 256) {
                        arr[rowStart + px] = color
                    }
                }
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = arr
            }
        }
        return TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
    }

    @Test
    fun `high density polar viewshed keeps street-level sub-region sharp with narrow anti-aliased boundary`() {
        assertEquals(1440, ViewshedEngine.RAY_COUNT)
        assertEquals(1024, ViewshedEngine.RASTER_SIZE)
        assertEquals(60.0, ViewshedEngine.fineStepMeters(10.0), 1e-4)

        val originLat = 42.1000
        val originLon = -80.1000
        val ridgeLat = 42.1350 // ~3.9 km north
        val grid = buildRidgeTileGrid(originLat, originLon, radiusKm = 10.0, ridgeMinLat = ridgeLat)

        val result = ViewshedEngine.computeViewshed(
            params = ViewshedParams(
                originLat = originLat,
                originLon = originLon,
                originLabel = "Crisp TX",
                antennaHeightMeters = 15.0,
                radiusKm = 10
            ),
            grid = grid,
            existingNodes = emptyList()
        )

        val field = result.field
        assertNotNull("Expected PolarVisibilityField attached to ViewshedResult", field)
        field!!
        assertEquals(1440, field.rayCount)
        assertEquals(60.0, field.stepMeters, 1e-4)
        assertEquals(1024, result.bitmap.width)
        assertEquals(1024, result.bitmap.height)

        // Zoom in to a ~1.8 km street-level sub-window straddling the ridge shadow boundary
        val zoomNorth = ridgeLat + 0.010
        val zoomSouth = ridgeLat - 0.006
        val zoomWest = originLon - 0.009
        val zoomEast = originLon + 0.009

        val zoomedBitmap = field.rasterizeSubRegion(
            renderNorthLat = zoomNorth,
            renderSouthLat = zoomSouth,
            renderWestLon = zoomWest,
            renderEastLon = zoomEast,
            width = 512,
            height = 512
        )

        val centerCol = 256
        val bottomAlpha = Color.alpha(zoomedBitmap.getPixel(centerCol, 490))
        val topAlpha = Color.alpha(zoomedBitmap.getPixel(centerCol, 20))
        assertTrue("Expected visible coverage south of ridge, got alpha=$bottomAlpha", bottomAlpha > 150)
        assertEquals("Expected shadowed area north of ridge to be transparent", 0, topAlpha)

        // Count partial AA boundary fade pixels (excluding opaque GRAZING=165 and CLEAR=225)
        var transitionPixels = 0
        for (py in 0 until 512) {
            val a = Color.alpha(zoomedBitmap.getPixel(centerCol, py))
            if (a in 1..155) {
                transitionPixels++
            }
        }
        assertTrue(
            "Expected narrow crisp AA boundary (1..10 transition px), got $transitionPixels",
            transitionPixels in 1..10
        )
    }

    @Test
    fun `bridge analysis attaches tx and rx polar fields and rasterizes crisp dual-zone sub-region`() {
        val txLat = 42.0800
        val txLon = -80.1000
        val rxLat = 42.1600
        val rxLon = -80.1000
        val params = BridgeParams(
            txLat = txLat,
            txLon = txLon,
            txLabel = "TX",
            rxLat = rxLat,
            rxLon = rxLon,
            rxLabel = "RX",
            txAntennaHeightMeters = 25.0,
            rxAntennaHeightMeters = 25.0,
            relayAntennaHeightMeters = 25.0,
            radiusKm = 8
        )
        val bounds = RelayBridgeEngine.computeBridgeBounds(params)
        val zoom = TerrariumDemRepository.recommendedZoomForBounds(
            northLat = bounds[0],
            southLat = bounds[1],
            westLon = bounds[2],
            eastLon = bounds[3],
            preferredRadiusKm = params.radiusKm
        )
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(bounds[0], bounds[2], zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(bounds[1], bounds[3], zoom)
        val flatRgb = Color.rgb(128, 200, 0)
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flatRgb }
            }
        }
        val grid = TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
        val result = RelayBridgeEngine.computeBridgeAnalysis(params, grid)

        assertNotNull(result.txField)
        assertNotNull(result.rxField)
        assertEquals(RelayBridgeEngine.RASTER_SIZE, result.bitmap.width)

        // Sub-region spanning TX-only (south), mutual bridge overlap (mid), and RX-only (north)
        val subBmp = PolarVisibilityField.rasterizeDualBridgeSubRegion(
            txField = result.txField!!,
            rxField = result.rxField!!,
            sensitivityDbm = params.sensitivityDbm,
            renderNorthLat = rxLat + 0.045,
            renderSouthLat = txLat - 0.045,
            renderWestLon = txLon - 0.04,
            renderEastLon = txLon + 0.04,
            width = 400,
            height = 600
        )

        val rxOnlyPx = subBmp.getPixel(200, 30)  // far north (RX only -> sky blue #38BDF8)
        val mutualPx = subBmp.getPixel(200, 300) // midpoint (mutual bridge -> vivid green/yellow)
        val txOnlyPx = subBmp.getPixel(200, 570) // far south (TX only -> amber #F59E0B)

        assertTrue("Expected RX-only sky blue dominant", Color.blue(rxOnlyPx) > Color.red(rxOnlyPx) * 2)
        assertTrue("Expected TX-only amber red/green > blue", Color.red(txOnlyPx) > Color.blue(txOnlyPx) * 4)
        assertTrue("Expected mutual bridge green dominant", Color.green(mutualPx) >= Color.red(mutualPx))
    }

    @Test
    fun `saved viewshed layer persists and reloads compressed polar field and cleans up on delete`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "viewshed_layers").deleteRecursively()

        val originLat = 42.1000
        val originLon = -80.1000
        val grid = buildRidgeTileGrid(originLat, originLon, radiusKm = 5.0, ridgeMinLat = 42.1300)
        val result = ViewshedEngine.computeViewshed(
            params = ViewshedParams(
                originLat = originLat,
                originLon = originLon,
                originLabel = "Persisted Tower",
                antennaHeightMeters = 20.0,
                radiusKm = 5
            ),
            grid = grid,
            existingNodes = emptyList()
        )
        val originalField = result.field!!

        val saved = ViewshedLayerRepository.saveNewLayer(
            context = context,
            name = "Tower 5km Crisp",
            result = result,
            tintColorArgb = LAYER_TINT_PALETTE[1],
            renderMode = LayerRenderMode.TINT,
            existingLayers = emptyList()
        )
        assertNotNull(saved.polarField)

        val reloadedList = ViewshedLayerRepository.loadAllLayers(context)
        assertEquals(1, reloadedList.size)
        val reloaded = reloadedList.first()
        assertNotNull("Expected .polar binary reloaded", reloaded.polarField)
        val rField = reloaded.polarField!!
        assertEquals(originalField.rayCount, rField.rayCount)
        assertEquals(originalField.numSteps, rField.numSteps)
        assertEquals(originalField.stepMeters, rField.stepMeters, 1e-6)
        assertEquals(originalField.statusGrid.size, rField.statusGrid.size)

        // Verify quantized RSSI matches within 0.05 dBm
        for (i in 0 until originalField.rssiGrid.size step 37) {
            assertEquals(originalField.statusGrid[i], rField.statusGrid[i])
            if (originalField.statusGrid[i].toInt() != 0) {
                assertTrue(abs(originalField.rssiGrid[i] - rField.rssiGrid[i]) <= 0.06f)
            }
        }

        // Verify tinted high-zoom sub-rasterization produces exact tint RGB
        val tintedZoom = rField.rasterizeSubRegion(
            width = 128,
            height = 128,
            tintColorArgb = LAYER_TINT_PALETTE[1]
        )
        val centerPx = tintedZoom.getPixel(64, 60)
        assertTrue(Color.alpha(centerPx) > 150)
        assertEquals(Color.red(LAYER_TINT_PALETTE[1]), Color.red(centerPx))
        assertEquals(Color.green(LAYER_TINT_PALETTE[1]), Color.green(centerPx))
        assertEquals(Color.blue(LAYER_TINT_PALETTE[1]), Color.blue(centerPx))

        ViewshedLayerRepository.deleteLayer(context, saved.id, emptyList())
        val polarBin = File(File(context.filesDir, "viewshed_layers"), "polar_${saved.id}.bin.gz")
        assertFalseExists(polarBin)
    }

    @Test
    fun `crisp overlay pool reuses buffers across opacity updates and disposes cleanly`() {
        val originLat = 42.1000
        val originLon = -80.1000
        val grid = buildRidgeTileGrid(originLat, originLon, radiusKm = 5.0, ridgeMinLat = 42.1300)
        val result = ViewshedEngine.computeViewshed(
            params = ViewshedParams(
                originLat = originLat,
                originLon = originLon,
                originLabel = "Pool Test",
                antennaHeightMeters = 20.0,
                radiusKm = 5
            ),
            grid = grid,
            existingNodes = emptyList()
        )
        val field = result.field!!
        val pool = CrispOverlayPool()
        val source = CrispOverlaySource.SingleField(polarField = field)

        val o1 = pool.obtainOverlay("viewshed_current", source, opacity = 0.72f)
        val o2 = pool.obtainOverlay("viewshed_current", source, opacity = 0.45f)
        assertSame(source.polarField, field)
        assertNotNull(o1)
        assertNotNull(o2)

        pool.pruneExcept(emptySet())
        pool.dispose()
    }

    private fun assertFalseExists(file: File) {
        assertTrue("Expected ${file.name} to be deleted", !file.exists())
        assertNull(if (file.exists()) file else null)
    }
}
