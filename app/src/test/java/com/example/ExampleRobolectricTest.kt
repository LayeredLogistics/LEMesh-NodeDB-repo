package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.BridgeParams
import com.example.data.BridgeRankTier
import com.example.data.LAYER_TINT_PALETTE
import com.example.data.LayerRenderMode
import com.example.data.LinkClassification
import com.example.data.LosAnalyzer
import com.example.data.LosEndpoint
import com.example.data.LosStatus
import com.example.data.MeshGeoUtils
import com.example.data.MeshNode
import com.example.data.NodeDbParser
import com.example.data.RelayBridgeEngine
import com.example.data.TerrariumDemRepository
import com.example.data.TerrariumTileGrid
import com.example.data.ViewshedEngine
import com.example.data.ViewshedLayerRepository
import com.example.data.ViewshedParams
import com.example.data.ViewshedPointStatus
import com.example.data.ViewshedResult
import com.example.data.createTintedLayerBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LEMesh NodeDB Map", appName)
    }

    @Test
    fun `parse object keyed nodes with latitudeI and myNodeNum`() {
        val json = """
            {
              "myInfo": { "myNodeNum": 305419896 },
              "nodes": {
                "!12345678": {
                  "num": 305419896,
                  "user": {
                    "id": "!12345678",
                    "longName": "Erie Base",
                    "shortName": "ERIE",
                    "hwModel": "TBEAM",
                    "role": "ROUTER"
                  },
                  "position": {
                    "latitudeI": 421292000,
                    "longitudeI": -800851000,
                    "altitude": 220
                  },
                  "hopsAway": 0,
                  "viaMqtt": false,
                  "deviceMetrics": {
                    "batteryLevel": 95,
                    "voltage": 4.18,
                    "channelUtilization": 12.4,
                    "airUtilTx": 1.8
                  }
                },
                "!abcdef01": {
                  "num": 2882400001,
                  "user": {
                    "id": "!abcdef01",
                    "longName": "Presque Isle Relay",
                    "shortName": "PISL",
                    "hwModel": "HELTEC_V3"
                  },
                  "position": {
                    "latitude": 42.1620,
                    "longitude": -80.1110
                  },
                  "hopsAway": 0,
                  "snr": 8.5,
                  "rssi": -72,
                  "viaMqtt": false,
                  "isFavorite": true,
                  "environmentMetrics": {
                    "temperature": 20.0,
                    "relativeHumidity": 55.5,
                    "barometricPressure": 1013.2
                  }
                },
                "!00000002": {
                  "user": {
                    "id": "!00000002",
                    "longName": "Zero Island",
                    "shortName": "ZERO"
                  },
                  "position": {
                    "latitude": 0.0,
                    "longitude": 0.0
                  },
                  "viaMqtt": true,
                  "hopsAway": 2
                }
              }
            }
        """.trimIndent()

        val result = NodeDbParser.parse(json).getOrThrow()
        assertEquals(3, result.totalCount)
        assertEquals(2, result.plottedGpsCount)
        assertEquals(2, result.rfCount)
        assertEquals(1, result.mqttCount)
        assertEquals(1, result.noGpsCount)

        val self = result.selfNode
        assertNotNull(self)
        assertEquals("!12345678", self?.id)
        assertEquals(LinkClassification.SELF, self?.linkClassification)
        assertEquals(42.1292, self!!.latitude!!, 1e-4)
        assertEquals(-80.0851, self.longitude!!, 1e-4)
        assertEquals(95, self.deviceMetrics?.batteryLevel)

        val fav = result.nodes.first { it.id == "!abcdef01" }
        assertEquals(LinkClassification.FAVORITE, fav.linkClassification)
        assertEquals(68.0, MeshGeoUtils.celsiusToFahrenheit(fav.environmentMetrics!!.temperature!!), 0.1)

        val zeroGps = result.nodes.first { it.id == "!00000002" }
        assertFalse(zeroGps.hasValidGps)
        assertNull(zeroGps.latitude)
        assertEquals(LinkClassification.MQTT, zeroGps.linkClassification)
    }

    @Test
    fun `parse array nodes with fallback self detection`() {
        val json = """
            {
              "nodes": [
                {
                  "num": 1001,
                  "user": { "id": "!000003e9", "longName": "Local Unit", "shortName": "LOC" },
                  "hopsAway": 0,
                  "viaMqtt": false
                },
                {
                  "num": 1002,
                  "user": { "id": "!000003ea", "longName": "Direct Peer", "shortName": "DIR" },
                  "hopsAway": 0,
                  "snr": 6.2,
                  "viaMqtt": false
                },
                {
                  "num": 1003,
                  "user": { "id": "!000003eb", "longName": "MultiHop Peer", "shortName": "HOP" },
                  "hopsAway": 2,
                  "viaMqtt": false
                }
              ]
            }
        """.trimIndent()

        val result = NodeDbParser.parse(json).getOrThrow()
        assertEquals("!000003e9", result.selfNode?.id)
        assertTrue(result.selfNode?.isSelf == true)
        assertEquals(LinkClassification.DIRECT_RF, result.nodes[1].linkClassification)
        assertEquals(LinkClassification.MULTI_HOP_RF, result.nodes[2].linkClassification)
    }

    @Test
    fun `bearing and direct rf link distance calculations`() {
        val bearingEast = MeshGeoUtils.bearingDegrees(42.1292, -80.0851, 42.1292, -79.9000)
        assertEquals(90.0, bearingEast, 1.0)
        assertEquals("E", MeshGeoUtils.formatCardinalDirection(bearingEast))

        val km = MeshGeoUtils.haversineKm(42.1292, -80.0851, 42.1620, -80.1110)
        val miles = MeshGeoUtils.kmToMiles(km)
        assertTrue(km > 3.5 && km < 5.0)
        assertEquals(km * 0.62137119, miles, 1e-4)
    }

    @Test
    fun `los analyzer samples 100 points and classifies clear marginal and obstructed`() {
        val pointA = LosEndpoint(42.1000, -80.1000, "TX", "TX")
        val pointB = LosEndpoint(42.1900, -80.1000, "RX", "RX") // ~10.0 km north
        val coords = LosAnalyzer.sampleGreatCirclePath(pointA, pointB, 100)
        assertEquals(100, coords.size)
        assertEquals(pointA.latitude, coords.first().first, 1e-5)
        assertEquals(pointB.latitude, coords.last().first, 1e-5)

        // 1. Flat terrain at 200m with 30m antenna heights -> Clear LoS
        val flatElev = List(100) { 200.0 }
        val clearResult = LosAnalyzer.analyze(
            pointA = pointA,
            pointB = pointB,
            coordinates = coords,
            elevationsMeters = flatElev,
            txHeightMeters = 30.0,
            rxHeightMeters = 30.0,
            frequencyMhz = 906.875
        )
        assertEquals(LosStatus.CLEAR_LOS, clearResult.status)
        assertTrue(clearResult.totalDistanceKm in 9.8..10.2)
        // FSPL for 10 km @ 906.875 MHz ~= 111.59 dB
        assertTrue(clearResult.fsplDb in 110.5..112.5)
        // Midpoint Earth bulge for 10 km at k=4/3 ~= 1.47 m
        val midSample = clearResult.samples[50]
        assertEquals(1.47, midSample.earthBulgeMeters, 0.15)
        // Midpoint F1 for 10 km @ 0.906875 GHz ~= 28.76 m
        assertEquals(28.76, midSample.fresnelRadiusMeters, 0.5)

        // 2. Ridge intruding into 60% Fresnel zone (below LoS ray of 230m, above 230 - 17.25 = 212.75m)
        val marginalElev = MutableList(100) { 200.0 }.apply { this[50] = 220.0 }
        val marginalResult = LosAnalyzer.analyze(
            pointA = pointA,
            pointB = pointB,
            coordinates = coords,
            elevationsMeters = marginalElev,
            txHeightMeters = 30.0,
            rxHeightMeters = 30.0,
            frequencyMhz = 906.875
        )
        assertEquals(LosStatus.MARGINAL_GRAZING, marginalResult.status)
        assertEquals(50, marginalResult.worstSample.index)

        // 3. Peak intersecting geometric LoS ray (> 230m)
        val obstructedElev = MutableList(100) { 200.0 }.apply { this[50] = 245.0 }
        val obstructedResult = LosAnalyzer.analyze(
            pointA = pointA,
            pointB = pointB,
            coordinates = coords,
            elevationsMeters = obstructedElev,
            txHeightMeters = 30.0,
            rxHeightMeters = 30.0,
            frequencyMhz = 906.875
        )
        assertEquals(LosStatus.OBSTRUCTED, obstructedResult.status)
        assertEquals(50, obstructedResult.worstSample.index)
        assertTrue(obstructedResult.worstClearanceMeters < -15.0)
    }

    @Test
    fun `terrarium rgb pixel decoding computes exact elevations`() {
        assertEquals(0.0, TerrariumDemRepository.decodeTerrariumPixel(Color.rgb(128, 0, 0)), 1e-4)
        assertEquals(300.0, TerrariumDemRepository.decodeTerrariumPixel(Color.rgb(129, 44, 0)), 1e-4)
        assertEquals(-100.0, TerrariumDemRepository.decodeTerrariumPixel(Color.rgb(127, 156, 0)), 1e-4)
        assertEquals(200.5, TerrariumDemRepository.decodeTerrariumPixel(Color.rgb(128, 200, 128)), 1e-4)
    }

    @Test
    fun `viewshed engine computes clear coverage footprint and blocks obstructed rays`() {
        val originLat = 42.1292
        val originLon = -80.0851
        val radiusKm = 10
        val zoom = TerrariumDemRepository.recommendedZoomForRadius(radiusKm)
        assertEquals(12, zoom)

        // Build synthetic Terrarium tiles around Erie PA at flat 200 m ASL
        val latDelta = (radiusKm / 111.12) * 1.15
        val cosLat = cos(Math.toRadians(originLat))
        val lonDelta = (radiusKm / (111.32 * cosLat)) * 1.15
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(originLat + latDelta, originLon - lonDelta, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(originLat - latDelta, originLon + lonDelta, zoom)

        val flat200mRgb = Color.rgb(128, 200, 0) // 128*256 + 200 - 32768 = 200.0 m
        val flatTiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                flatTiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200mRgb }
            }
        }
        val flatGrid = TerrariumTileGrid(zoom = zoom, tiles = flatTiles, tileCount = flatTiles.size)

        val northNode = MeshNode(
            id = "!00001111",
            num = 0x1111L,
            longName = "North Ridge Node",
            shortName = "NRTH",
            hwModel = "TBEAM",
            role = "ROUTER",
            latitude = 42.1650, // ~4.0 km north
            longitude = -80.0851,
            altitude = 200,
            hopsAway = 0,
            snr = 9.0,
            rssi = -78,
            viaMqtt = false,
            isFavorite = false,
            isSelf = false,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        val southNode = northNode.copy(
            id = "!00002222",
            num = 0x2222L,
            longName = "South Harbor Node",
            shortName = "STH",
            latitude = 42.0930, // ~4.0 km south
            longitude = -80.0851
        )

        val params = ViewshedParams(
            originLat = originLat,
            originLon = originLon,
            originLabel = "Erie Test Site",
            antennaHeightMeters = 55.0,
            radiusKm = radiusKm,
            frequencyMhz = 906.875,
            txPowerDbm = 30.0,
            antennaGainDbi = 5.8,
            sensitivityDbm = -130.0
        )

        val clearResult = ViewshedEngine.computeViewshed(params, flatGrid, listOf(northNode, southNode))
        assertEquals(ViewshedEngine.RASTER_SIZE, clearResult.bitmap.width)
        assertEquals(ViewshedEngine.RASTER_SIZE, clearResult.bitmap.height)
        assertEquals(200.0, clearResult.groundElevationMeters, 0.2)
        // Over flat terrain with a 55m mast, path clears 60% F1 across the full 10km circle
        assertTrue("Expected > 250 km^2 covered area, got ${clearResult.coveredAreaKm2}", clearResult.coveredAreaKm2 > 250.0)
        assertTrue("Expected > 9.0 km clear range, got ${clearResult.maxUnobstructedRangeKm}", clearResult.maxUnobstructedRangeKm > 9.0)
        assertEquals(2, clearResult.coveredClearNodes.size)

        // Check that lowering mast to 10m reduces Clear LoS range due to 60% Fresnel zone grazing
        val lowMastResult = ViewshedEngine.computeViewshed(params.copy(antennaHeightMeters = 10.0), flatGrid, listOf(northNode, southNode))
        assertTrue(
            "10m mast clear range (${lowMastResult.maxUnobstructedRangeKm}) should be less than 55m mast (${clearResult.maxUnobstructedRangeKm})",
            lowMastResult.maxUnobstructedRangeKm < clearResult.maxUnobstructedRangeKm
        )

        // Check FSPL link budget math for ~4 km @ 906.875 MHz:
        // EIRP = 35.8 dBm, FSPL ~= 103.6 dB -> expected RSSI around -67.8 dBm
        val northCoverage = clearResult.coveredClearNodes.first { it.node.id == "!00001111" }
        assertTrue(northCoverage.predictedRssiDbm in -72.0..-64.0)

        // Now place a 450m ridge across the tile containing midpoint (42.1471, -80.0851) (~2 km north)
        val obstructedTiles = flatTiles.mapValues { (_, arr) -> arr.clone() }.toMutableMap()
        val ridgeLat = 42.1471
        val n = (1 shl zoom).toDouble()
        val ridgeLatRad = Math.toRadians(ridgeLat)
        val ridgeWorldX = ((originLon + 180.0) / 360.0) * n
        val ridgeWorldY = (1.0 - ln(tan(ridgeLatRad) + 1.0 / cos(ridgeLatRad)) / Math.PI) / 2.0 * n
        val ridgeTx = floor(ridgeWorldX).toInt()
        val ridgeTy = floor(ridgeWorldY).toInt()
        val ridgePy = ((ridgeWorldY - ridgeTy) * 256.0).toInt().coerceIn(5, 250)
        val ridgePx = ((ridgeWorldX - ridgeTx) * 256.0).toInt().coerceIn(20, 235)
        val ridgeRgb = Color.rgb(129, 194, 0) // 129*256 + 194 - 32768 = 450.0 m

        val ridgeTileArr = obstructedTiles.getValue(TerrariumTileGrid.tileKey(ridgeTx, ridgeTy))
        for (py in (ridgePy - 4)..(ridgePy + 4)) {
            for (px in (ridgePx - 20)..(ridgePx + 20)) {
                ridgeTileArr[py * 256 + px] = ridgeRgb
            }
        }
        val obstructedGrid = TerrariumTileGrid(zoom = zoom, tiles = obstructedTiles, tileCount = obstructedTiles.size)

        val obstructedResult = ViewshedEngine.computeViewshed(params, obstructedGrid, listOf(northNode, southNode))
        assertTrue(
            "Terrain ridge should reduce covered area (${obstructedResult.coveredAreaKm2} < ${clearResult.coveredAreaKm2})",
            obstructedResult.coveredAreaKm2 < clearResult.coveredAreaKm2
        )
        assertFalse(
            "North node behind 450m ridge should not have Clear LoS",
            obstructedResult.coveredClearNodes.any { it.node.id == "!00001111" }
        )
        assertTrue(
            "South node in opposite direction should remain Clear LoS",
            obstructedResult.coveredClearNodes.any { it.node.id == "!00002222" }
        )
    }

    @Test
    fun `relay bridge engine finds optimal mutual Clear LoS ridge between obstructed TX and RX`() {
        val txLat = 42.1000
        val txLon = -80.1000
        val rxLat = 42.2080 // ~12.0 km north
        val rxLon = -80.1000

        val params = BridgeParams(
            txLat = txLat,
            txLon = txLon,
            txLabel = "TX Valley",
            rxLat = rxLat,
            rxLon = rxLon,
            rxLabel = "RX Valley",
            txAntennaHeightMeters = 10.0,
            rxAntennaHeightMeters = 10.0,
            relayAntennaHeightMeters = 15.0,
            radiusKm = 15,
            frequencyMhz = 906.875,
            txPowerDbm = 30.0,
            antennaGainDbi = 5.8,
            sensitivityDbm = -130.0
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

        val flat200mRgb = Color.rgb(128, 200, 0) // 200.0 m
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200mRgb }
            }
        }

        // Paint an east-west 360m mountain ridge across the midpoint (42.1540) blocking direct TX->RX
        val midLat = (txLat + rxLat) * 0.5
        val n = (1 shl zoom).toDouble()
        val midLatRad = Math.toRadians(midLat)
        val midWorldY = (1.0 - ln(tan(midLatRad) + 1.0 / cos(midLatRad)) / Math.PI) / 2.0 * n
        val midWorldX = ((txLon + 180.0) / 360.0) * n
        val ridgeTy = floor(midWorldY).toInt()
        val ridgeTx = floor(midWorldX).toInt()
        val ridgePy = ((midWorldY - ridgeTy) * 256.0).toInt().coerceIn(8, 247)
        val ridgeRgb = Color.rgb(129, 104, 0) // 129*256 + 104 - 32768 = 360.0 m

        for (tx in (ridgeTx - 1)..(ridgeTx + 1)) {
            val arr = tiles[TerrariumTileGrid.tileKey(tx, ridgeTy)] ?: continue
            for (py in (ridgePy - 6)..(ridgePy + 6)) {
                for (px in 0 until 256) {
                    arr[py * 256 + px] = ridgeRgb
                }
            }
        }

        val grid = TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
        val result = RelayBridgeEngine.computeBridgeAnalysis(params, grid)

        assertEquals(RelayBridgeEngine.RASTER_SIZE, result.bitmap.width)
        assertEquals(RelayBridgeEngine.RASTER_SIZE, result.bitmap.height)
        assertEquals(ViewshedPointStatus.OBSTRUCTED, result.directBaseline.status)
        assertTrue(result.txCoveredAreaKm2 > 20.0)
        assertTrue(result.rxCoveredAreaKm2 > 20.0)
        assertTrue(result.mutualOverlapAreaKm2 > 1.0)
        assertTrue(result.topCandidates.isNotEmpty())

        val best = result.topCandidates.first()
        assertEquals(1, best.rank)
        assertEquals(BridgeRankTier.MUTUAL_CLEAR_LOS, best.tier)
        assertEquals(ViewshedPointStatus.CLEAR, best.txHop.status)
        assertEquals(ViewshedPointStatus.CLEAR, best.rxHop.status)
        assertTrue("Expected ridge crest elevation > 300m, got ${best.groundElevationMeters}", best.groundElevationMeters > 300.0)
        assertTrue("Expected strong positive worst-case link margin > 40 dB, got ${best.worstLinkMarginDb}", best.worstLinkMarginDb > 40.0)
    }

    @Test
    fun `saved viewshed layer repository persists toggles mode switches and deletes cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val layersDir = java.io.File(context.filesDir, "viewshed_layers")
        if (layersDir.exists()) layersDir.deleteRecursively()

        // Create a small test bitmap with transparent, grazing (alpha=165), and clear (alpha=225) pixels
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.TRANSPARENT)
        bitmap.setPixel(1, 0, Color.argb(165, 249, 115, 22))
        bitmap.setPixel(2, 0, Color.argb(225, 34, 197, 94))

        val tintColor = LAYER_TINT_PALETTE[2] // Purple 0xFFA855F7
        val tinted = createTintedLayerBitmap(bitmap, tintColor)
        assertEquals(0, Color.alpha(tinted.getPixel(0, 0)))
        assertEquals(165, Color.alpha(tinted.getPixel(1, 0)))
        assertEquals(225, Color.alpha(tinted.getPixel(2, 0)))
        assertEquals(Color.red(tintColor), Color.red(tinted.getPixel(2, 0)))
        assertEquals(Color.green(tintColor), Color.green(tinted.getPixel(2, 0)))
        assertEquals(Color.blue(tintColor), Color.blue(tinted.getPixel(2, 0)))

        val dummyResult = ViewshedResult(
            params = ViewshedParams(
                originLat = 42.1292,
                originLon = -80.0851,
                originLabel = "Erie Summit",
                antennaHeightMeters = 20.0,
                radiusKm = 25
            ),
            groundElevationMeters = 240.0,
            bitmap = bitmap,
            northLat = 42.35,
            southLat = 41.90,
            westLon = -80.35,
            eastLon = -79.80,
            coveredAreaKm2 = 312.5,
            maxUnobstructedRangeKm = 19.4,
            coveredClearNodes = emptyList(),
            tileCountUsed = 4,
            computeTimeMs = 12L
        )

        val saved = ViewshedLayerRepository.saveNewLayer(
            context = context,
            name = "Erie Summit 20m",
            result = dummyResult,
            tintColorArgb = tintColor,
            renderMode = LayerRenderMode.TINT,
            existingLayers = emptyList()
        )
        assertTrue(saved.isEnabled)
        assertEquals(LayerRenderMode.TINT, saved.renderMode)
        assertEquals("Erie Summit 20m", saved.name)
        assertTrue(java.io.File(layersDir, "rssi_${saved.id}.png").exists())

        // Reload fresh from disk
        val reloaded = ViewshedLayerRepository.loadAllLayers(context)
        assertEquals(1, reloaded.size)
        val firstReload = reloaded.first()
        assertEquals(saved.id, firstReload.id)
        assertEquals("Erie Summit 20m", firstReload.name)
        assertEquals(tintColor, firstReload.tintColorArgb)
        assertEquals(LayerRenderMode.TINT, firstReload.renderMode)
        assertTrue(firstReload.isEnabled)
        assertNotNull(firstReload.tintedBitmap)
        assertEquals(225, Color.alpha(firstReload.displayBitmap().getPixel(2, 0)))

        // Disable and switch to RSSI mode, then persist metadata and reload
        val modified = reloaded.map {
            it.copy(isEnabled = false, renderMode = LayerRenderMode.RSSI)
        }
        ViewshedLayerRepository.persistMetadata(context, modified)

        val reloadedAfterEdit = ViewshedLayerRepository.loadAllLayers(context)
        assertEquals(1, reloadedAfterEdit.size)
        assertFalse(reloadedAfterEdit.first().isEnabled)
        assertEquals(LayerRenderMode.RSSI, reloadedAfterEdit.first().renderMode)

        // Delete layer and verify index + PNG file are both removed
        ViewshedLayerRepository.deleteLayer(context, saved.id, emptyList())
        assertFalse(java.io.File(layersDir, "rssi_${saved.id}.png").exists())
        val reloadedAfterDelete = ViewshedLayerRepository.loadAllLayers(context)
        assertTrue(reloadedAfterDelete.isEmpty())
    }

    @Test
    fun `clear current viewshed and start fresh reset overlays without deleting saved layers`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val layersDir = java.io.File(context.filesDir, "viewshed_layers")
        if (layersDir.exists()) layersDir.deleteRecursively()

        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(1, 1, Color.argb(225, 34, 197, 94))
        val dummyResult = ViewshedResult(
            params = ViewshedParams(
                originLat = 42.1292,
                originLon = -80.0851,
                originLabel = "Harbor Tower",
                antennaHeightMeters = 15.0,
                radiusKm = 25
            ),
            groundElevationMeters = 190.0,
            bitmap = bitmap,
            northLat = 42.35,
            southLat = 41.90,
            westLon = -80.35,
            eastLon = -79.80,
            coveredAreaKm2 = 180.0,
            maxUnobstructedRangeKm = 14.5,
            coveredClearNodes = emptyList(),
            tileCountUsed = 4,
            computeTimeMs = 10L
        )

        val savedLayer = ViewshedLayerRepository.saveNewLayer(
            context = context,
            name = "Harbor 15m",
            result = dummyResult,
            tintColorArgb = LAYER_TINT_PALETTE.first(),
            renderMode = LayerRenderMode.TINT,
            existingLayers = emptyList()
        )
        assertTrue(savedLayer.isEnabled)

        val vm = com.example.ui.ViewshedViewModel(context)
        // Wait briefly for init loadPersistedLayers coroutine to finish, then inject active result via reflection
        // or test state transitions directly with ViewshedUiState + repository persistence
        val uiStateField = com.example.ui.ViewshedViewModel::class.java.getDeclaredField("_uiState")
        uiStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = uiStateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.ui.ViewshedUiState>
        stateFlow.value = stateFlow.value.copy(
            currentResult = dummyResult,
            pinnedSnapshot = dummyResult,
            isSnapshotPinned = true,
            showSnapshotOnMap = true,
            savedLayers = listOf(savedLayer)
        )
        assertTrue(vm.uiState.value.hasAnyVisibleMapOverlay)

        // 1. Clear Current clears only currentResult while keeping snapshot and saved layers enabled
        vm.clearCurrentViewshed()
        assertNull(vm.uiState.value.currentResult)
        assertNotNull(vm.uiState.value.pinnedSnapshot)
        assertTrue(vm.uiState.value.savedLayers.single().isEnabled)
        assertTrue(vm.uiState.value.hasAnyVisibleMapOverlay)

        // 2. Start Fresh clears currentResult, clears pinned snapshot, and disables all saved layers (without deleting)
        stateFlow.value = stateFlow.value.copy(currentResult = dummyResult)
        vm.clearAllAndStartFresh()
        assertNull(vm.uiState.value.currentResult)
        assertNull(vm.uiState.value.pinnedSnapshot)
        assertFalse(vm.uiState.value.isSnapshotPinned)
        assertFalse(vm.uiState.value.showSnapshotOnMap)
        assertEquals(1, vm.uiState.value.savedLayers.size)
        assertFalse(vm.uiState.value.savedLayers.single().isEnabled)
        assertFalse(vm.uiState.value.hasAnyVisibleMapOverlay)

        // Give repository persistence coroutine a moment and verify disabled state persisted on disk
        kotlinx.coroutines.delay(150L)
        val reloaded = ViewshedLayerRepository.loadAllLayers(context)
        assertEquals(1, reloaded.size)
        assertFalse(reloaded.single().isEnabled)
    }

    @Test
    fun `bridge viewmodel supports auto alternating placement clear overlay and full pin reset`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val vm = com.example.ui.BridgeViewModel(context)

        // Default starts in AUTO mode targeting TX first
        assertEquals(com.example.ui.BridgePlacementMode.AUTO, vm.uiState.value.placementMode)
        assertEquals(com.example.ui.BridgePlacementTarget.TX, vm.uiState.value.effectivePlacementTarget)
        assertTrue(vm.uiState.value.areBothEndpointsSet)

        // Auto-alternate: placing TX advances target to RX, placing RX advances back to TX
        vm.setCoordinateForActiveTarget(42.1000, -80.1000)
        assertEquals(42.1000, vm.uiState.value.txLat!!, 1e-5)
        assertEquals(com.example.ui.BridgePlacementTarget.RX, vm.uiState.value.effectivePlacementTarget)

        vm.setCoordinateForActiveTarget(42.2000, -80.0000)
        assertEquals(42.2000, vm.uiState.value.rxLat!!, 1e-5)
        assertEquals(com.example.ui.BridgePlacementTarget.TX, vm.uiState.value.effectivePlacementTarget)

        // Locking RX keeps placement target on RX across multiple taps
        vm.setPlacementMode(com.example.ui.BridgePlacementMode.RX)
        assertEquals(com.example.ui.BridgePlacementTarget.RX, vm.uiState.value.effectivePlacementTarget)
        vm.setCoordinateForActiveTarget(42.2200, -79.9800)
        assertEquals(42.2200, vm.uiState.value.rxLat!!, 1e-5)
        assertEquals(com.example.ui.BridgePlacementTarget.RX, vm.uiState.value.effectivePlacementTarget)

        // Inject a dummy BridgeResult to verify Clear Overlay vs Reset Pins
        val dummyBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val dummyResult = com.example.data.BridgeResult(
            params = BridgeParams(
                txLat = 42.1000,
                txLon = -80.1000,
                txLabel = "TX Pin",
                rxLat = 42.2200,
                rxLon = -79.9800,
                rxLabel = "RX Pin",
                txAntennaHeightMeters = 10.0,
                rxAntennaHeightMeters = 10.0,
                relayAntennaHeightMeters = 15.0,
                radiusKm = 25
            ),
            txGroundElevationMeters = 200.0,
            rxGroundElevationMeters = 210.0,
            directDistanceKm = 15.0,
            directBaseline = com.example.data.BridgeHopEvaluation(
                distanceKm = 15.0,
                status = ViewshedPointStatus.OBSTRUCTED,
                predictedRssiDbm = -85.0,
                linkMarginDb = 45.0,
                worstClearanceMeters = -12.0,
                minFresnelRatio = -0.5
            ),
            bitmap = dummyBitmap,
            northLat = 42.3,
            southLat = 42.0,
            westLon = -80.2,
            eastLon = -79.9,
            txCoveredAreaKm2 = 80.0,
            rxCoveredAreaKm2 = 90.0,
            mutualOverlapAreaKm2 = 20.0,
            candidatesEvaluated = 12,
            topCandidates = emptyList(),
            tileCountUsed = 2,
            computeTimeMs = 5L
        )
        val uiStateField = com.example.ui.BridgeViewModel::class.java.getDeclaredField("_uiState")
        uiStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = uiStateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.ui.BridgeUiState>
        stateFlow.value = stateFlow.value.copy(currentResult = dummyResult, selectedCandidateIndex = 1)

        // 1. Clear Overlay keeps TX & RX pins intact
        vm.clearBridgeOverlay()
        assertNull(vm.uiState.value.currentResult)
        assertEquals(0, vm.uiState.value.selectedCandidateIndex)
        assertTrue(vm.uiState.value.areBothEndpointsSet)
        assertEquals(42.1000, vm.uiState.value.txLat!!, 1e-5)
        assertEquals(42.2200, vm.uiState.value.rxLat!!, 1e-5)

        // 2. Reset Pins clears overlay AND unsets both TX & RX pins, and blocks syncDefaults override
        stateFlow.value = stateFlow.value.copy(currentResult = dummyResult)
        vm.resetBridgeAll()
        assertNull(vm.uiState.value.currentResult)
        assertNull(vm.uiState.value.txLat)
        assertNull(vm.uiState.value.txLon)
        assertNull(vm.uiState.value.rxLat)
        assertNull(vm.uiState.value.rxLon)
        assertFalse(vm.uiState.value.areBothEndpointsSet)
        assertFalse(vm.uiState.value.hasAnyOverlayOrPins)
        assertEquals(com.example.ui.BridgePlacementMode.AUTO, vm.uiState.value.placementMode)
        assertEquals(com.example.ui.BridgePlacementTarget.TX, vm.uiState.value.effectivePlacementTarget)

        val sampleNode = MeshNode(
            id = "!00009999",
            num = 0x9999L,
            longName = "Node Alpha",
            shortName = "ALPH",
            hwModel = "TBEAM",
            role = "ROUTER",
            latitude = 42.15,
            longitude = -80.05,
            altitude = 200,
            hopsAway = 0,
            snr = 8.0,
            rssi = -70,
            viaMqtt = false,
            isFavorite = false,
            isSelf = true,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        vm.syncDefaultsFromNodesIfUnset(sampleNode, listOf(sampleNode))
        assertNull(vm.uiState.value.txLat)
        assertNull(vm.uiState.value.rxLat)
    }

    @Test
    fun `viewshed viewmodel supports tx rx placement modes rx link check and reset pins hiding saved layers`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val layersDir = java.io.File(context.filesDir, "viewshed_layers")
        if (layersDir.exists()) layersDir.deleteRecursively()

        val vm = com.example.ui.ViewshedViewModel(context)

        // Default has TX pre-set and RX unset, so AUTO targets RX first
        assertTrue(vm.uiState.value.hasTxSet)
        assertFalse(vm.uiState.value.hasRxSet)
        assertEquals(com.example.ui.ViewshedPlacementMode.AUTO, vm.uiState.value.placementMode)
        assertEquals(com.example.ui.ViewshedPlacementTarget.RX, vm.uiState.value.effectivePlacementTarget)

        // First tap drops RX and advances AUTO to TX; next drops TX and advances back to RX
        vm.setCoordinateForActiveTarget(42.1600, -80.0851)
        assertTrue(vm.uiState.value.hasRxSet)
        assertEquals(42.1600, vm.uiState.value.rxLat!!, 1e-5)
        assertEquals(com.example.ui.ViewshedPlacementTarget.TX, vm.uiState.value.effectivePlacementTarget)

        vm.setCoordinateForActiveTarget(42.1292, -80.0851)
        assertEquals(42.1292, vm.uiState.value.txLat!!, 1e-5)
        assertEquals(com.example.ui.ViewshedPlacementTarget.RX, vm.uiState.value.effectivePlacementTarget)

        // Evaluate RX link on synthetic flat 200m Terrarium grid
        val zoom = 12
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(42.18, -80.12, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(42.10, -80.05, zoom)
        val flat200mRgb = Color.rgb(128, 200, 0)
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200mRgb }
            }
        }
        val flatGrid = TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
        val grazingLink = vm.evaluateRxLinkOnGrid(vm.uiState.value, flatGrid)
        assertNotNull(grazingLink)
        // Over 3.4km at 906.875 MHz, 60% F1 is ~10.1m, so 10m masts graze the Fresnel zone
        assertEquals(ViewshedPointStatus.GRAZING, grazingLink!!.hop.status)

        vm.updateAntennaHeight(20f)
        vm.updateRxAntennaHeight(20f)
        val rxLink = vm.evaluateRxLinkOnGrid(vm.uiState.value, flatGrid)
        assertNotNull(rxLink)
        assertEquals(ViewshedPointStatus.CLEAR, rxLink!!.hop.status)
        assertTrue(rxLink.hop.distanceKm in 3.0..4.0)
        assertTrue(rxLink.hop.linkMarginDb > 40.0)

        // Swap TX and RX coordinates
        vm.swapTxAndRx()
        assertEquals(42.1600, vm.uiState.value.txLat!!, 1e-5)
        assertEquals(42.1292, vm.uiState.value.rxLat!!, 1e-5)

        // Save a layer and verify Reset Pins (clearAllAndStartFresh) unsets TX & RX and hides saved layers
        val dummyBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val dummyResult = ViewshedResult(
            params = ViewshedParams(
                originLat = 42.1600,
                originLon = -80.0851,
                originLabel = "North Site",
                antennaHeightMeters = 10.0,
                radiusKm = 25
            ),
            groundElevationMeters = 200.0,
            bitmap = dummyBitmap,
            northLat = 42.3,
            southLat = 42.0,
            westLon = -80.2,
            eastLon = -79.9,
            coveredAreaKm2 = 150.0,
            maxUnobstructedRangeKm = 12.0,
            coveredClearNodes = emptyList(),
            tileCountUsed = 4,
            computeTimeMs = 10L
        )
        val savedLayer = ViewshedLayerRepository.saveNewLayer(
            context = context,
            name = "North 10m",
            result = dummyResult,
            tintColorArgb = LAYER_TINT_PALETTE.first(),
            renderMode = LayerRenderMode.TINT,
            existingLayers = emptyList()
        )
        val uiStateField = com.example.ui.ViewshedViewModel::class.java.getDeclaredField("_uiState")
        uiStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = uiStateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.ui.ViewshedUiState>
        stateFlow.value = stateFlow.value.copy(
            currentResult = dummyResult,
            rxLinkResult = rxLink,
            savedLayers = listOf(savedLayer)
        )

        // Clear Current clears overlay + link check while keeping TX & RX pins and enabled saved layer
        vm.clearCurrentViewshed()
        assertNull(vm.uiState.value.currentResult)
        assertNull(vm.uiState.value.rxLinkResult)
        assertTrue(vm.uiState.value.areBothEndpointsSet)
        assertTrue(vm.uiState.value.savedLayers.single().isEnabled)

        // Reset Pins unsets both TX & RX pins, hides all saved layers, and prevents self-node override
        vm.clearAllAndStartFresh()
        assertNull(vm.uiState.value.txLat)
        assertNull(vm.uiState.value.txLon)
        assertNull(vm.uiState.value.rxLat)
        assertNull(vm.uiState.value.rxLon)
        assertFalse(vm.uiState.value.hasTxSet)
        assertFalse(vm.uiState.value.hasRxSet)
        assertFalse(vm.uiState.value.hasAnyOverlayOrPins)
        assertEquals(com.example.ui.ViewshedPlacementMode.AUTO, vm.uiState.value.placementMode)
        assertEquals(com.example.ui.ViewshedPlacementTarget.TX, vm.uiState.value.effectivePlacementTarget)
        assertEquals(1, vm.uiState.value.savedLayers.size)
        assertFalse(vm.uiState.value.savedLayers.single().isEnabled)

        val selfNode = MeshNode(
            id = "!00008888",
            num = 0x8888L,
            longName = "Self Site",
            shortName = "SELF",
            hwModel = "TBEAM",
            role = "ROUTER",
            latitude = 42.15,
            longitude = -80.05,
            altitude = 200,
            hopsAway = 0,
            snr = 9.0,
            rssi = -70,
            viaMqtt = false,
            isFavorite = false,
            isSelf = true,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        vm.syncDefaultSiteFromSelfNodeIfUnset(selfNode)
        assertNull(vm.uiState.value.txLat)
    }

    @Test
    fun `clearLoadedJson wipes disk cache resets map state and allows fresh planner auto sync`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val cachedJson = java.io.File(context.filesDir, "cached_nodedb.json")
        val cachedMeta = java.io.File(context.filesDir, "cached_nodedb_meta.json")
        cachedJson.writeText("""{"nodes":[]}""", Charsets.UTF_8)
        cachedMeta.writeText("""{"fileName":"test.json"}""", Charsets.UTF_8)

        val mapVm = com.example.ui.NodeMapViewModel(context)
        val mapStateField = com.example.ui.NodeMapViewModel::class.java.getDeclaredField("_uiState")
        mapStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val mapStateFlow = mapStateField.get(mapVm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.ui.NodeMapUiState>
        mapStateFlow.value = mapStateFlow.value.copy(
            hasLoadedFile = true,
            loadedFileName = "test.json",
            totalCount = 5,
            plottedGpsCount = 4,
            searchQuery = "erie",
            baseLayer = com.example.ui.MapBaseLayer.OPEN_TOPO
        )

        mapVm.clearLoadedJson()
        kotlinx.coroutines.delay(150L)

        assertFalse(mapVm.uiState.value.hasLoadedFile)
        assertNull(mapVm.uiState.value.loadedFileName)
        assertEquals(0, mapVm.uiState.value.totalCount)
        assertEquals("", mapVm.uiState.value.searchQuery)
        assertEquals(com.example.ui.MapBaseLayer.OPEN_TOPO, mapVm.uiState.value.baseLayer)
        assertFalse(cachedJson.exists())
        assertFalse(cachedMeta.exists())

        val viewshedVm = com.example.ui.ViewshedViewModel(context)
        val bridgeVm = com.example.ui.BridgeViewModel(context)

        viewshedVm.resetForFreshNodeDb()
        bridgeVm.resetForFreshNodeDb()
        assertNull(viewshedVm.uiState.value.txLat)
        assertNull(bridgeVm.uiState.value.txLat)
        assertNull(bridgeVm.uiState.value.rxLat)

        val newSelf = MeshNode(
            id = "!0000aaaa",
            num = 0xAAAAL,
            longName = "New Base",
            shortName = "NEWB",
            hwModel = "TBEAM",
            role = "ROUTER",
            latitude = 41.9999,
            longitude = -80.1234,
            altitude = 210,
            hopsAway = 0,
            snr = 9.0,
            rssi = -70,
            viaMqtt = false,
            isFavorite = false,
            isSelf = true,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        val newRemote = newSelf.copy(
            id = "!0000bbbb",
            num = 0xBBBBL,
            longName = "New Remote",
            shortName = "NEWR",
            latitude = 42.0500,
            longitude = -80.0500,
            isSelf = false
        )

        viewshedVm.syncDefaultSiteFromSelfNodeIfUnset(newSelf)
        bridgeVm.syncDefaultsFromNodesIfUnset(newSelf, listOf(newSelf, newRemote))

        assertEquals(41.9999, viewshedVm.uiState.value.txLat!!, 1e-4)
        assertEquals("!0000aaaa", viewshedVm.uiState.value.sourceNode?.id)
        assertEquals(41.9999, bridgeVm.uiState.value.txLat!!, 1e-4)
        assertEquals(42.0500, bridgeVm.uiState.value.rxLat!!, 1e-4)
    }

    @Test
    fun `map viewmodel toggles top header dismiss and hud minimize independently`() {
        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val mapVm = com.example.ui.NodeMapViewModel(context)

        assertEquals(com.example.ui.MapBaseLayer.DARK_MATTER, mapVm.uiState.value.baseLayer)
        assertFalse(mapVm.uiState.value.isTopHeaderDismissed)
        assertFalse(mapVm.uiState.value.isHudMinimized)

        mapVm.setTopHeaderDismissed(true)
        assertTrue(mapVm.uiState.value.isTopHeaderDismissed)
        assertFalse(mapVm.uiState.value.isHudMinimized)

        mapVm.toggleHudMinimized()
        assertTrue(mapVm.uiState.value.isTopHeaderDismissed)
        assertTrue(mapVm.uiState.value.isHudMinimized)

        mapVm.setTopHeaderDismissed(false)
        assertFalse(mapVm.uiState.value.isTopHeaderDismissed)
        assertTrue(mapVm.uiState.value.isHudMinimized)

        mapVm.toggleHudMinimized()
        assertFalse(mapVm.uiState.value.isHudMinimized)
    }

    @Test
    fun `viewshed and bridge viewmodels start with top bar expanded and toggle cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val viewshedVm = com.example.ui.ViewshedViewModel(context)
        val bridgeVm = com.example.ui.BridgeViewModel(context)

        assertTrue(viewshedVm.uiState.value.isTopBarExpanded)
        assertTrue(bridgeVm.uiState.value.isTopBarExpanded)

        viewshedVm.toggleTopBarExpanded()
        bridgeVm.toggleTopBarExpanded()
        assertFalse(viewshedVm.uiState.value.isTopBarExpanded)
        assertFalse(bridgeVm.uiState.value.isTopBarExpanded)

        viewshedVm.toggleTopBarExpanded()
        bridgeVm.toggleTopBarExpanded()
        assertTrue(viewshedVm.uiState.value.isTopBarExpanded)
        assertTrue(bridgeVm.uiState.value.isTopBarExpanded)
    }

    @Test
    fun `viewshed knife edge diffraction and color gradient span green yellow orange and red`() {
        // 1. ITU knife-edge diffraction: 0 dB at >= 0.60 F1, ~6 dB at 0.0 F1 grazing, > 12 dB in shadow
        assertEquals(0.0, ViewshedEngine.knifeEdgeDiffractionLossDb(0.65), 1e-4)
        assertEquals(0.0, ViewshedEngine.knifeEdgeDiffractionLossDb(0.60), 1e-4)
        val grazingLoss = ViewshedEngine.knifeEdgeDiffractionLossDb(0.0)
        assertTrue("Expected ~6.0 dB at grazing, got $grazingLoss", grazingLoss in 5.8..6.3)
        val shadowLoss = ViewshedEngine.knifeEdgeDiffractionLossDb(-0.8)
        assertTrue("Expected > 12 dB in shadow, got $shadowLoss", shadowLoss > 12.0)

        // 2. Calibrated color scale produces distinct Green, Yellow, Orange, and Red hues
        val strongGreen = ViewshedEngine.colorForRssi(-55.0, sensitivityDbm = -130.0, isGrazing = false)
        val midYellow = ViewshedEngine.colorForRssi(-89.4, sensitivityDbm = -130.0, isGrazing = false)
        val weakOrange = ViewshedEngine.colorForRssi(-110.4, sensitivityDbm = -130.0, isGrazing = true)
        val fringeRed = ViewshedEngine.colorForRssi(-130.0, sensitivityDbm = -130.0, isGrazing = true)

        assertEquals(225, Color.alpha(strongGreen))
        assertEquals(165, Color.alpha(fringeRed))

        // Strong: green channel dominant (#22C55E)
        assertTrue(Color.green(strongGreen) > Color.red(strongGreen) * 2)
        // Yellow: high red and high green, low blue (#FACC15)
        assertTrue(Color.red(midYellow) > 220 && Color.green(midYellow) > 180 && Color.blue(midYellow) < 60)
        // Orange: high red, moderate green (#F97316)
        assertTrue(Color.red(weakOrange) > 220 && Color.green(weakOrange) in 90..140)
        // Red: high red, low green (#EF4444)
        assertTrue(Color.red(fringeRed) > 220 && Color.green(fringeRed) < 90)
    }

    @Test
    fun `50km viewshed and bridge use adaptive dem zoom step scaling and render out to full radius`() {
        // 1. Adaptive step stays 100m <= 25km and scales smoothly for 40..50km
        assertEquals(100.0, ViewshedEngine.adaptiveStepMeters(10.0), 1e-4)
        assertEquals(100.0, ViewshedEngine.adaptiveStepMeters(25.0), 1e-4)
        val step50 = ViewshedEngine.adaptiveStepMeters(50.0)
        assertTrue("Expected adaptive step in 150..220m at 50km, got $step50", step50 in 150.0..220.0)

        // 2. Bounds-aware DEM zoom keeps tile count <= 56 even at 50km high-latitude and wide Bridge spans
        val highLatBounds = TerrariumDemRepository.paddedBoundsForRadius(60.0, -149.0, 50.0)
        val highLatZoom = TerrariumDemRepository.recommendedZoomForBounds(
            northLat = highLatBounds[0],
            southLat = highLatBounds[1],
            westLon = highLatBounds[2],
            eastLon = highLatBounds[3],
            preferredRadiusKm = 50
        )
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(highLatBounds[0], highLatBounds[2], highLatZoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(highLatBounds[1], highLatBounds[3], highLatZoom)
        val highLatTiles = (maxTx - minTx + 1) * (maxTy - minTy + 1)
        assertTrue("Expected <= 56 tiles at 60N 50km, got $highLatTiles at z$highLatZoom", highLatTiles <= 56)

        // 3. 50km Viewshed from elevated site renders clear coverage past 45km and outer raster pixels are non-transparent
        val originLat = 42.1292
        val originLon = -80.0851
        val vBounds = TerrariumDemRepository.paddedBoundsForRadius(originLat, originLon, 50.0)
        val vZoom = TerrariumDemRepository.recommendedZoomForBounds(
            northLat = vBounds[0],
            southLat = vBounds[1],
            westLon = vBounds[2],
            eastLon = vBounds[3],
            preferredRadiusKm = 50
        )
        val (vMinTx, vMinTy) = TerrariumDemRepository.latLonToTile(vBounds[0], vBounds[2], vZoom)
        val (vMaxTx, vMaxTy) = TerrariumDemRepository.latLonToTile(vBounds[1], vBounds[3], vZoom)
        val flat200mRgb = Color.rgb(128, 200, 0)
        val vTiles = mutableMapOf<Long, IntArray>()
        for (ty in vMinTy..vMaxTy) {
            for (tx in vMinTx..vMaxTx) {
                vTiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200mRgb }
            }
        }
        val vGrid = TerrariumTileGrid(zoom = vZoom, tiles = vTiles, tileCount = vTiles.size)

        val remoteNode = MeshNode(
            id = "!00004500",
            num = 0x4500L,
            longName = "Far Ridge Node",
            shortName = "FAR4",
            hwModel = "TBEAM",
            role = "ROUTER",
            latitude = 42.5340, // ~45 km north
            longitude = -80.0851,
            altitude = 200,
            hopsAway = 0,
            snr = 4.0,
            rssi = -92,
            viaMqtt = false,
            isFavorite = false,
            isSelf = false,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )

        val vs50 = ViewshedEngine.computeViewshed(
            params = ViewshedParams(
                originLat = originLat,
                originLon = originLon,
                originLabel = "Mountain Lookout",
                antennaHeightMeters = 250.0,
                radiusKm = 50
            ),
            grid = vGrid,
            existingNodes = listOf(remoteNode)
        )
        assertTrue(
            "Expected maxUnobstructedRangeKm > 45 km at 50km radius, got ${vs50.maxUnobstructedRangeKm}",
            vs50.maxUnobstructedRangeKm > 45.0
        )
        assertTrue(
            "Expected coveredAreaKm2 > 6000 km^2 at 50km radius, got ${vs50.coveredAreaKm2}",
            vs50.coveredAreaKm2 > 6000.0
        )
        assertEquals(1, vs50.coveredClearNodes.size)
        // Outer ring pixel (~45km north of center at py ~= 26) should be rendered, not transparent
        val outerNorthPixel = vs50.bitmap.getPixel(ViewshedEngine.RASTER_SIZE / 2, 26)
        assertTrue("Expected non-transparent pixel at ~45km north", Color.alpha(outerNorthPixel) > 0)

        // 4. 50km Bridge analysis across 45 km TX-RX separation succeeds with mutual overlap
        val bridgeParams = BridgeParams(
            txLat = 42.0000,
            txLon = -80.1000,
            txLabel = "TX South",
            rxLat = 42.4050, // ~45 km north
            rxLon = -80.1000,
            rxLabel = "RX North",
            txAntennaHeightMeters = 180.0,
            rxAntennaHeightMeters = 180.0,
            relayAntennaHeightMeters = 30.0,
            radiusKm = 50
        )
        val bBounds = RelayBridgeEngine.computeBridgeBounds(bridgeParams)
        val bZoom = TerrariumDemRepository.recommendedZoomForBounds(
            northLat = bBounds[0],
            southLat = bBounds[1],
            westLon = bBounds[2],
            eastLon = bBounds[3],
            preferredRadiusKm = bridgeParams.radiusKm
        )
        val (bMinTx, bMinTy) = TerrariumDemRepository.latLonToTile(bBounds[0], bBounds[2], bZoom)
        val (bMaxTx, bMaxTy) = TerrariumDemRepository.latLonToTile(bBounds[1], bBounds[3], bZoom)
        val bTilesCount = (bMaxTx - bMinTx + 1) * (bMaxTy - bMinTy + 1)
        assertTrue("Expected <= 56 bridge tiles, got $bTilesCount at z$bZoom", bTilesCount <= 56)

        val bTiles = mutableMapOf<Long, IntArray>()
        for (ty in bMinTy..bMaxTy) {
            for (tx in bMinTx..bMaxTx) {
                bTiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200mRgb }
            }
        }
        val bGrid = TerrariumTileGrid(zoom = bZoom, tiles = bTiles, tileCount = bTiles.size)
        val bridgeResult = RelayBridgeEngine.computeBridgeAnalysis(bridgeParams, bGrid)
        assertTrue("Expected mutual overlap > 1000 km^2, got ${bridgeResult.mutualOverlapAreaKm2}", bridgeResult.mutualOverlapAreaKm2 > 1000.0)
        assertTrue("Expected top relay candidates", bridgeResult.topCandidates.isNotEmpty())

        // 5. ViewModels clamp radius inputs to 50km max
        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val viewshedVm = com.example.ui.ViewshedViewModel(context)
        val bridgeVm = com.example.ui.BridgeViewModel(context)
        viewshedVm.updateRadiusKm(75)
        bridgeVm.updateRadiusKm(75)
        assertEquals(50, viewshedVm.uiState.value.radiusKm)
        assertEquals(50, bridgeVm.uiState.value.radiusKm)
    }

    @Test
    fun `bridge viewmodel imports tx rx nodes elevations and rf config from viewshed only when both set`() {
        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val viewshedVm = com.example.ui.ViewshedViewModel(context)
        val bridgeVm = com.example.ui.BridgeViewModel(context)

        val txNode = MeshNode(
            id = "!00001001",
            num = 0x1001L,
            longName = "Summit Repeater",
            shortName = "SUMM",
            hwModel = "TBEAM",
            role = "ROUTER",
            latitude = 42.1100,
            longitude = -80.1200,
            altitude = 320,
            hopsAway = 0,
            snr = 10.0,
            rssi = -65,
            viaMqtt = false,
            isFavorite = true,
            isSelf = true,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        val rxNode = txNode.copy(
            id = "!00002002",
            num = 0x2002L,
            longName = "Harbor Base",
            shortName = "HARB",
            latitude = 42.2600,
            longitude = -79.9400,
            isSelf = false
        )

        // 1. Refuse carry-over if RX is unset on Viewshed
        viewshedVm.selectExistingNode(txNode)
        viewshedVm.updateAntennaHeight(32f)
        viewshedVm.updateRadiusKm(40)
        assertFalse(viewshedVm.uiState.value.areBothEndpointsSet)
        val rejected = bridgeVm.importFromViewshed(viewshedVm.uiState.value, autoSolve = false)
        assertFalse(rejected)
        // Bridge retains its prior TX coordinate instead of partially overwriting
        assertEquals(42.1292, bridgeVm.uiState.value.txLat!!, 1e-4)

        // 2. Customize Bridge relay height and inject a stale result + error to ensure import preserves relay height and clears stale state
        bridgeVm.updateRelayAntennaHeight(24f)
        val bridgeStateField = com.example.ui.BridgeViewModel::class.java.getDeclaredField("_uiState")
        bridgeStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val bridgeFlow = bridgeStateField.get(bridgeVm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.ui.BridgeUiState>
        bridgeFlow.value = bridgeFlow.value.copy(
            errorMessage = "Stale error",
            selectedCandidateIndex = 2
        )

        // 3. Configure full Viewshed TX, RX, elevations, and RF parameters
        viewshedVm.selectRxNode(rxNode)
        viewshedVm.updateRxAntennaHeight(18f)
        viewshedVm.updateFrequencyText("868.125")
        viewshedVm.updateTxPowerDbm(22f)
        viewshedVm.updateAntennaGainDbi(8.5f)
        viewshedVm.updateSensitivityDbm(-134f)
        viewshedVm.updateOverlayOpacity(0.55f)

        val viewshedField = com.example.ui.ViewshedViewModel::class.java.getDeclaredField("_uiState")
        viewshedField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val viewshedFlow = viewshedField.get(viewshedVm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.ui.ViewshedUiState>
        viewshedFlow.value = viewshedFlow.value.copy(
            groundElevationMeters = 318.5,
            rxGroundElevationMeters = 185.0
        )

        val accepted = bridgeVm.importFromViewshed(viewshedVm.uiState.value, autoSolve = false)
        assertTrue(accepted)

        val imported = bridgeVm.uiState.value
        assertEquals(42.1100, imported.txLat!!, 1e-5)
        assertEquals(-80.1200, imported.txLon!!, 1e-5)
        assertEquals("!00001001", imported.txNode?.id)
        assertEquals("Summit Repeater (SUMM)", imported.txLabel)
        assertEquals(318.5, imported.txGroundElevationMeters!!, 1e-3)

        assertEquals(42.2600, imported.rxLat!!, 1e-5)
        assertEquals(-79.9400, imported.rxLon!!, 1e-5)
        assertEquals("!00002002", imported.rxNode?.id)
        assertEquals("HARB (Harbor Base)", imported.rxLabel)
        assertEquals(185.0, imported.rxGroundElevationMeters!!, 1e-3)

        assertEquals(32f, imported.txAntennaHeightMeters, 1e-4f)
        assertEquals(18f, imported.rxAntennaHeightMeters, 1e-4f)
        assertEquals(24f, imported.relayAntennaHeightMeters, 1e-4f)
        assertEquals(40, imported.radiusKm)
        assertEquals("868.125", imported.frequencyText)
        assertEquals(22f, imported.txPowerDbm, 1e-4f)
        assertEquals(8.5f, imported.antennaGainDbi, 1e-4f)
        assertEquals(-134f, imported.sensitivityDbm, 1e-4f)
        assertEquals(0.55f, imported.overlayOpacity, 1e-4f)
        assertEquals(0, imported.selectedCandidateIndex)
        assertNull(imported.errorMessage)
        assertNull(imported.currentResult)
        assertTrue(imported.hasUserPositionedTx)
        assertTrue(imported.hasUserPositionedRx)

        // Ensure background node sync will not clobber imported TX/RX
        val otherNode = txNode.copy(id = "!00009999", num = 0x9999L, latitude = 41.5, longitude = -79.5)
        bridgeVm.syncDefaultsFromNodesIfUnset(otherNode, listOf(otherNode))
        assertEquals(42.1100, bridgeVm.uiState.value.txLat!!, 1e-5)
        assertEquals(42.2600, bridgeVm.uiState.value.rxLat!!, 1e-5)

        // 4. Auto-solve on endpoints closer than 250m surfaces validation message immediately after import
        viewshedVm.setCustomSiteCoordinate(42.1292, -80.0851)
        viewshedVm.setRxCustomCoordinate(42.1298, -80.0851) // ~67 m apart
        val acceptedShort = bridgeVm.importFromViewshed(viewshedVm.uiState.value, autoSolve = true)
        assertTrue(acceptedShort)
        assertFalse(bridgeVm.uiState.value.isCalculating)
        assertNotNull(bridgeVm.uiState.value.errorMessage)
        assertTrue(bridgeVm.uiState.value.errorMessage!!.contains("250 m"))
    }

    @Test
    fun `bridge hop evaluation extracts worst obstruction coordinates elevations and declusters multi ridge peaks`() {
        val latA = 42.1000
        val lonA = -80.1000
        val latB = 42.2800 // ~20.0 km north
        val lonB = -80.1000

        val zoom = 12
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(42.30, -80.15, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(42.08, -80.05, zoom)
        val flat200mRgb = Color.rgb(128, 200, 0) // 200 m
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200mRgb }
            }
        }

        fun paintRidge(ridgeLat: Double, elevMeters: Int) {
            val n = (1 shl zoom).toDouble()
            val latRad = Math.toRadians(ridgeLat)
            val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
            val worldX = ((lonA + 180.0) / 360.0) * n
            val ty = floor(worldY).toInt()
            val tx = floor(worldX).toInt()
            val py = ((worldY - ty) * 256.0).toInt().coerceIn(3, 252)
            val raw = elevMeters + 32768
            val rgb = Color.rgb(raw shr 8, raw and 0xFF, 0)
            val arr = tiles[TerrariumTileGrid.tileKey(tx, ty)] ?: return
            for (y in (py - 2)..(py + 2)) {
                for (px in 0 until 256) {
                    arr[y * 256 + px] = rgb
                }
            }
        }

        // Paint two distinct ridges along the 20km path:
        // Ridge 1 (~6 km north, 42.154): 310m (moderate obstruction)
        // Ridge 2 (~13 km north, 42.217): 420m (worst obstruction)
        paintRidge(42.154, 310)
        paintRidge(42.217, 420)

        val grid = TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
        val hop = RelayBridgeEngine.evaluateHop(
            latA = latA,
            lonA = lonA,
            heightAMeters = 10.0,
            latB = latB,
            lonB = lonB,
            heightBMeters = 10.0,
            freqMhz = 906.875,
            freqGhz = 0.906875,
            eirpDbm = 35.8,
            sensitivityDbm = -130.0,
            grid = grid,
            collectObstructionPeaks = true
        )

        assertEquals(ViewshedPointStatus.OBSTRUCTED, hop.status)
        assertNotNull(hop.worstObstruction)
        assertTrue("Expected at least 2 distinct ridge peaks, got ${hop.obstructions.size}", hop.obstructions.size >= 2)

        val worst = hop.obstructions.first()
        assertEquals(hop.worstObstruction!!.latitude, worst.latitude, 1e-6)
        assertEquals(hop.worstObstruction!!.longitude, worst.longitude, 1e-6)
        // Worst peak must correspond to the 420m ridge near 42.217
        assertEquals(42.217, worst.latitude, 0.015)
        assertTrue("Expected terrain elevation >= 400m, got ${worst.terrainElevationMeters}", worst.terrainElevationMeters >= 400.0)
        assertTrue("Expected effective terrain > raw terrain due to bulge", worst.effectiveTerrainMeters > worst.terrainElevationMeters)
        assertTrue("Expected negative clearance at blocked ridge", worst.clearanceMeters < -150.0)
        assertTrue("Expected distance from origin ~12..15 km, got ${worst.distanceFromOriginKm}", worst.distanceFromOriginKm in 11.0..15.5)

        // Second peak must be separated from the first and correspond to the ~310m ridge near 42.154
        val second = hop.obstructions[1]
        assertTrue(kotlin.math.abs(second.distanceFromOriginKm - worst.distanceFromOriginKm) >= 1.0)
        assertEquals(42.154, second.latitude, 0.02)
        assertTrue(second.terrainElevationMeters >= 290.0)

        // Full Bridge analysis populates obstructions on direct baseline and top candidates,
        // and BridgeViewModel toggles Obstructions tab & obstruction highlight cleanly.
        val bounds = RelayBridgeEngine.computeBridgeBounds(
            BridgeParams(
                txLat = latA,
                txLon = lonA,
                txLabel = "TX",
                rxLat = latB,
                rxLon = lonB,
                rxLabel = "RX",
                radiusKm = 15
            )
        )
        val bZoom = TerrariumDemRepository.recommendedZoomForBounds(bounds[0], bounds[1], bounds[2], bounds[3], 15)
        val (bMinTx, bMinTy) = TerrariumDemRepository.latLonToTile(bounds[0], bounds[2], bZoom)
        val (bMaxTx, bMaxTy) = TerrariumDemRepository.latLonToTile(bounds[1], bounds[3], bZoom)
        val bTiles = mutableMapOf<Long, IntArray>()
        for (ty in bMinTy..bMaxTy) {
            for (tx in bMinTx..bMaxTx) {
                bTiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200mRgb }
            }
        }
        val bGrid = TerrariumTileGrid(zoom = bZoom, tiles = bTiles, tileCount = bTiles.size)
        val flatBridge = RelayBridgeEngine.computeBridgeAnalysis(
            BridgeParams(
                txLat = latA,
                txLon = lonA,
                txLabel = "TX",
                rxLat = latB,
                rxLon = lonB,
                rxLabel = "RX",
                radiusKm = 15
            ),
            bGrid
        )
        val obsList = RelayBridgeEngine.buildObstructionList(flatBridge, 0)
        assertTrue("Expected direct + relay hop pinch items", obsList.isNotEmpty())
        assertTrue(obsList.any { it.segment == com.example.data.BridgeLinkSegment.DIRECT })

        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val bridgeVm = com.example.ui.BridgeViewModel(context)
        assertEquals(com.example.ui.BridgeDrawerTab.RESULTS, bridgeVm.uiState.value.activeDrawerTab)
        bridgeVm.selectDrawerTab(com.example.ui.BridgeDrawerTab.OBSTRUCTIONS)
        assertEquals(com.example.ui.BridgeDrawerTab.OBSTRUCTIONS, bridgeVm.uiState.value.activeDrawerTab)

        val firstObsId = obsList.first().id
        bridgeVm.selectObstruction(firstObsId)
        assertEquals(firstObsId, bridgeVm.uiState.value.selectedObstructionId)
        assertEquals(1L, bridgeVm.uiState.value.obstructionFocusNonce)

        // Switching candidate clears stale obstruction highlight
        bridgeVm.selectCandidateIndex(1)
        assertNull(bridgeVm.uiState.value.selectedObstructionId)
    }

    @Test
    fun `adjusting antenna height sliders resamples cached grid and updates worst obstruction coordinates live`() = runBlocking {
        val txLat = 42.1000
        val txLon = -80.1000
        val rxLat = 42.2800 // ~20 km north
        val rxLon = -80.1000

        val params = BridgeParams(
            txLat = txLat,
            txLon = txLon,
            txLabel = "TX",
            rxLat = rxLat,
            rxLon = rxLon,
            rxLabel = "RX",
            txAntennaHeightMeters = 10.0,
            rxAntennaHeightMeters = 10.0,
            relayAntennaHeightMeters = 15.0,
            radiusKm = 15
        )
        val bounds = RelayBridgeEngine.computeBridgeBounds(params)
        val zoom = TerrariumDemRepository.recommendedZoomForBounds(bounds[0], bounds[1], bounds[2], bounds[3], 15)
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(bounds[0], bounds[2], zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(bounds[1], bounds[3], zoom)
        val flat200mRgb = Color.rgb(128, 200, 0) // 200 m
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200mRgb }
            }
        }

        fun paintRidge(ridgeLat: Double, elevMeters: Int) {
            val n = (1 shl zoom).toDouble()
            val latRad = Math.toRadians(ridgeLat)
            val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
            val worldX = ((txLon + 180.0) / 360.0) * n
            val ty = floor(worldY).toInt()
            val tx = floor(worldX).toInt()
            val py = ((worldY - ty) * 256.0).toInt().coerceIn(3, 252)
            val raw = elevMeters + 32768
            val rgb = Color.rgb(raw shr 8, raw and 0xFF, 0)
            val arr = tiles[TerrariumTileGrid.tileKey(tx, ty)] ?: return
            for (y in (py - 6)..(py + 6)) {
                for (px in 0 until 256) {
                    arr[y * 256 + px] = rgb
                }
            }
        }

        // Ridge A near TX (~3 km north, 42.127): 238m (worst at 10m TX mast, +0.40 F1 at 60m TX mast)
        // Ridge B near RX (~16 km north, 42.244): 234m (less severe at 10m TX, worst obstruction at 60m TX mast)
        paintRidge(42.127, 238)
        paintRidge(42.244, 234)

        val grid = TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
        val initialResult = RelayBridgeEngine.computeBridgeAnalysis(params, grid)
        val initialWorstDirect = initialResult.directBaseline.worstObstruction!!
        assertEquals(42.127, initialWorstDirect.latitude, 0.015)

        // Direct engine fast resample with raised TX mast shifts worst obstruction to Ridge B
        val raisedTxParams = params.copy(txAntennaHeightMeters = 60.0)
        val resampledDirect = RelayBridgeEngine.recalculateHopsOnGrid(initialResult, raisedTxParams, grid)
        assertEquals(initialResult.topCandidates.map { it.rank }, resampledDirect.topCandidates.map { it.rank })
        assertEquals(initialResult.topCandidates.map { it.latitude }, resampledDirect.topCandidates.map { it.latitude })
        val shiftedWorstDirect = resampledDirect.directBaseline.worstObstruction!!
        assertEquals(42.244, shiftedWorstDirect.latitude, 0.015)

        // BridgeViewModel live slider updates trigger automatic hop re-sampling against cached grid
        val context = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val bridgeVm = com.example.ui.BridgeViewModel(context)
        val stateField = com.example.ui.BridgeViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(bridgeVm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.ui.BridgeUiState>
        stateFlow.value = stateFlow.value.copy(
            txLat = txLat,
            txLon = txLon,
            rxLat = rxLat,
            rxLon = rxLon,
            txAntennaHeightMeters = 10f,
            rxAntennaHeightMeters = 10f,
            relayAntennaHeightMeters = 15f,
            currentResult = initialResult,
            activeDrawerTab = com.example.ui.BridgeDrawerTab.OBSTRUCTIONS,
            selectedObstructionId = "${com.example.data.BridgeLinkSegment.DIRECT.name}_1"
        )
        bridgeVm.setCachedGridForTest(grid)

        // Move TX Mast slider to 60m and flush main looper after background work completes
        bridgeVm.updateTxAntennaHeight(60f)
        kotlinx.coroutines.delay(150L)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val liveResult = bridgeVm.uiState.value.currentResult!!
        assertEquals(60.0, liveResult.params.txAntennaHeightMeters, 1e-4)
        assertEquals(42.244, liveResult.directBaseline.worstObstruction!!.latitude, 0.015)
        assertEquals("${com.example.data.BridgeLinkSegment.DIRECT.name}_1", bridgeVm.uiState.value.selectedObstructionId)

        // Move Relay Mast slider to 60m and verify relay params & hops re-sample live
        bridgeVm.updateRelayAntennaHeight(60f)
        kotlinx.coroutines.delay(150L)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(60.0, bridgeVm.uiState.value.currentResult!!.params.relayAntennaHeightMeters, 1e-4)
    }

    @Test
    fun `map export generates valid RFC 7946 GeoJSON across node map viewshed and bridge tabs`() {
        val selfNode = MeshNode(
            num = 100L,
            id = "!00000064",
            longName = "Erie Base",
            shortName = "BASE",
            hwModel = "TBEAM",
            role = "ROUTER",
            latitude = 42.1292,
            longitude = -80.0851,
            altitude = 220,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = false,
            isSelf = true,
            snr = null,
            rssi = null,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        val peerNode = selfNode.copy(
            num = 101L,
            id = "!00000065",
            longName = "Harbor Peak",
            shortName = "HRBR",
            latitude = 42.1850,
            longitude = -79.9950,
            altitude = 245,
            isSelf = false,
            snr = 7.5,
            rssi = -78
        )

        val ptA = LosEndpoint(42.1292, -80.0851, "BASE", "BASE", selfNode)
        val ptB = LosEndpoint(42.1850, -79.9950, "HRBR", "HRBR", peerNode)
        val coords = LosAnalyzer.sampleGreatCirclePath(ptA, ptB, 40)
        val losRes = LosAnalyzer.analyze(
            pointA = ptA,
            pointB = ptB,
            coordinates = coords,
            elevationsMeters = List(40) { 210.0 },
            txHeightMeters = 10.0,
            rxHeightMeters = 10.0,
            frequencyMhz = 906.875
        )

        val nodeMapState = com.example.ui.NodeMapUiState(
            hasLoadedFile = true,
            loadedFileName = "erie_test.json",
            allNodes = listOf(selfNode, peerNode),
            selfNode = selfNode,
            totalCount = 2,
            plottedGpsCount = 2,
            rfCount = 2,
            measureAnchorA = selfNode,
            measureAnchorB = peerNode,
            losPointA = ptA,
            losPointB = ptB,
            losResult = losRes
        )

        val nodeMapGeoJson = org.json.JSONObject(
            com.example.data.MapExportEngine.buildNodeMapGeoJson(nodeMapState)
        )
        assertEquals("FeatureCollection", nodeMapGeoJson.getString("type"))
        val nodeFeatures = nodeMapGeoJson.getJSONArray("features")
        val nodeTypes = (0 until nodeFeatures.length()).map {
            nodeFeatures.getJSONObject(it).getJSONObject("properties").getString("featureType")
        }
        assertTrue(nodeTypes.contains("mesh_node"))
        assertTrue(nodeTypes.contains("rf_sightline"))
        assertTrue(nodeTypes.contains("rf_measure_line"))
        assertTrue(nodeTypes.contains("los_path"))
        assertTrue(nodeTypes.contains("los_worst_obstruction"))
        // Verify RFC 7946 [lon, lat] order on first node
        val firstCoords = nodeFeatures.getJSONObject(0)
            .getJSONObject("geometry")
            .getJSONArray("coordinates")
        assertEquals(-80.0851, firstCoords.getDouble(0), 1e-4)
        assertEquals(42.1292, firstCoords.getDouble(1), 1e-4)

        // Flat synthetic DEM grid for Viewshed + Bridge export checks
        val bounds = RelayBridgeEngine.computeBridgeBounds(
            BridgeParams(
                txLat = 42.1292,
                txLon = -80.0851,
                txLabel = "TX",
                rxLat = 42.1850,
                rxLon = -79.9950,
                rxLabel = "RX",
                radiusKm = 12
            )
        )
        val zoom = TerrariumDemRepository.recommendedZoomForBounds(bounds[0], bounds[1], bounds[2], bounds[3], 12)
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(bounds[0], bounds[2], zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(bounds[1], bounds[3], zoom)
        val flatRgb = Color.rgb(128, 210, 0) // 210 m
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flatRgb }
            }
        }
        val grid = TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)

        val viewshedRes = ViewshedEngine.computeViewshed(
            ViewshedParams(42.1292, -80.0851, "TX Base", selfNode, 15.0, 12),
            grid,
            listOf(selfNode, peerNode)
        )
        val viewshedState = com.example.ui.ViewshedUiState(
            txLat = 42.1292,
            txLon = -80.0851,
            siteLabel = "TX Base",
            rxLat = 42.1850,
            rxLon = -79.9950,
            rxLabel = "RX Site",
            radiusKm = 12,
            currentResult = viewshedRes
        )
        val viewshedGeoJson = org.json.JSONObject(
            com.example.data.MapExportEngine.buildViewshedGeoJson(viewshedState)
        )
        val vsFeatures = viewshedGeoJson.getJSONArray("features")
        val vsTypes = (0 until vsFeatures.length()).map {
            vsFeatures.getJSONObject(it).getJSONObject("properties").getString("featureType")
        }
        assertTrue(vsTypes.contains("tx_site"))
        assertTrue(vsTypes.contains("rx_site"))
        assertTrue(vsTypes.contains("viewshed_coverage_ring"))
        // Coverage ring polygon should be closed (first coord == last coord)
        val ringFeature = (0 until vsFeatures.length())
            .map { vsFeatures.getJSONObject(it) }
            .first { it.getJSONObject("properties").getString("featureType") == "viewshed_coverage_ring" }
        val ringCoords = ringFeature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        assertTrue(ringCoords.length() >= 65)
        assertEquals(ringCoords.getJSONArray(0).getDouble(0), ringCoords.getJSONArray(ringCoords.length() - 1).getDouble(0), 1e-6)

        val bridgeRes = RelayBridgeEngine.computeBridgeAnalysis(
            BridgeParams(42.1292, -80.0851, "TX Base", selfNode, 42.1850, -79.9950, "RX Site", peerNode, radiusKm = 12),
            grid
        )
        val bridgeState = com.example.ui.BridgeUiState(
            txLat = 42.1292,
            txLon = -80.0851,
            txLabel = "TX Base",
            rxLat = 42.1850,
            rxLon = -79.9950,
            rxLabel = "RX Site",
            radiusKm = 12,
            currentResult = bridgeRes
        )
        val bridgeGeoJson = org.json.JSONObject(
            com.example.data.MapExportEngine.buildBridgeGeoJson(bridgeState)
        )
        val brFeatures = bridgeGeoJson.getJSONArray("features")
        val brTypes = (0 until brFeatures.length()).map {
            brFeatures.getJSONObject(it).getJSONObject("properties").getString("featureType")
        }
        assertTrue(brTypes.contains("tx_site"))
        assertTrue(brTypes.contains("rx_site"))
        assertTrue(brTypes.contains("bridge_direct_baseline"))
        assertTrue(brTypes.contains("relay_candidate"))
        assertTrue(brTypes.contains("bridge_hop_tx_to_relay"))
        assertTrue(brTypes.contains("bridge_hop_relay_to_rx"))
        assertTrue(brTypes.contains("obstruction_peak"))
    }

    @Test
    fun `map export composites high-res annotated PNG and writes scoped share and SAF files`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rawMapBitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(18, 28, 38))
        }
        val state = com.example.ui.BridgeUiState()
        val summary = com.example.data.MapExportEngine.buildBridgeSummary(
            uiState = state,
            baseLayer = com.example.ui.MapBaseLayer.DARK_MATTER
        )

        val composed = com.example.data.MapExportEngine.composeAnnotatedExportBitmap(rawMapBitmap, summary)
        assertEquals(1200, composed.width)
        assertTrue("Annotated PNG should include header and footer beyond 900px", composed.height > 980)

        val pngBytes = com.example.data.MapExportEngine.encodeBitmapToPngBytes(composed)
        assertTrue(pngBytes.size > 100)
        // PNG magic signature: 89 50 4E 47
        assertEquals(0x89.toByte(), pngBytes[0])
        assertEquals(0x50.toByte(), pngBytes[1])
        assertEquals(0x4E.toByte(), pngBytes[2])
        assertEquals(0x47.toByte(), pngBytes[3])

        val fileName = com.example.data.MapExportEngine.suggestedFilename(
            "lemesh_bridge",
            com.example.data.MapExportFormat.PNG
        )
        assertTrue(fileName.startsWith("lemesh_bridge_"))
        assertTrue(fileName.endsWith(".png"))

        val shareUri = com.example.data.MapExportEngine.writeShareCacheFile(context, fileName, pngBytes)
            .getOrThrow()
        assertEquals("content", shareUri.scheme)
        assertTrue(shareUri.authority!!.endsWith(".fileprovider"))

        val destFile = java.io.File(context.cacheDir, "saf_test_out.geojson")
        val geoJsonBytes = """{"type":"FeatureCollection","features":[]}""".toByteArray(Charsets.UTF_8)
        com.example.data.MapExportEngine.writeBytesToUri(
            context,
            android.net.Uri.fromFile(destFile),
            geoJsonBytes
        ).getOrThrow()
        assertTrue(destFile.exists())
        assertEquals(geoJsonBytes.size.toLong(), destFile.length())
    }

    @Test
    fun `carto basemaps include all 6 styles with authenticated rastertiles URLs and dark matter default`() {
        val allLayers = com.example.ui.MapBaseLayer.entries
        assertEquals(8, allLayers.size)

        val cartoLayers = allLayers.filter { it.requiresCartoKey }
        assertEquals(
            listOf("dark_all", "dark_nolabels", "light_all", "light_nolabels", "voyager", "voyager_nolabels"),
            cartoLayers.map { it.cartoStyleSlug }
        )

        // Default startup base layer is CARTO Dark Matter
        assertEquals(com.example.ui.MapBaseLayer.DARK_MATTER, com.example.ui.NodeMapUiState().baseLayer)

        // Placeholder / blank key resolves to null and never appends bogus query param
        assertNull(com.example.ui.resolveCartoApiKey(""))
        assertNull(com.example.ui.resolveCartoApiKey(com.example.ui.CARTO_API_KEY_PLACEHOLDER))
        assertEquals("test_carto_key_123", com.example.ui.resolveCartoApiKey(" test_carto_key_123 "))

        val index = org.osmdroid.util.MapTileIndex.getTileIndex(11, 567, 756)
        cartoLayers.forEach { layer ->
            val slug = layer.cartoStyleSlug!!
            val source = com.example.ui.createCartoTileSource(slug, "test_carto_key_123")
            assertEquals(256, source.tileSizePixels)
            assertEquals(20, source.maximumZoomLevel)
            val tileUrl = source.getTileURLString(index)
            assertTrue("Expected rastertiles/$slug in $tileUrl", tileUrl.contains("/rastertiles/$slug/11/567/756.png"))
            assertTrue("Expected API key query param in $tileUrl", tileUrl.endsWith("?key=test_carto_key_123"))

            val keylessUrl = com.example.ui.createCartoTileSource(slug, null).getTileURLString(index)
            assertFalse("Unconfigured key should not emit ?key= in $keylessUrl", keylessUrl.contains("?key="))
        }
    }

    @Test
    fun `viewshed fix advisor solves grazing ridge within mast limit and escalates deep ridge to Bridge`() {
        val txLat = 42.1000
        val txLon = -80.1000
        val rxLat = 42.1900 // ~10.0 km north
        val rxLon = -80.1000
        val zoom = 12
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(rxLat + 0.02, txLon - 0.02, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(txLat - 0.02, txLon + 0.02, zoom)

        val flat200m = Color.rgb(128, 200, 0) // 200 m
        val grazingTiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                grazingTiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200m }
            }
        }

        // Helper to paint a ridge around midpoint lat 42.1450
        fun paintMidRidge(tiles: MutableMap<Long, IntArray>, elevMeters: Int) {
            val midLat = 42.1450
            val n = (1 shl zoom).toDouble()
            val latRad = Math.toRadians(midLat)
            val worldX = ((txLon + 180.0) / 360.0) * n
            val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
            val rTx = floor(worldX).toInt()
            val rTy = floor(worldY).toInt()
            val rPy = ((worldY - rTy) * 256.0).toInt().coerceIn(5, 250)
            val rPx = ((worldX - rTx) * 256.0).toInt().coerceIn(10, 245)
            val encoded = elevMeters + 32768
            val rgb = Color.rgb((encoded shr 8) and 0xFF, encoded and 0xFF, 0)
            val arr = tiles.getValue(TerrariumTileGrid.tileKey(rTx, rTy))
            for (py in (rPy - 3)..(rPy + 3)) {
                for (px in (rPx - 10)..(rPx + 10)) {
                    arr[py * 256 + px] = rgb
                }
            }
        }

        // Case 1: 215 m ridge -> 10m TX / 10m RX is GRAZING/OBSTRUCTED, fixable well under 60m
        paintMidRidge(grazingTiles, 215)
        val grazingGrid = TerrariumTileGrid(zoom = zoom, tiles = grazingTiles, tileCount = grazingTiles.size)
        val grazingPlan = com.example.data.ViewshedFixAdvisor.analyzeHopFixes(
            txLat = txLat,
            txLon = txLon,
            currentTxMeters = 10.0,
            rxLat = rxLat,
            rxLon = rxLon,
            currentRxMeters = 10.0,
            freqMhz = 906.875,
            eirpDbm = 35.8,
            sensitivityDbm = -130.0,
            grid = grazingGrid
        )
        assertTrue("Expected viable mast fixes for 215m ridge", grazingPlan.hasViableMastFix)
        val mastOptions = grazingPlan.options.filter {
            it.strategy != com.example.data.ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK
        }
        assertTrue(mastOptions.isNotEmpty())
        assertTrue(mastOptions.any { it.isRecommended })
        mastOptions.forEach { opt ->
            assertTrue(opt.targetTxHeightMeters <= com.example.data.ViewshedFixAdvisor.MAX_MAST_HEIGHT_METERS)
            assertTrue(opt.targetRxHeightMeters <= com.example.data.ViewshedFixAdvisor.MAX_MAST_HEIGHT_METERS)
            assertEquals(ViewshedPointStatus.CLEAR, opt.resultingHop?.status)
            assertTrue((opt.resultingHop?.minFresnelRatio ?: 0.0) >= 0.60)
            assertTrue((opt.resultingHop?.linkMarginDb ?: -1.0) >= 0.0)
        }
        val bridgeAlt = grazingPlan.options.last()
        assertEquals(com.example.data.ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK, bridgeAlt.strategy)
        assertFalse(bridgeAlt.isRecommended)

        // Case 2: 450 m mountain ridge -> exceeds 60m mast ceiling, Bridge fallback becomes Recommended
        val deepTiles = grazingTiles.mapValues { (_, arr) -> arr.clone() }.toMutableMap()
        paintMidRidge(deepTiles, 450)
        val deepGrid = TerrariumTileGrid(zoom = zoom, tiles = deepTiles, tileCount = deepTiles.size)
        val deepPlan = com.example.data.ViewshedFixAdvisor.analyzeHopFixes(
            txLat = txLat,
            txLon = txLon,
            currentTxMeters = 10.0,
            rxLat = rxLat,
            rxLon = rxLon,
            currentRxMeters = 10.0,
            freqMhz = 906.875,
            eirpDbm = 35.8,
            sensitivityDbm = -130.0,
            grid = deepGrid
        )
        assertFalse("450m ridge should not offer a single-hop mast fix <= 60m", deepPlan.hasViableMastFix)
        assertEquals(1, deepPlan.options.size)
        assertEquals(com.example.data.ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK, deepPlan.options.first().strategy)
        assertTrue(deepPlan.options.first().isRecommended)
        assertNotNull(deepPlan.diagnosticNote)
    }

    @Test
    fun `viewshed engine lists obstructed nodes with hints and viewmodel applyMastFix updates heights`() {
        val originLat = 42.1292
        val originLon = -80.0851
        val radiusKm = 10
        val zoom = TerrariumDemRepository.recommendedZoomForRadius(radiusKm)
        val latDelta = (radiusKm / 111.12) * 1.15
        val cosLat = cos(Math.toRadians(originLat))
        val lonDelta = (radiusKm / (111.32 * cosLat)) * 1.15
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(originLat + latDelta, originLon - lonDelta, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(originLat - latDelta, originLon + lonDelta, zoom)

        val flat200mRgb = Color.rgb(128, 200, 0) // 200 m
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { flat200mRgb }
            }
        }
        // Paint a 350 m ridge ~2 km north blocking northNode
        val ridgeLat = 42.1471
        val n = (1 shl zoom).toDouble()
        val ridgeLatRad = Math.toRadians(ridgeLat)
        val ridgeWorldX = ((originLon + 180.0) / 360.0) * n
        val ridgeWorldY = (1.0 - ln(tan(ridgeLatRad) + 1.0 / cos(ridgeLatRad)) / Math.PI) / 2.0 * n
        val ridgeTx = floor(ridgeWorldX).toInt()
        val ridgeTy = floor(ridgeWorldY).toInt()
        val ridgePy = ((ridgeWorldY - ridgeTy) * 256.0).toInt().coerceIn(5, 250)
        val ridgePx = ((ridgeWorldX - ridgeTx) * 256.0).toInt().coerceIn(20, 235)
        val ridgeRgb = Color.rgb(129, 94, 0) // 129*256 + 94 - 32768 = 350 m
        val ridgeArr = tiles.getValue(TerrariumTileGrid.tileKey(ridgeTx, ridgeTy))
        for (py in (ridgePy - 4)..(ridgePy + 4)) {
            for (px in (ridgePx - 20)..(ridgePx + 20)) {
                ridgeArr[py * 256 + px] = ridgeRgb
            }
        }
        val grid = TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)

        val blockedNorth = MeshNode(
            id = "!00001111",
            num = 0x1111L,
            longName = "North Blocked Node",
            shortName = "NBLK",
            hwModel = "TBEAM",
            role = "CLIENT",
            latitude = 42.1650,
            longitude = -80.0851,
            altitude = 200,
            hopsAway = 1,
            snr = -4.0,
            rssi = -114,
            viaMqtt = false,
            isFavorite = false,
            isSelf = false,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        val clearSouth = blockedNorth.copy(
            id = "!00002222",
            num = 0x2222L,
            longName = "South Clear Node",
            shortName = "SCLR",
            latitude = 42.0930,
            longitude = -80.0851
        )
        val grazingWest = blockedNorth.copy(
            id = "!00003333",
            num = 0x3333L,
            longName = "West Grazing Node",
            shortName = "WGRZ",
            latitude = 42.1292,
            longitude = -80.1880 // ~8.5 km west over flat terrain -> 60% F1 grazing at 40m TX / 2m RX
        )

        val result = ViewshedEngine.computeViewshed(
            ViewshedParams(originLat, originLon, "TX Site", antennaHeightMeters = 40.0, radiusKm = radiusKm),
            grid,
            listOf(blockedNorth, clearSouth, grazingWest)
        )
        assertEquals(1, result.coveredClearNodes.size)
        assertEquals("!00002222", result.coveredClearNodes.first().node.id)
        assertEquals(2, result.obstructedNodes.size)
        // Partial (GRAZING) nodes are sorted ahead of OBSTRUCTED nodes
        assertEquals("!00003333", result.obstructedNodes[0].node.id)
        assertEquals(ViewshedPointStatus.GRAZING, result.obstructedNodes[0].status)
        assertEquals("!00001111", result.obstructedNodes[1].node.id)
        assertEquals(ViewshedPointStatus.OBSTRUCTED, result.obstructedNodes[1].status)
        assertTrue(result.obstructedNodes[0].quickFixHint.isNotBlank())
        assertTrue(result.obstructedNodes[1].quickFixHint.isNotBlank())

        // Verify ViewshedViewModel.evaluateRxLinkOnGrid attaches fixPlan and applyMastFix clamps + updates heights
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.ViewshedViewModel(app)
        vm.selectExistingNode(clearSouth.copy(latitude = originLat, longitude = originLon, id = "!00000001", num = 1L))
        vm.inspectObstructedNodeForFix(blockedNorth)
        val linkEval = vm.evaluateRxLinkOnGrid(vm.uiState.value, grid)
        assertNotNull(linkEval)
        assertEquals(ViewshedPointStatus.OBSTRUCTED, linkEval!!.hop.status)
        assertTrue(linkEval.fixPlan.options.isNotEmpty())

        vm.applyMastFix(42f, 28f)
        assertEquals(42f, vm.uiState.value.antennaHeightMeters, 1e-3f)
        assertEquals(28f, vm.uiState.value.rxAntennaHeightMeters, 1e-3f)
        vm.applyMastFix(120f, 0f)
        assertEquals(60f, vm.uiState.value.antennaHeightMeters, 1e-3f)
        assertEquals(1f, vm.uiState.value.rxAntennaHeightMeters, 1e-3f)
    }

    @Test
    fun `startup location resolver follows gps then self node then plotted centroid then default`() {
        val selfNode = MeshNode(
            id = "!00001001",
            num = 0x1001L,
            longName = "Self Station",
            shortName = "SELF",
            hwModel = "TBEAM",
            role = "CLIENT",
            latitude = 37.7749,
            longitude = -122.4194,
            altitude = 50,
            hopsAway = 0,
            snr = null,
            rssi = null,
            viaMqtt = false,
            isFavorite = false,
            isSelf = true,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        val peerA = selfNode.copy(id = "!00001002", num = 0x1002L, isSelf = false, latitude = 40.0, longitude = -75.0)
        val peerB = selfNode.copy(id = "!00001003", num = 0x1003L, isSelf = false, latitude = 42.0, longitude = -77.0)

        // 1. Device GPS takes highest priority
        val gpsTarget = com.example.data.DeviceLocationResolver.resolveStartupTarget(
            deviceLocation = 34.0522 to -118.2437,
            selfNode = selfNode,
            allNodes = listOf(selfNode, peerA, peerB)
        )
        assertEquals(34.0522, gpsTarget.first, 1e-4)
        assertEquals(-118.2437, gpsTarget.second, 1e-4)

        // 2. Falls back to selfNode when device GPS is unavailable
        val selfTarget = com.example.data.DeviceLocationResolver.resolveStartupTarget(
            deviceLocation = null,
            selfNode = selfNode,
            allNodes = listOf(selfNode, peerA, peerB)
        )
        assertEquals(37.7749, selfTarget.first, 1e-4)
        assertEquals(-122.4194, selfTarget.second, 1e-4)

        // 3. Falls back to plotted nodes centroid when no GPS and no valid selfNode
        val centroidTarget = com.example.data.DeviceLocationResolver.resolveStartupTarget(
            deviceLocation = null,
            selfNode = null,
            allNodes = listOf(peerA, peerB)
        )
        assertEquals(41.0, centroidTarget.first, 1e-4)
        assertEquals(-76.0, centroidTarget.second, 1e-4)

        // 4. Falls back to default Erie coordinate when neither GPS nor NodeDB exists
        val defaultTarget = com.example.data.DeviceLocationResolver.resolveStartupTarget(
            deviceLocation = null,
            selfNode = null,
            allNodes = emptyList()
        )
        assertEquals(com.example.data.DeviceLocationResolver.DEFAULT_FALLBACK_LAT, defaultTarget.first, 1e-4)
        assertEquals(com.example.data.DeviceLocationResolver.DEFAULT_FALLBACK_LON, defaultTarget.second, 1e-4)
    }

    @Test
    fun `node map viewmodel restores cache silently and tracks one-shot startup zoom state`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val cacheJsonFile = java.io.File(app.filesDir, "cached_nodedb.json")
        val cacheMetaFile = java.io.File(app.filesDir, "cached_nodedb_meta.json")
        cacheJsonFile.writeText(
            """
            {
              "myInfo": { "myNodeNum": 4097 },
              "nodes": {
                "!00001001": {
                  "num": 4097,
                  "user": { "id": "!00001001", "longName": "Austin Hub", "shortName": "AUS" },
                  "position": { "latitude": 30.2672, "longitude": -97.7431 }
                }
              }
            }
            """.trimIndent()
        )
        cacheMetaFile.writeText("""{"fileName":"austin_nodes.json"}""")

        try {
            val vm = com.example.ui.NodeMapViewModel(app)
            vm.resolveStartupZoomTarget(permissionGranted = false)

            // Pump main looper so viewModelScope (Dispatchers.Main) executes cache restore + resolution
            var attempts = 0
            while (vm.uiState.value.startupTargetLat == null && attempts < 80) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(25)
                attempts++
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            val state = vm.uiState.value
            assertTrue(state.isInitialCacheRestored)
            assertTrue(state.hasLoadedFile)
            // Cached restore must NOT emit fitBoundsRequestId so startup zoom glide is uninterrupted
            assertEquals(0L, state.fitBoundsRequestId)
            assertEquals(30.2672, state.startupTargetLat!!, 1e-4)
            assertEquals(-97.7431, state.startupTargetLon!!, 1e-4)
            assertFalse(state.hasStartedStartupZoom)

            vm.markStartupZoomStarted()
            vm.saveMapCameraState(30.27, -97.74, 13.0)
            assertTrue(vm.uiState.value.hasStartedStartupZoom)
            assertEquals(13.0, vm.uiState.value.savedCameraZoom!!, 1e-4)
        } finally {
            cacheJsonFile.delete()
            cacheMetaFile.delete()
        }
    }
}
