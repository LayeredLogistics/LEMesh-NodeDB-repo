package com.example.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class AggregateCoverageParams(
    val radiusKm: Int = 10,
    val antennaHeightMeters: Double = 10.0,
    val frequencyMhz: Double = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ,
    val txPowerDbm: Double = RelayBridgeEngine.DEFAULT_TX_POWER_DBM,
    val antennaGainDbi: Double = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI,
    val sensitivityDbm: Double = RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM
)

data class FilteredAggregateNodes(
    val selectedNodes: List<MeshNode>,
    val totalEligibleCount: Int,
    val skippedCapCount: Int
)

data class AggregateCoverageResult(
    val fields: List<PolarVisibilityField>,
    val contributingNodeCount: Int,
    val totalEligibleCount: Int,
    val skippedCapCount: Int,
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double,
    val estimatedCoveredAreaKm2: Double,
    val peakRssiDbm: Double,
    val sensitivityDbm: Double,
    val signature: String,
    val computeTimeMs: Long
)

object AggregateCoverageEngine {

    const val MAX_AGGREGATE_NODES = 36
    private const val CLUSTER_BUCKET_DEG = 0.40
    private const val CLUSTER_SCAN_DIM = 96

    fun filterEligibleRfNodes(
        nodes: List<MeshNode>,
        maxNodes: Int = MAX_AGGREGATE_NODES
    ): FilteredAggregateNodes {
        val eligible = nodes
            .filter { it.hasValidGps && !it.viaMqtt }
            .sortedWith(
                compareBy<MeshNode>(
                    { priorityTier(it) },
                    { -(it.snr ?: Double.NEGATIVE_INFINITY) },
                    { it.hopsAway ?: Int.MAX_VALUE },
                    { it.id }
                )
            )
        val selected = if (eligible.size > maxNodes) eligible.take(maxNodes) else eligible
        return FilteredAggregateNodes(
            selectedNodes = selected,
            totalEligibleCount = eligible.size,
            skippedCapCount = (eligible.size - selected.size).coerceAtLeast(0)
        )
    }

    private fun priorityTier(node: MeshNode): Int {
        val upperRole = node.role?.uppercase(Locale.US).orEmpty()
        return when {
            node.isSelf -> 0
            node.isFavorite -> 1
            upperRole.contains("ROUTER") || upperRole.contains("REPEATER") -> 2
            node.hopsAway == 0 -> 3
            else -> 4
        }
    }

    fun buildSignature(
        nodes: List<MeshNode>,
        params: AggregateCoverageParams
    ): String {
        val filtered = filterEligibleRfNodes(nodes).selectedNodes
        return buildSignatureForSelected(filtered, params)
    }

    fun buildSignatureForSelected(
        selectedNodes: List<MeshNode>,
        params: AggregateCoverageParams
    ): String {
        val nodeKey = selectedNodes.joinToString(";") { node ->
            String.format(
                Locale.US,
                "%s:%.5f,%.5f",
                node.id,
                node.latitude ?: 0.0,
                node.longitude ?: 0.0
            )
        }
        return String.format(
            Locale.US,
            "r=%d|h=%.1f|f=%.3f|p=%.1f|g=%.1f|s=%.1f|n=%d|%d",
            params.radiusKm,
            params.antennaHeightMeters,
            params.frequencyMhz,
            params.txPowerDbm,
            params.antennaGainDbi,
            params.sensitivityDbm,
            selectedNodes.size,
            nodeKey.hashCode()
        )
    }

