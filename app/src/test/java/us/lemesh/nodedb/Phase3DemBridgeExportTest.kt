package us.lemesh.nodedb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.lemesh.nodedb.data.BridgeParams
import us.lemesh.nodedb.data.BridgeRankTier
import us.lemesh.nodedb.data.CustomNodeStore
import us.lemesh.nodedb.data.CustomNodeStoreData
import us.lemesh.nodedb.data.ElevationRepository
import us.lemesh.nodedb.data.GeoJsonReportExporter
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.OfflineDemPackManager
import us.lemesh.nodedb.data.OfflineDemPreset
import us.lemesh.nodedb.data.OfflineDemUiState
import us.lemesh.nodedb.data.OfflineTileMissingException
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.data.TerrariumTileGrid
import us.lemesh.nodedb.data.TileCoord
import us.lemesh.nodedb.data.ViewshedPointStatus
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.OfflineMapsDialog
import us.lemesh.nodedb.ui.state.MapViewportState
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase3DemBridgeExportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TerrariumDemRepository.tileByteFetcherForTest = null
        TerrariumDemRepository.clearDemCache(context)
        TerrariumDemRepository.setOfflineOnlyMode(context, false)
        OfflineDemPackManager.initialize(context)
    }

    @After
    fun tearDown() {
        OfflineDemPackManager.cancelDownload()
        TerrariumDemRepository.tileByteFetcherForTest = null
        TerrariumDemRepository.clearDemCache(context)
        TerrariumDemRepository.setOfflineOnlyMode(context, false)
    }

    private fun encodeTerrariumPng(elevationMeters: Int): ByteArray {
        val raw = (elevationMeters + 32768).coerceIn(0, 65535)
        val r = (raw shr 8) and 0xFF
        val g = raw and 0xFF
        val rgb = Color.rgb(r, g, 0)
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(rgb)
        return ByteArrayOutputStream().use { baos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bmp.recycle()
            baos.toByteArray()
        }
    }

    private fun buildRidgeTerrainGrid(
        north: Double,
        south: Double,
        west: Double,
        east: Double
    ): TerrariumTileGrid {
        val zoom = TerrariumDemRepository.recommendedZoomForBounds(north, south, west, east, 12)
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(north, west, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(south, east, zoom)
        val nDouble = (1 shl zoom).toDouble()
        val tiles = mutableMapOf<Long, IntArray>()

        val baseRaw = 200 + 32768
        val baseRgb = Color.rgb((baseRaw shr 8) and 0xFF, baseRaw and 0xFF, 0)
        val ridgeRaw = 460 + 32768
        val ridgeRgb = Color.rgb((ridgeRaw shr 8) and 0xFF, ridgeRaw and 0xFF, 0)

        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                val pixels = IntArray(256 * 256)
                for (py in 0 until 256) {
                    val worldY = ty + (py + 0.5) / 256.0
                    val latRad = kotlin.math.atan(kotlin.math.sinh(Math.PI * (1.0 - 2.0 * worldY / nDouble)))
                    val lat = Math.toDegrees(latRad)
                    for (px in 0 until 256) {
                        val worldX = tx + (px + 0.5) / 256.0
                        val lon = (worldX / nDouble) * 360.0 - 180.0
                        // Blocking ridge wall across direct path (around lon -80.08, lat 42.08..42.12)
                        val isRidge = lon in -80.095..-80.065 && lat in 42.080..42.120
                        pixels[py * 256 + px] = if (isRidge) ridgeRgb else baseRgb
                    }
                }
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = pixels
            }
        }
        return TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
    }

    @Test
    fun `shared DEM pool coalesces concurrent fetches persists to filesDir and serves from disk offline`() = runBlocking {
        val fetchCounts = ConcurrentHashMap<TileCoord, AtomicInteger>()
        val pngBytes = encodeTerrariumPng(245)

        TerrariumDemRepository.tileByteFetcherForTest = { zoom, x, y ->
            fetchCounts.computeIfAbsent(TileCoord(zoom, x, y)) { AtomicInteger(0) }.incrementAndGet()
            pngBytes
        }

        val north = 42.14
        val south = 42.10
        val west = -80.12
        val east = -80.06
        val zoom = 12

        // 8 parallel callers request overlapping bounds concurrently
        val grids = coroutineScope {
            List(8) {
                async(Dispatchers.IO) {
                    TerrariumDemRepository.loadTileGridForBounds(
                        context = context,
                        northLat = north,
                        southLat = south,
                        westLon = west,
                        eastLon = east,
                        zoom = zoom
                    ).getOrThrow()
                }
            }.awaitAll()
        }

        assertEquals(8, grids.size)
        val (tx, ty) = TerrariumDemRepository.latLonToTile(42.12, -80.09, zoom)
        assertTrue(TerrariumDemRepository.isTileInMemoryForTest(zoom, tx, ty))

        // Every tile fetched at most once across all 8 parallel workers
        assertTrue(fetchCounts.isNotEmpty())
        fetchCounts.values.forEach { count ->
            assertEquals("Tile should only be fetched once due to in-flight coalescing", 1, count.get())
        }

        // Verify decoded elevation accuracy
        val sampled = grids.first().sampleElevation(42.12, -80.09)
        assertNotNull(sampled)
        assertEquals(245.0, sampled!!, 0.5)

        // Verify persistent hierarchical disk cache under filesDir/dem_tiles/{z}/{x}/{y}.png
        val diskFile = TerrariumDemRepository.demTileFile(context, zoom, tx, ty)
        assertTrue("Expected tile file at ${diskFile.absolutePath}", diskFile.exists() && diskFile.length() > 0L)

        // Evict memory cache and verify reload comes from disk with ZERO network fetches
        TerrariumDemRepository.clearMemoryCacheForTest()
        assertFalse(TerrariumDemRepository.isTileInMemoryForTest(zoom, tx, ty))

        val totalBefore = fetchCounts.values.sumOf { it.get() }
        val reloaded = TerrariumDemRepository.loadTileGridForBounds(
            context = context,
            northLat = north,
            southLat = south,
            westLon = west,
            eastLon = east,
            zoom = zoom
        ).getOrThrow()
        val totalAfter = fetchCounts.values.sumOf { it.get() }

        assertEquals("Disk reload must hit 0 network fetches", totalBefore, totalAfter)
        assertEquals(245.0, reloaded.sampleElevation(42.12, -80.09)!!, 0.5)
    }

    @Test
    fun `offline only DEM mode blocks uncached network requests and allows cached tiles`() = runBlocking {
        val networkCalls = AtomicInteger(0)
        val pngBytes = encodeTerrariumPng(310)
        TerrariumDemRepository.tileByteFetcherForTest = { _, _, _ ->
            networkCalls.incrementAndGet()
            pngBytes
        }

        TerrariumDemRepository.setOfflineOnlyMode(context, true)

        // 1. Uncached tile request in Offline-Only mode must fail with OfflineTileMissingException & 0 network hits
        val missingTileRes = TerrariumDemRepository.loadTileGridForBounds(
            context = context,
            northLat = 42.13,
            southLat = 42.11,
            westLon = -80.10,
            eastLon = -80.07,
            zoom = 12
        )
        assertTrue(missingTileRes.isFailure)
        assertTrue(missingTileRes.exceptionOrNull() is OfflineTileMissingException)
        assertEquals(0, networkCalls.get())

        // 2. Open-Meteo ElevationRepository must also be blocked in Offline-Only mode
        val openMeteoRes = ElevationRepository.fetchElevations(
            coordinates = listOf(42.12 to -80.08, 42.13 to -80.07),
            context = context
        )
        assertTrue(openMeteoRes.isFailure)
        assertTrue(openMeteoRes.exceptionOrNull() is OfflineTileMissingException)

        // 3. Pre-populate those exact tiles via pack cache and verify offline read succeeds
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(42.13, -80.10, 12)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(42.11, -80.07, 12)
        for (y in minTy..maxTy) {
            for (x in minTx..maxTx) {
                TerrariumDemRepository.ensureTileCached(
                    context = context,
                    coord = TileCoord(12, x, y),
                    allowNetworkWhenOffline = true
                ).getOrThrow()
            }
        }
        TerrariumDemRepository.clearMemoryCacheForTest()
        val callsBeforeOfflineRead = networkCalls.get()

        val cachedRes = TerrariumDemRepository.loadTileGridForBounds(
            context = context,
            northLat = 42.13,
            southLat = 42.11,
            westLon = -80.10,
            eastLon = -80.07,
            zoom = 12
        )
        assertTrue(cachedRes.isSuccess)
        assertEquals(callsBeforeOfflineRead, networkCalls.get())
        assertEquals(310.0, cachedRes.getOrThrow().sampleElevation(42.12, -80.085)!!, 0.5)
    }

    @Test
    fun `offline dem pack manager enumerates downloads cancels and clears cache`() = runBlocking {
        val pngBytes = encodeTerrariumPng(195)
        val erieTiles = OfflineDemPackManager.enumerateTilesForBounds(
            northLat = OfflineDemPreset.ERIE_PRESQUE_ISLE.northLat,
            southLat = OfflineDemPreset.ERIE_PRESQUE_ISLE.southLat,
            westLon = OfflineDemPreset.ERIE_PRESQUE_ISLE.westLon,
            eastLon = OfflineDemPreset.ERIE_PRESQUE_ISLE.eastLon,
            minZoom = OfflineDemPreset.ERIE_PRESQUE_ISLE.minZoom,
            maxZoom = OfflineDemPreset.ERIE_PRESQUE_ISLE.maxZoom
        )
        val nwPaTiles = OfflineDemPackManager.enumerateTilesForBounds(
            northLat = OfflineDemPreset.NW_PA_CORRIDOR.northLat,
            southLat = OfflineDemPreset.NW_PA_CORRIDOR.southLat,
            westLon = OfflineDemPreset.NW_PA_CORRIDOR.westLon,
            eastLon = OfflineDemPreset.NW_PA_CORRIDOR.eastLon,
            minZoom = OfflineDemPreset.NW_PA_CORRIDOR.minZoom,
            maxZoom = OfflineDemPreset.NW_PA_CORRIDOR.maxZoom
        )
        assertTrue(erieTiles.isNotEmpty())
        assertTrue(nwPaTiles.isNotEmpty())

        // Test cancellation mid-download using a gated tile fetcher
        val firstStarted = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        TerrariumDemRepository.tileByteFetcherForTest = { _, _, _ ->
            firstStarted.complete(Unit)
            releaseGate.await()
            pngBytes
        }

        val cancelJob = OfflineDemPackManager.startPresetDownload(context, OfflineDemPreset.NW_PA_CORRIDOR)
        firstStarted.await()
        OfflineDemPackManager.cancelDownload()
        releaseGate.complete(Unit)
        cancelJob.join()

        val afterCancelState = OfflineDemPackManager.uiState.value
        assertEquals(null, afterCancelState.activeDownload)
        assertTrue(afterCancelState.statusMessage?.contains("canceled", ignoreCase = true) == true)

        // Now download small viewport pack cleanly to completion
        TerrariumDemRepository.tileByteFetcherForTest = { _, _, _ -> pngBytes }
        val vpJob = OfflineDemPackManager.startViewportDownload(
            context = context,
            northLat = 42.14,
            southLat = 42.11,
            westLon = -80.10,
            eastLon = -80.06
        )
        vpJob.join()

        val completedState = OfflineDemPackManager.uiState.value
        assertEquals(null, completedState.activeDownload)
        assertTrue(completedState.cachedTileCount > 0)
        assertTrue(completedState.cacheSizeBytes > 0L)

        // Clear cache resets disk stats cleanly
        OfflineDemPackManager.clearDemCache(context)
        val clearedState = OfflineDemPackManager.uiState.value
        assertEquals(0, clearedState.cachedTileCount)
        assertEquals(0L, clearedState.cacheSizeBytes)
    }

    @Test
    fun `relay bridge two-stage pruning caps candidates and supports cooperative cancellation`() = runBlocking {
        val params = BridgeParams(
            txLat = 42.10,
            txLon = -80.14,
            txLabel = "TX Valley",
            rxLat = 42.10,
            rxLon = -80.02,
            rxLabel = "RX Valley",
            txAntennaHeightMeters = 8.0,
            rxAntennaHeightMeters = 8.0,
            relayAntennaHeightMeters = 15.0,
            radiusKm = 12
        )
        val bounds = RelayBridgeEngine.computeBridgeBounds(params)
        val grid = buildRidgeTerrainGrid(bounds[0], bounds[1], bounds[2], bounds[3])

        val result = RelayBridgeEngine.computeBridgeAnalysisSuspend(params, grid)
        val unpruned = RelayBridgeEngine.computeBridgeAnalysis(
            params = params,
            grid = grid,
            enableStage1Pruning = false
        )
        assertEquals(ViewshedPointStatus.OBSTRUCTED, result.directBaseline.status)
        assertTrue("Expected Stage 2 evaluation cap <= 160, was ${result.candidatesEvaluated}", result.candidatesEvaluated <= 160)
        assertTrue(
            "Pruned search (${result.candidatesEvaluated}) must evaluate fewer full Fresnel profiles than unpruned (${unpruned.candidatesEvaluated})",
            result.candidatesEvaluated < unpruned.candidatesEvaluated
        )
        assertTrue(result.topCandidates.isNotEmpty())
        val best = result.topCandidates.first()
        val unprunedBest = unpruned.topCandidates.first()
        assertEquals(1, best.rank)
        assertEquals(BridgeRankTier.MUTUAL_CLEAR_LOS, best.tier)
        assertTrue(best.minFresnelRatio >= 0.6)
        assertTrue(best.worstLinkMarginDb > 0.0)
        assertEquals(unprunedBest.latitude, best.latitude, 1e-5)
        assertEquals(unprunedBest.longitude, best.longitude, 1e-5)
        assertEquals(unprunedBest.worstLinkMarginDb, best.worstLinkMarginDb, 1e-3)
        assertEquals(unprunedBest.minFresnelRatio, best.minFresnelRatio, 1e-3)
        // Best candidate should sit on the 460 m ridge rather than 200 m valley
        assertTrue("Best candidate elevation ${best.groundElevationMeters} should be on ridge", best.groundElevationMeters > 350.0)

        // Verify cooperative cancellation aborts mid-search cleanly
        var calls = 0
        var caughtCancel = false
        try {
            RelayBridgeEngine.computeBridgeAnalysis(
                params = params,
                grid = grid,
                checkCancellation = {
                    calls++
                    if (calls >= 3) throw CancellationException("User canceled bridge search")
                }
            )
        } catch (_: CancellationException) {
            caughtCancel = true
        }
        assertTrue("Expected CancellationException when checkCancellation aborts", caughtCancel)
    }

    @Test
    fun `manual node opt-in coordinate masking persists and snaps GeoJSON export to ~1km grid`() {
        val storeFile = File(context.filesDir, "test_phase3_mask_store.json")
        if (storeFile.exists()) storeFile.delete()

        val exactLat = 42.129245
        val exactLon = -80.085134

        val maskedManualNode = MeshNode(
            num = 0xF0002222L,
            id = "!f0002222",
            longName = "Portable Field Rig",
            shortName = "PORT",
            hwModel = "HELTEC_V3",
            role = "CLIENT",
            latitude = exactLat,
            longitude = exactLon,
            altitude = 240,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = false,
            isSelf = false,
            snr = null,
            rssi = null,
            lastHeard = 1700000000L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}",
            isManual = true,
            maskLocationOnExport = true
        )
        val unmaskedImportedNode = MeshNode(
            num = 2001L,
            id = "!000007d1",
            longName = "Fixed Tower",
            shortName = "TOWR",
            hwModel = "RAK4631",
            role = "ROUTER",
            latitude = 42.151234,
            longitude = -80.041234,
            altitude = 310,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = true,
            isSelf = true,
            snr = 9.0,
            rssi = -72,
            lastHeard = 1700000000L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )

        // Persist & reload through CustomNodeStore
        CustomNodeStore.saveToFile(
            storeFile,
            CustomNodeStoreData(manualNodes = listOf(maskedManualNode), overrides = emptyMap())
        )
        val reloadedStore = CustomNodeStore.loadFromFile(storeFile)
        assertEquals(1, reloadedStore.manualNodes.size)
        assertTrue(reloadedStore.manualNodes.first().maskLocationOnExport)

        val nodes = listOf(unmaskedImportedNode, reloadedStore.manualNodes.first())
        val uiState = NodeMapUiState(
            allNodes = nodes,
            selfNode = unmaskedImportedNode
        )

        val geoJsonStr = MapExportEngine.buildNodeMapGeoJson(uiState)
        val features = JSONObject(geoJsonStr).getJSONArray("features")
        var foundMasked = false
        var foundUnmasked = false

        for (i in 0 until features.length()) {
            val feat = features.getJSONObject(i)
            val props = feat.getJSONObject("properties")
            if (props.optString("featureType") != "mesh_node") continue
            val coords = feat.getJSONObject("geometry").getJSONArray("coordinates")
            val outLon = coords.getDouble(0)
            val outLat = coords.getDouble(1)

            if (props.optString("id") == "!f0002222") {
                foundMasked = true
                assertTrue(props.optBoolean("locationMasked", false))
                val (expectedLat, expectedLon) = GeoJsonReportExporter.maskCoordinateApprox1Km(exactLat, exactLon)
                assertEquals(expectedLat, outLat, 1e-6)
                assertEquals(expectedLon, outLon, 1e-6)
                assertTrue(abs(outLat - exactLat) > 1e-4 || abs(outLon - exactLon) > 1e-4)
            } else if (props.optString("id") == "!000007d1") {
                foundUnmasked = true
                assertFalse(props.has("locationMasked"))
                assertEquals(42.151234, outLat, 1e-6)
                assertEquals(-80.041234, outLon, 1e-6)
            }
        }
        assertTrue(foundMasked && foundUnmasked)

        // Also test default maskLocationOnExport = false exports exact coordinates without locationMasked
        val defaultManual = maskedManualNode.copy(maskLocationOnExport = false)
        val defaultGeo = MapExportEngine.buildNodeMapGeoJson(
            NodeMapUiState(allNodes = listOf(defaultManual))
        )
        val defaultProps = JSONObject(defaultGeo).getJSONArray("features").getJSONObject(0)
        assertFalse(defaultProps.getJSONObject("properties").has("locationMasked"))
        val defaultCoords = defaultProps.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(exactLon, defaultCoords.getDouble(0), 1e-6)
        assertEquals(exactLat, defaultCoords.getDouble(1), 1e-6)
    }

    @Test
    fun `offline maps dialog renders offline toggle and triggers preset download and clear`() {
        var toggledValue: Boolean? = null
        var presetClicked: OfflineDemPreset? = null
        var cleared = false

        composeRule.setContent {
            LEMeshTheme {
                OfflineMapsDialog(
                    visible = true,
                    uiState = OfflineDemUiState(
                        isOfflineOnlyMode = false,
                        cachedTileCount = 12,
                        cacheSizeBytes = 2L * 1024L * 1024L
                    ),
                    mapViewProvider = { null },
                    viewportState = MapViewportState(),
                    onToggleOfflineOnly = { toggledValue = it },
                    onDownloadPreset = { presetClicked = it },
                    onDownloadViewport = { _, _, _, _ -> },
                    onCancelDownload = {},
                    onClearCache = { cleared = true },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("offline_only_mode_switch").assertIsDisplayed().performClick()
        assertEquals(true, toggledValue)

        composeRule.onNodeWithTag("offline_download_erie_button").assertIsDisplayed().performClick()
        assertEquals(OfflineDemPreset.ERIE_PRESQUE_ISLE, presetClicked)

        composeRule.onNodeWithTag("offline_clear_cache_button").assertIsDisplayed().performClick()
        assertTrue(cleared)
    }
}
