package us.lemesh.nodedb

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import us.lemesh.nodedb.data.LidarDataSource
import us.lemesh.nodedb.data.LidarDemRepository
import us.lemesh.nodedb.data.LidarElevationPatch
import us.lemesh.nodedb.data.LidarViewshedEngine
import us.lemesh.nodedb.data.LidarViewshedParams
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.ui.BridgeUiState
import us.lemesh.nodedb.ui.CompactLidarStreetBottomDrawer
import us.lemesh.nodedb.ui.LidarStreetModeActions
import us.lemesh.nodedb.ui.LidarStreetUiState
import us.lemesh.nodedb.ui.LidarStreetViewModel
import us.lemesh.nodedb.ui.MapBaseLayer
import us.lemesh.nodedb.ui.MeshRfWorkspaceScreen
import us.lemesh.nodedb.ui.NodeInspectorBottomSheet
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.RfNodeSiteActionSheet
import us.lemesh.nodedb.ui.ViewshedUiState
import us.lemesh.nodedb.ui.state.MapViewportState
import us.lemesh.nodedb.ui.state.WorkspaceMode
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.cos

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LidarViewshedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LidarDemRepository.usgsPatchFetcherForTest = null
        LidarDemRepository.clearMemoryCache()
        TerrariumDemRepository.tileByteFetcherForTest = null
        TerrariumDemRepository.setOfflineOnlyMode(app, false)
        TerrariumDemRepository.clearDemCache(app)
        File(app.filesDir, "lidar_patches").deleteRecursively()
    }

    private fun buildSyntheticBermPatch(
        centerLat: Double = 42.1292,
        centerLon: Double = -80.0851,
        halfExtentMeters: Double = 140.0,
        cols: Int = 281,
        rows: Int = 281,
        baseElevM: Float = 200f,
        bermNorthOffsetMeters: Double = 22.0,
        bermHeightMeters: Float = 4.0f
    ): LidarElevationPatch {
        val latDelta = halfExtentMeters / 111_132.0
        val lonDelta = halfExtentMeters / (111_320.0 * cos(Math.toRadians(centerLat)))
        val northLat = centerLat + latDelta
        val southLat = centerLat - latDelta
        val westLon = centerLon - lonDelta
        val eastLon = centerLon + lonDelta

        val grid = FloatArray(cols * rows) { baseElevM }
        for (r in 0 until rows) {
            val lat = northLat - (r.toDouble() / (rows - 1)) * (northLat - southLat)
            val northMeters = (lat - centerLat) * 111_132.0
            if (northMeters in (bermNorthOffsetMeters - 3.0)..(bermNorthOffsetMeters + 3.0)) {
                for (c in 0 until cols) {
                    grid[r * cols + c] = baseElevM + bermHeightMeters
                }
            }
        }
        return LidarElevationPatch(
            cols = cols,
            rows = rows,
            northLat = northLat,
            southLat = southLat,
            westLon = westLon,
            eastLon = eastLon,
            resolutionMeters = 0.5,
            elevationsMeters = grid,
            noDataValue = -9999f,
            source = LidarDataSource.USGS_3DEP_1M,
            sourceLabel = "USGS 3DEP 0.5m LiDAR"
        )
    }

    private fun buildTestGeoTiffBytes(
        cols: Int,
        rows: Int,
        westLon: Double,
        northLat: Double,
        pixelDegLon: Double,
        pixelDegLat: Double,
        elevations: FloatArray
    ): ByteArray {
        val tagCount = 9
        val ifdOffset = 8
        val ifdSize = 2 + tagCount * 12 + 4
        val scaleOffset = ifdOffset + ifdSize
        val tiepointOffset = scaleOffset + 24
        val pixelOffset = tiepointOffset + 48
        val totalSize = pixelOffset + elevations.size * 4

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte())
        buf.put('I'.code.toByte())
        buf.putShort(42)
        buf.putInt(ifdOffset)

        buf.position(ifdOffset)
        buf.putShort(tagCount.toShort())

        fun putTag(tag: Int, type: Int, count: Int, valueOrOffset: Int) {
            buf.putShort(tag.toShort())
            buf.putShort(type.toShort())
            buf.putInt(count)
            buf.putInt(valueOrOffset)
        }

        putTag(256, 4, 1, cols)
        putTag(257, 4, 1, rows)
        putTag(258, 3, 1, 32)
        putTag(259, 3, 1, 1)
        putTag(273, 4, 1, pixelOffset)
        putTag(278, 4, 1, rows)
        putTag(339, 3, 1, 3)
        putTag(33550, 12, 3, scaleOffset)
        putTag(33922, 12, 6, tiepointOffset)
        buf.putInt(0)

        buf.position(scaleOffset)
        buf.putDouble(pixelDegLon)
        buf.putDouble(pixelDegLat)
        buf.putDouble(0.0)

        buf.position(tiepointOffset)
        buf.putDouble(0.0)
        buf.putDouble(0.0)
        buf.putDouble(0.0)
        buf.putDouble(westLon)
        buf.putDouble(northLat)
        buf.putDouble(0.0)

        buf.position(pixelOffset)
        elevations.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun buildFlatTerrariumPng(elevationMeters: Double): ByteArray {
        val shifted = elevationMeters + 32768.0
        val r = (shifted / 256.0).toInt().coerceIn(0, 255)
        val g = (shifted - r * 256.0).toInt().coerceIn(0, 255)
        val b = (((shifted - r * 256.0 - g) * 256.0)).toInt().coerceIn(0, 255)
        val argb = Color.rgb(r, g, b)
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(argb)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun sampleMeshNode(): MeshNode = MeshNode(
        num = 505L,
        id = "!000001f9",
        longName = "Bayfront Porch",
        shortName = "BAYP",
        hwModel = "HELTEC_V3",
        role = "CLIENT",
        latitude = 42.1292,
        longitude = -80.0851,
        altitude = 200,
        hopsAway = 0,
        viaMqtt = false,
        isFavorite = false,
        isSelf = false,
        snr = 7.5,
        rssi = -78,
        lastHeard = 1_700_000_000L,
        deviceMetrics = null,
        environmentMetrics = null,
        rawJson = "{}"
    )

    @Test
    fun `1m step viewshed blocks 0ft TX over berm and clears at 45ft using SX1262 LongFast sensitivity`() = runBlocking {
        assertEquals(1.0, LidarViewshedEngine.STEP_METERS, 1e-6)
        assertEquals(-134.0, LidarViewshedEngine.SX1262_LONGFAST_SENSITIVITY_DBM, 1e-6)

        val txLat = 42.1292
        val txLon = -80.0851
        val patch = buildSyntheticBermPatch(centerLat = txLat, centerLon = txLon)

        val lowParams = LidarViewshedParams(
            originLat = txLat,
            originLon = txLon,
            originLabel = "Ground Sensor",
            txHeightFt = 0.0,
            rxHeightFt = 5.0,
            radiusMeters = 120
        )
        val highParams = lowParams.copy(originLabel = "Roof Mast", txHeightFt = 45.0)
        val highRxParams = lowParams.copy(rxHeightFt = 10.0)

        val lowResult = LidarViewshedEngine.computeViewshed(lowParams, patch)
        val highResult = LidarViewshedEngine.computeViewshed(highParams, patch)
        val highRxResult = LidarViewshedEngine.computeViewshed(highRxParams, patch)

        assertEquals(1.0, lowResult.field.stepMeters, 1e-6)
        assertEquals(-134.0, lowResult.field.sensitivityDbm, 1e-6)
        assertEquals(0.5, lowResult.terrainResolutionMeters, 1e-6)
        assertEquals("0.5m 3DEP", lowResult.shortSourceLabel)

        assertTrue(
            "45 ft TX (${highResult.coveredAreaSqMeters} m²) should clear berm better than 0 ft ground sensor (${lowResult.coveredAreaSqMeters} m²)",
            highResult.coveredAreaSqMeters > lowResult.coveredAreaSqMeters * 1.25
        )
        assertTrue(highResult.coveredAreaAcres > lowResult.coveredAreaAcres)
        assertTrue(highResult.corridorClearancePct > lowResult.corridorClearancePct)
        assertTrue(highRxResult.coveredAreaSqMeters >= lowResult.coveredAreaSqMeters)
    }

    @Test
    fun `usgs 3dep export url targets BestAvailable finest mosaic and sub-meter pixel grid`() {
        val centerLat = 42.1292
        val centerLon = -80.0851
        val boundsSmall = LidarDemRepository.computeQueryBounds(centerLat, centerLon, 300)
        val (wSmall, hSmall) = LidarDemRepository.computeGridDimensions(centerLat, boundsSmall, 300)
        val widthMetersSmall = (boundsSmall.eastLon - boundsSmall.westLon) * 111_320.0 * cos(Math.toRadians(centerLat))
        assertEquals(0.5, widthMetersSmall / wSmall, 0.05)
        assertTrue(wSmall >= 1000 && hSmall >= 1000)

        val boundsMid = LidarDemRepository.computeQueryBounds(centerLat, centerLon, 500)
        val (wMid, _) = LidarDemRepository.computeGridDimensions(centerLat, boundsMid, 500)
        val widthMetersMid = (boundsMid.eastLon - boundsMid.westLon) * 111_320.0 * cos(Math.toRadians(centerLat))
        assertEquals(0.77, widthMetersMid / wMid, 0.05)

        val boundsLarge = LidarDemRepository.computeQueryBounds(centerLat, centerLon, 700)
        val (wLarge, hLarge) = LidarDemRepository.computeGridDimensions(centerLat, boundsLarge, 700)
        assertEquals(1536, wLarge)
        assertEquals(1536, hLarge)

        val url = LidarDemRepository.buildUsgs3DepExportUrl(
            westLon = -80.09,
            southLat = 42.12,
            eastLon = -80.08,
            northLat = 42.13,
            cols = wSmall,
            rows = hSmall
        )
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("BestAvailable"))
        assertTrue(decoded.contains("esriMosaicAttribute"))
        assertTrue(decoded.contains("pixelType=F32"))
        assertTrue(decoded.contains("format=tiff"))
    }

    @Test
    fun `pure kotlin asc and geotiff parsers load local grids and repository caches then falls back`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LidarDemRepository.clearMemoryCache()
        File(app.filesDir, "lidar_patches").deleteRecursively()

        val ascText = """
            ncols        4
            nrows        4
            xllcorner    -80.0900
            yllcorner    42.1250
            cellsize     0.0010
            NODATA_value -9999
            210.0 211.0 212.0 213.0
            214.0 215.0 216.0 217.0
            218.0 219.0 220.0 221.0
            222.0 223.0 224.0 225.0
        """.trimIndent()
        val ascPatch = LidarDemRepository.parseLocalLidarStream(
            fileName = "erie_alley.asc",
            inputStream = ascText.byteInputStream(Charsets.UTF_8)
        )
        assertEquals(LidarDataSource.LOCAL_ASCII_GRID, ascPatch.source)
        assertEquals(4, ascPatch.cols)
        assertEquals(4, ascPatch.rows)
        val ascSample = ascPatch.sampleElevation(42.1270, -80.0880)
        assertNotNull(ascSample)
        assertTrue(ascSample!! in 210.0..225.0)

        val tiffCols = 24
        val tiffRows = 24
        val westLon = -80.0900
        val northLat = 42.1340
        val pixelDeg = 0.0005
        val tiffElevations = FloatArray(tiffCols * tiffRows) { idx ->
            205.0f + (idx % tiffCols) * 0.25f
        }
        val tiffBytes = buildTestGeoTiffBytes(
            cols = tiffCols,
            rows = tiffRows,
            westLon = westLon,
            northLat = northLat,
            pixelDegLon = pixelDeg,
            pixelDegLat = pixelDeg,
            elevations = tiffElevations
        )
        val tiffPatch = LidarDemRepository.parseLocalLidarStream(
            fileName = "pasda_dsm.tif",
            inputStream = tiffBytes.inputStream()
        )
        assertEquals(LidarDataSource.LOCAL_GEOTIFF, tiffPatch.source)
        assertEquals(tiffCols, tiffPatch.cols)
        assertEquals(tiffRows, tiffPatch.rows)
        val tiffSample = tiffPatch.sampleElevation(42.1292, -80.0851)
        assertNotNull(tiffSample)
        assertTrue(tiffSample!! in 205.0..212.0)

        val centerLat = 42.1292
        val centerLon = -80.0851
        LidarDemRepository.usgsPatchFetcherForTest = { w, s, e, n, c, r ->
            val pLon = (e - w) / c
            val pLat = (n - s) / r
            buildTestGeoTiffBytes(c, r, w, n, pLon, pLat, FloatArray(c * r) { 218.5f })
        }

        val liveFetch = LidarDemRepository.fetchPatchForViewshed(
            context = app,
            centerLat = centerLat,
            centerLon = centerLon,
            radiusMeters = 500
        )
        assertEquals(LidarDataSource.USGS_3DEP_1M, liveFetch.patch.source)
        assertEquals(218.5, liveFetch.patch.sampleElevation(centerLat, centerLon)!!, 0.2)

        LidarDemRepository.clearMemoryCache()
        LidarDemRepository.usgsPatchFetcherForTest = { _, _, _, _, _, _ ->
            throw IOException("Simulated offline field condition")
        }
        val cachedFetch = LidarDemRepository.fetchPatchForViewshed(
            context = app,
            centerLat = centerLat,
            centerLon = centerLon,
            radiusMeters = 500
        )
        assertEquals(LidarDataSource.CACHED_3DEP_1M, cachedFetch.patch.source)
        assertEquals(218.5, cachedFetch.patch.sampleElevation(centerLat, centerLon)!!, 0.2)

        LidarDemRepository.clearMemoryCache()
        File(app.filesDir, "lidar_patches").deleteRecursively()
        val flatTerrariumPng = buildFlatTerrariumPng(234.0)
        TerrariumDemRepository.tileByteFetcherForTest = { _, _, _ -> flatTerrariumPng }

        val fallbackFetch = LidarDemRepository.fetchPatchForViewshed(
            context = app,
            centerLat = 42.2000,
            centerLon = -80.1500,
            radiusMeters = 500
        )
        assertEquals(LidarDataSource.TERRARIUM_10M_FALLBACK, fallbackFetch.patch.source)
        assertTrue(fallbackFetch.note?.contains("10m Terrarium DEM") == true)
        assertEquals(234.0, fallbackFetch.patch.sampleElevation(42.2000, -80.1500)!!, 0.5)
    }

    @Test
    fun `lidar exports omit probe features and include sx1262 baseline dual units and lemesh branding`() = runBlocking {
        val txLat = 42.1292
        val txLon = -80.0851
        val patch = buildSyntheticBermPatch(centerLat = txLat, centerLon = txLon, bermHeightMeters = 0.2f)
        val params = LidarViewshedParams(
            originLat = txLat,
            originLon = txLon,
            originLabel = "Harbor Bench",
            txHeightFt = 35.0,
            rxHeightFt = 5.0,
            radiusMeters = 300
        )
        val viewshed = LidarViewshedEngine.computeViewshed(params, patch)

        val state = LidarStreetUiState(
            txLat = txLat,
            txLon = txLon,
            siteLabel = "Harbor Bench",
            groundElevationMeters = 200.0,
            txHeightFt = 35f,
            rxHeightFt = 5f,
            radiusMeters = 300,
            activeSourceLabel = patch.sourceLabel,
            activeShortSourceLabel = patch.shortBadgeLabel,
            currentResult = viewshed
        )

        val kml = MapExportEngine.buildLidarKml(state, MapBaseLayer.DARK_MATTER.displayName)
        assertTrue(kml.contains(MapExportEngine.EXPORT_BRAND_ATTRIBUTION))
        assertTrue(kml.contains("ac ("))
        assertTrue(kml.contains("SX1262 -134 dBm"))
        assertFalse(kml.contains("Probe RX"))

        val geoJson = MapExportEngine.buildLidarGeoJson(state, MapBaseLayer.DARK_MATTER.displayName)
        val root = JSONObject(geoJson)
        assertEquals("FeatureCollection", root.getString("type"))
        val meta = root.getJSONObject("metadata")
        assertEquals("LiDAR / Street Viewshed", meta.getString("screen"))
        assertEquals(1.0, meta.getDouble("gridStepMeters"), 1e-6)
        assertEquals(-134.0, meta.getDouble("sensitivityDbm"), 1e-6)
        assertTrue(meta.getString("assumedRxRadio").contains("SX1262"))
        assertFalse(geoJson.contains("lidar_probe_rx"))
        assertFalse(geoJson.contains("lidar_street_sightline"))

        val summary = MapExportEngine.buildLidarSummary(state, MapBaseLayer.DARK_MATTER)
        assertEquals("LiDAR Street Viewshed Export", summary.screenTitle)
        assertTrue(summary.metricLines.any { it.contains("ac") && it.contains("m²") })
        assertTrue(summary.metricLines.any { it.contains("SX1262 -134 dBm") })
    }

    @Test
    fun `lidar tab renders single TX top controls without probe toggle`() = runBlocking {
        val patch = buildSyntheticBermPatch()
        val params = LidarViewshedParams(
            originLat = 42.1292,
            originLon = -80.0851,
            originLabel = "Erie Street Survey",
            txHeightFt = 10.0,
            rxHeightFt = 5.0,
            radiusMeters = 250
        )
        val result = LidarViewshedEngine.computeViewshed(params, patch)

        val activeLidarState = LidarStreetUiState(
            txLat = 42.1292,
            txLon = -80.0851,
            siteLabel = "Erie Street Survey",
            groundElevationMeters = 200.0,
            activeDataSource = LidarDataSource.USGS_3DEP_1M,
            activeSourceLabel = patch.sourceLabel,
            activeShortSourceLabel = patch.shortBadgeLabel,
            currentResult = result
        )

        val activeModeState = androidx.compose.runtime.mutableStateOf(WorkspaceMode.MESH)

        composeRule.setContent {
            LEMeshTheme {
                Box(modifier = Modifier.size(390.dp, 820.dp)) {
                    MeshRfWorkspaceScreen(
                        activeMode = activeModeState.value,
                        viewportState = MapViewportState(),
                        meshState = NodeMapUiState(),
                        viewshedState = ViewshedUiState(),
                        bridgeState = BridgeUiState(),
                        lidarState = activeLidarState,
                        onSwitchMode = { activeModeState.value = it },
                        lidarActions = LidarStreetModeActions()
                    )
                }
            }
        }

        composeRule.onNodeWithTag("nav_tab_lidar").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(WorkspaceMode.LIDAR_STREET, activeModeState.value)

        composeRule.onNodeWithTag("lidar_drop_pin_button").assertIsDisplayed()
        composeRule.onNodeWithTag("lidar_source_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("lidar_top_mode_tx").assertDoesNotExist()
        composeRule.onNodeWithTag("lidar_top_mode_probe").assertDoesNotExist()
    }

    @Test
    fun `lidar drawer accepts 0-50ft TX, clamps RX 0-10ft, displays SX1262 pill, and removes sensitivity and probe card`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = LidarStreetViewModel(app)
        val patch = buildSyntheticBermPatch()
        val params = LidarViewshedParams(
            originLat = 42.1292,
            originLon = -80.0851,
            originLabel = "Erie Street Survey",
            txHeightFt = 10.0,
            rxHeightFt = 5.0,
            radiusMeters = 250
        )
        val result = LidarViewshedEngine.computeViewshed(params, patch)

        val drawerState = androidx.compose.runtime.mutableStateOf(
            LidarStreetUiState(
                txLat = 42.1292,
                txLon = -80.0851,
                siteLabel = "Erie Street Survey",
                groundElevationMeters = 200.0,
                activeDataSource = LidarDataSource.USGS_3DEP_1M,
                activeSourceLabel = patch.sourceLabel,
                activeShortSourceLabel = patch.shortBadgeLabel,
                currentResult = result
            )
        )

        composeRule.setContent {
            LEMeshTheme {
                CompactLidarStreetBottomDrawer(
                    uiState = drawerState.value,
                    plottedNodes = emptyList(),
                    controlsExpanded = true,
                    onToggleExpanded = {},
                    onSelectTxNode = {},
                    onTxHeightSliderChange = {},
                    onTxHeightTextChange = {
                        vm.updateTxHeightFromText(it)
                        drawerState.value = drawerState.value.copy(
                            txHeightText = vm.uiState.value.txHeightText,
                            txHeightFt = vm.uiState.value.txHeightFt
                        )
                    },
                    onRxHeightSliderChange = {},
                    onRxHeightTextChange = {
                        vm.updateRxHeightFromText(it)
                        drawerState.value = drawerState.value.copy(
                            rxHeightText = vm.uiState.value.rxHeightText,
                            rxHeightFt = vm.uiState.value.rxHeightFt
                        )
                    },
                    onRadiusSliderChange = {},
                    onRadiusTextChange = { vm.updateRadiusFromText(it) },
                    onFrequencyChange = {},
                    onTxPowerChange = {},
                    onAntennaGainChange = {},
                    onCableLossChange = {},
                    onOpacityChange = {},
                    onCalculateViewshed = {},
                    onImportLocalLidar = {},
                    onClearImportedLidar = {}
                )
            }
        }

        composeRule.onNodeWithTag("lidar_scorecard").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            String.format(Locale.US, "%.2f ac", result.coveredAreaAcres)
        ).assertIsDisplayed()

        composeRule.onNodeWithTag("lidar_tx_height_input").performScrollTo().performTextReplacement("45")
        assertEquals(45f, vm.uiState.value.txHeightFt, 1e-3f)

        composeRule.onNodeWithTag("lidar_rx_height_input").performScrollTo().performTextReplacement("25")
        assertEquals(10f, vm.uiState.value.rxHeightFt, 1e-3f)

        composeRule.onNodeWithTag("lidar_sx1262_spec_pill").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("lidar_advanced_rf_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("lidar_sensitivity_slider").assertDoesNotExist()
        composeRule.onNodeWithTag("lidar_probe_card").assertDoesNotExist()
    }

    @Test
    fun `node inspector bottom sheet offers lidar street viewshed handoff cta`() {
        var lidarHandoffClicked = false
        val node = sampleMeshNode()
        composeRule.setContent {
            LEMeshTheme {
                NodeInspectorBottomSheet(
                    node = node,
                    selfNode = null,
                    onDismiss = {},
                    onAssignLidarStreet = { lidarHandoffClicked = true }
                )
            }
        }

        composeRule.onNodeWithText("LiDAR Street Viewshed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("inspector_lidar_street_button").performScrollTo().performClick()
        assertTrue(lidarHandoffClicked)
    }

    @Test
    fun `lidar node site action sheet hides rx button for single TX workflow`() {
        var setTxClicked = false
        composeRule.setContent {
            LEMeshTheme {
                RfNodeSiteActionSheet(
                    node = sampleMeshNode(),
                    isCurrentTx = true,
                    isCurrentRx = false,
                    onSetAsTx = { setTxClicked = true },
                    onSetAsRx = null,
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("rf_node_set_tx_button").assertIsDisplayed().performClick()
        assertTrue(setTxClicked)
        composeRule.onNodeWithTag("rf_node_set_rx_button").assertDoesNotExist()
    }

    @Test
    fun `geotiff writer round-trips elevation patch bounds, floats, and nodata through offline importer`() {
        val patch = buildSyntheticBermPatch(
            centerLat = 42.1292,
            centerLon = -80.0851,
            halfExtentMeters = 80.0,
            cols = 40,
            rows = 36,
            baseElevM = 212.75f,
            bermNorthOffsetMeters = 15.0,
            bermHeightMeters = 3.5f
        )
        // Inject a NaN hole to confirm GDAL_NODATA (-9999) round-trips cleanly
        patch.elevationsMeters[0] = Float.NaN

        val exportedBytes = LidarDemRepository.buildGeoTiffBytes(patch)
        val reimported = LidarDemRepository.parseLocalLidarStream(
            fileName = "lemesh_lidar_0.5m_42.1292_-80.0851_500m.tif",
            inputStream = exportedBytes.inputStream()
        )

        assertEquals(LidarDataSource.LOCAL_GEOTIFF, reimported.source)
        assertEquals(patch.cols, reimported.cols)
        assertEquals(patch.rows, reimported.rows)
        assertEquals(patch.northLat, reimported.northLat, 1e-6)
        assertEquals(patch.southLat, reimported.southLat, 1e-6)
        assertEquals(patch.westLon, reimported.westLon, 1e-6)
        assertEquals(patch.eastLon, reimported.eastLon, 1e-6)
        assertEquals(-9999f, reimported.noDataValue, 1e-3f)
        assertEquals(
            patch.sampleElevation(42.1292, -80.0851)!!,
            reimported.sampleElevation(42.1292, -80.0851)!!,
            0.05
        )
        assertEquals(
            patch.sampleElevation(42.1292 + 15.0 / 111_132.0, -80.0851)!!,
            reimported.sampleElevation(42.1292 + 15.0 / 111_132.0, -80.0851)!!,
            0.05
        )
    }

    @Test
    fun `exportActivePatchToGeoTiff writes offline file that re-imports cleanly and UI renders Export GeoTIFF button`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val centerLat = 42.1292
        val centerLon = -80.0851
        LidarDemRepository.usgsPatchFetcherForTest = { w, s, e, n, c, r ->
            val pLon = (e - w) / c
            val pLat = (n - s) / r
            buildTestGeoTiffBytes(c, r, w, n, pLon, pLat, FloatArray(c * r) { 219.25f })
        }

        val vm = LidarStreetViewModel(app)
        val outFile = File(app.cacheDir, "offline_survey_export.tif")
        if (outFile.exists()) outFile.delete()

        vm.exportActivePatchToGeoTiff(Uri.fromFile(outFile))
        val deadline = System.currentTimeMillis() + 5_000L
        while (vm.uiState.value.isExportingGeoTiff && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(vm.uiState.value.isExportingGeoTiff)
        assertTrue(outFile.exists() && outFile.length() > 1000L)
        assertNotNull(vm.uiState.value.exportStatusMessage)
        assertTrue(vm.suggestedGeoTiffFileName().endsWith(".tif"))

        val roundTripped = LidarDemRepository.parseLocalLidarStream(
            fileName = outFile.name,
            inputStream = outFile.inputStream()
        )
        assertEquals(219.25, roundTripped.sampleElevation(centerLat, centerLon)!!, 0.1)

        var exportClicked = false
        composeRule.setContent {
            LEMeshTheme {
                CompactLidarStreetBottomDrawer(
                    uiState = vm.uiState.value,
                    plottedNodes = emptyList(),
                    controlsExpanded = true,
                    onToggleExpanded = {},
                    onSelectTxNode = {},
                    onTxHeightSliderChange = {},
                    onTxHeightTextChange = {},
                    onRxHeightSliderChange = {},
                    onRxHeightTextChange = {},
                    onRadiusSliderChange = {},
                    onRadiusTextChange = {},
                    onFrequencyChange = {},
                    onTxPowerChange = {},
                    onAntennaGainChange = {},
                    onCableLossChange = {},
                    onOpacityChange = {},
                    onCalculateViewshed = {},
                    onImportLocalLidar = {},
                    onExportGeoTiff = { exportClicked = true },
                    onClearImportedLidar = {}
                )
            }
        }

        composeRule.onNodeWithTag("lidar_import_file_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("lidar_export_geotiff_button").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(exportClicked)
        composeRule.onNodeWithTag("lidar_export_status_banner").performScrollTo().assertIsDisplayed()
        outFile.delete()
        Unit
    }
}