    fun spatialClusters(
        nodes: List<MeshNode>,
        bucketDeg: Double = CLUSTER_BUCKET_DEG
    ): List<List<MeshNode>> {
        if (nodes.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<Pair<Int, Int>, MutableList<MeshNode>>()
        nodes.forEach { node ->
            val lat = node.latitude ?: return@forEach
            val lon = node.longitude ?: return@forEach
            val key = floor(lat / bucketDeg).toInt() to floor(lon / bucketDeg).toInt()
            grouped.getOrPut(key) { mutableListOf() }.add(node)
        }
        return grouped.values.toList()
    }

    suspend fun computeAggregateCoverage(
        context: Context,
        nodes: List<MeshNode>,
        params: AggregateCoverageParams = AggregateCoverageParams(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        tileGridProvider: (suspend (
            northLat: Double,
            southLat: Double,
            westLon: Double,
            eastLon: Double,
            zoom: Int
        ) -> Result<TerrariumTileGrid>)? = null
    ): Result<AggregateCoverageResult> {
        val startMs = System.currentTimeMillis()
        val filtered = filterEligibleRfNodes(nodes)
        val selected = filtered.selectedNodes
        if (selected.isEmpty()) {
            return Result.failure(
                IllegalStateException("No eligible non-MQTT RF nodes with GPS coordinates in the active map view.")
            )
        }

        val signature = buildSignatureForSelected(selected, params)
        val radiusKm = params.radiusKm.coerceIn(1, ViewshedEngine.MAX_SUPPORTED_RADIUS_KM).toDouble()
        val rayCount = if (selected.size <= 8) 1080 else 720
        val stepMeters = ViewshedEngine.adaptiveStepMeters(radiusKm)
        val clusters = spatialClusters(selected)

        return try {
            val doneCounter = AtomicInteger(0)
            val totalNodes = selected.size
            onProgress(0, totalNodes)

            // 1. Load DEM tile grids once per spatial cluster
            val clusterGrids = mutableListOf<Pair<List<MeshNode>, TerrariumTileGrid>>()
            for (clusterNodes in clusters) {
                var minLat = Double.POSITIVE_INFINITY
                var maxLat = Double.NEGATIVE_INFINITY
                var minLon = Double.POSITIVE_INFINITY
                var maxLon = Double.NEGATIVE_INFINITY
                clusterNodes.forEach { n ->
                    val lat = n.latitude ?: return@forEach
                    val lon = n.longitude ?: return@forEach
                    minLat = min(minLat, lat)
                    maxLat = max(maxLat, lat)
                    minLon = min(minLon, lon)
                    maxLon = max(maxLon, lon)
                }
                val latPad = (radiusKm / 111.12) * 1.08
                val cNorth = (maxLat + latPad).coerceAtMost(85.0)
                val cSouth = (minLat - latPad).coerceAtLeast(-85.0)
                val poleLat = max(abs(cNorth), abs(cSouth))
                val cosLat = cos(Math.toRadians(poleLat)).coerceAtLeast(0.15)
                val lonPad = (radiusKm / (111.32 * cosLat)) * 1.08
                val cWest = minLon - lonPad
                val cEast = maxLon + lonPad

                val zoom = TerrariumDemRepository.recommendedZoomForBounds(
                    northLat = cNorth,
                    southLat = cSouth,
                    westLon = cWest,
                    eastLon = cEast,
                    preferredRadiusKm = params.radiusKm
                )

                val gridResult = tileGridProvider?.invoke(cNorth, cSouth, cWest, cEast, zoom)
                    ?: TerrariumDemRepository.loadTileGridForBounds(
                        context = context,
                        northLat = cNorth,
                        southLat = cSouth,
                        westLon = cWest,
                        eastLon = cEast,
                        zoom = zoom
                    )

                val grid = gridResult.getOrElse { err ->
                    return Result.failure(err)
                }
                clusterGrids += clusterNodes to grid
            }

            // 2. Compute PolarVisibilityField per node in parallel on worker pool
            val workerConcurrency = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val semaphore = Semaphore(workerConcurrency)

            val computedPairs: List<Pair<Int, PolarVisibilityField>> = withContext(Dispatchers.Default) {
                coroutineScope {
                    var clusterIdx = 0
                    clusterGrids.flatMap { (clusterNodes, grid) ->
                        val cId = clusterIdx++
                        clusterNodes.map { node ->
                            async {
                                semaphore.withPermit {
                                    val output = ViewshedEngine.computePolarField(
                                        originLat = node.latitude!!,
                                        originLon = node.longitude!!,
                                        antennaHeightMeters = params.antennaHeightMeters,
                                        radiusKm = radiusKm,
                                        frequencyMhz = params.frequencyMhz,
                                        txPowerDbm = params.txPowerDbm,
                                        antennaGainDbi = params.antennaGainDbi,
                                        sensitivityDbm = params.sensitivityDbm,
                                        grid = grid,
                                        rayCount = rayCount,
                                        stepMeters = stepMeters
                                    )
                                    val finished = doneCounter.incrementAndGet()
                                    onProgress(finished, totalNodes)
                                    cId to output.field
                                }
                            }
                        }
                    }.awaitAll()
                }
            }

            val fields = computedPairs.map { it.second }
            val unionNorth = fields.maxOf { it.northLat }
            val unionSouth = fields.minOf { it.southLat }
            val unionWest = fields.minOf { it.westLon }
            val unionEast = fields.maxOf { it.eastLon }

            // 3. Estimate non-double-counted union coverage area & peak combined RSSI across clusters
            val (areaKm2, peakRssiDbm) = withContext(Dispatchers.Default) {
                estimateUnionMetrics(
                    fieldsByCluster = computedPairs.groupBy({ it.first }, { it.second }).values.toList(),
                    allFields = fields,
                    sensitivityDbm = params.sensitivityDbm
                )
            }

            Result.success(
                AggregateCoverageResult(
                    fields = fields,
                    contributingNodeCount = fields.size,
                    totalEligibleCount = filtered.totalEligibleCount,
                    skippedCapCount = filtered.skippedCapCount,
                    northLat = unionNorth,
                    southLat = unionSouth,
                    westLon = unionWest,
                    eastLon = unionEast,
                    estimatedCoveredAreaKm2 = areaKm2,
                    peakRssiDbm = peakRssiDbm,
                    sensitivityDbm = params.sensitivityDbm,
                    signature = signature,
                    computeTimeMs = System.currentTimeMillis() - startMs
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun estimateUnionMetrics(
        fieldsByCluster: List<List<PolarVisibilityField>>,
        allFields: List<PolarVisibilityField>,
        sensitivityDbm: Double
    ): Pair<Double, Double> {
        if (allFields.isEmpty()) return 0.0 to sensitivityDbm

        var totalAreaKm2 = 0.0
        var peakRssi = Double.NEGATIVE_INFINITY
        val processedClusterBounds = mutableListOf<DoubleArray>() // [N, S, W, E]
        val sampleOut = DoubleArray(3)

        for (clusterFields in fieldsByCluster) {
            if (clusterFields.isEmpty()) continue
            val cNorth = clusterFields.maxOf { it.northLat }
            val cSouth = clusterFields.minOf { it.southLat }
            val cWest = clusterFields.minOf { it.westLon }
            val cEast = clusterFields.maxOf { it.eastLon }

            val midLatCos = cos(Math.toRadians((cNorth + cSouth) * 0.5)).coerceAtLeast(0.15)
            val heightKm = (cNorth - cSouth).coerceAtLeast(1e-5) * 111.132
            val widthKm = (cEast - cWest).coerceAtLeast(1e-5) * 111.320 * midLatCos
            val cellWidthKm = widthKm / CLUSTER_SCAN_DIM
            val cellHeightKm = heightKm / CLUSTER_SCAN_DIM
            val cellAreaKm2 = cellWidthKm * cellHeightKm
            val pixelMeters = max(cellWidthKm, cellHeightKm) * 1000.0

            for (py in 0 until CLUSTER_SCAN_DIM) {
                val lat = cNorth - ((py + 0.5) / CLUSTER_SCAN_DIM) * (cNorth - cSouth)
                for (px in 0 until CLUSTER_SCAN_DIM) {
                    val lon = cWest + ((px + 0.5) / CLUSTER_SCAN_DIM) * (cEast - cWest)

                    // Avoid double-counting points already covered by an earlier cluster's scan
                    var alreadyScanned = false
                    for (prev in processedClusterBounds) {
                        if (lat <= prev[0] && lat >= prev[1] && lon >= prev[2] && lon <= prev[3]) {
                            alreadyScanned = true
                            break
                        }
                    }
                    if (alreadyScanned) continue

                    var linearPowerSum = 0.0
                    var unblockedComplement = 1.0
                    for (f in allFields) {
                        if (lat > f.northLat || lat < f.southLat || lon < f.westLon || lon > f.eastLon) continue
                        if (f.sampleContinuous(lat, lon, pixelMeters, sampleOut)) {
                            val edge = sampleOut[0]
                            val rssi = sampleOut[2]
                            linearPowerSum += edge * 10.0.pow((rssi + 100.0) * 0.1)
                            unblockedComplement *= (1.0 - edge)
                        }
                    }

                    val unionEdge = (1.0 - unblockedComplement).coerceIn(0.0, 1.0)
                    if (unionEdge >= 0.5 && linearPowerSum > 0.0) {
                        totalAreaKm2 += cellAreaKm2
                        val aggRssi = 10.0 * log10((linearPowerSum / unionEdge).coerceAtLeast(1e-12)) - 100.0
                        if (aggRssi > peakRssi) peakRssi = aggRssi
                    }
                }
            }
            processedClusterBounds += doubleArrayOf(cNorth, cSouth, cWest, cEast)
        }

        val resolvedPeak = if (peakRssi.isFinite()) peakRssi else sensitivityDbm
        return totalAreaKm2 to resolvedPeak
    }
}
