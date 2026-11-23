package us.lemesh.nodedb.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

enum class OfflineDemPreset(
    val id: String,
    val title: String,
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double,
    val minZoom: Int,
    val maxZoom: Int
) {
    ERIE_PRESQUE_ISLE(
        id = "erie_presque_isle",
        title = "Erie County & Presque Isle Core",
        northLat = 42.27,
        southLat = 41.84,
        westLon = -80.52,
        eastLon = -79.76,
        minZoom = 11,
        maxZoom = 13
    ),
    NW_PA_CORRIDOR(
        id = "nw_pa_corridor",
        title = "NW PA & I-90 / Route 5 & 20 Corridor",
        northLat = 42.30,
        southLat = 41.70,
        westLon = -80.85,
        eastLon = -79.65,
        minZoom = 11,
        maxZoom = 12
    )
}

data class DemPackDownloadProgress(
    val packId: String,
    val packTitle: String,
    val downloadedTiles: Int,
    val totalTiles: Int,
    val failedTiles: Int = 0,
    val isCancelling: Boolean = false
) {
    val fraction: Float
        get() = if (totalTiles > 0) (downloadedTiles.toFloat() / totalTiles).coerceIn(0f, 1f) else 0f
}

data class OfflineDemUiState(
    val isOfflineOnlyMode: Boolean = false,
    val cachedTileCount: Int = 0,
    val cacheSizeBytes: Long = 0L,
    val activeDownload: DemPackDownloadProgress? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val cacheSizeMb: Double
        get() = cacheSizeBytes / (1024.0 * 1024.0)
}

object OfflineDemPackManager {

    private const val PACK_DOWNLOAD_CONCURRENCY = 4
    private const val MAX_VIEWPORT_PACK_TILES = 600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _uiState = MutableStateFlow(OfflineDemUiState())
    val uiState: StateFlow<OfflineDemUiState> = _uiState.asStateFlow()

    @Volatile
    private var activeDownloadJob: Job? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val offline = TerrariumDemRepository.syncOfflineSetting(appContext)
        val stats = TerrariumDemRepository.computeDemDiskStats(appContext)
        _uiState.update {
            it.copy(
                isOfflineOnlyMode = offline,
                cachedTileCount = stats.fileCount,
                cacheSizeBytes = stats.totalBytes
            )
        }
    }

    fun refreshCacheStats(context: Context) {
        val stats = TerrariumDemRepository.computeDemDiskStats(context.applicationContext)
        _uiState.update {
            it.copy(
                cachedTileCount = stats.fileCount,
                cacheSizeBytes = stats.totalBytes
            )
        }
    }

    fun setOfflineOnlyMode(context: Context, enabled: Boolean) {
        TerrariumDemRepository.setOfflineOnlyMode(context.applicationContext, enabled)
        _uiState.update {
            it.copy(
                isOfflineOnlyMode = enabled,
                statusMessage = if (enabled) {
                    "Offline-Only DEM Mode active — network terrain fetches blocked."
                } else {
                    "Online DEM mode active — missing tiles will download on demand."
                },
                errorMessage = null
            )
        }
    }

    fun clearDemCache(context: Context) {
        val appContext = context.applicationContext
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        TerrariumDemRepository.clearDemCache(appContext)
        val stats = TerrariumDemRepository.computeDemDiskStats(appContext)
        _uiState.update {
            it.copy(
                cachedTileCount = stats.fileCount,
                cacheSizeBytes = stats.totalBytes,
                activeDownload = null,
                statusMessage = "Cleared cached DEM terrain tiles.",
                errorMessage = null
            )
        }
    }

    fun enumerateTilesForBounds(
        northLat: Double,
        southLat: Double,
        westLon: Double,
        eastLon: Double,
        minZoom: Int,
        maxZoom: Int
    ): List<TileCoord> {
        val nLat = max(northLat, southLat).coerceIn(-85.0, 85.0)
        val sLat = min(northLat, southLat).coerceIn(-85.0, 85.0)
        val wLon = min(westLon, eastLon).coerceIn(-180.0, 180.0)
        val eLon = max(westLon, eastLon).coerceIn(-180.0, 180.0)

        val coords = ArrayList<TileCoord>()
        for (z in minZoom..maxZoom) {
            val (minTx, minTy) = TerrariumDemRepository.latLonToTile(nLat, wLon, z)
            val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(sLat, eLon, z)
            val txStart = min(minTx, maxTx)
            val txEnd = max(minTx, maxTx)
            val tyStart = min(minTy, maxTy)
            val tyEnd = max(minTy, maxTy)
            for (ty in tyStart..tyEnd) {
                for (tx in txStart..txEnd) {
                    coords.add(TileCoord(z, tx, ty))
                }
            }
        }
        return coords
    }

    fun startPresetDownload(context: Context, preset: OfflineDemPreset): Job {
        val tiles = enumerateTilesForBounds(
            northLat = preset.northLat,
            southLat = preset.southLat,
            westLon = preset.westLon,
            eastLon = preset.eastLon,
            minZoom = preset.minZoom,
            maxZoom = preset.maxZoom
        )
        return launchPackDownload(
            context = context.applicationContext,
            packId = preset.id,
            packTitle = preset.title,
            tiles = tiles
        )
    }

    fun startViewportDownload(
        context: Context,
        northLat: Double,
        southLat: Double,
        westLon: Double,
        eastLon: Double
    ): Job {
        val tiles = enumerateTilesForBounds(
            northLat = northLat,
            southLat = southLat,
            westLon = westLon,
            eastLon = eastLon,
            minZoom = 11,
            maxZoom = 13
        )
        if (tiles.size > MAX_VIEWPORT_PACK_TILES) {
            _uiState.update {
                it.copy(
                    errorMessage = "Viewport is too wide (${tiles.size} tiles). Zoom in closer (max $MAX_VIEWPORT_PACK_TILES tiles per viewport pack)."
                )
            }
            return Job().apply { complete() }
        }
        return launchPackDownload(
            context = context.applicationContext,
            packId = "current_viewport",
            packTitle = "Current Map Viewport",
            tiles = tiles
        )
    }

    fun cancelDownload() {
        val job = activeDownloadJob ?: return
        _uiState.update { state ->
            state.copy(
                activeDownload = state.activeDownload?.copy(isCancelling = true)
            )
        }
        job.cancel()
    }

    fun dismissMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    private fun launchPackDownload(
        context: Context,
        packId: String,
        packTitle: String,
        tiles: List<TileCoord>
    ): Job {
        activeDownloadJob?.cancel()
        val total = tiles.size
        val alreadyOnDisk = tiles.count { TerrariumDemRepository.isTileCachedOnDisk(context, it.zoom, it.x, it.y) }

        val job = scope.launch {
            _uiState.update {
                it.copy(
                    activeDownload = DemPackDownloadProgress(
                        packId = packId,
                        packTitle = packTitle,
                        downloadedTiles = alreadyOnDisk,
                        totalTiles = total
                    ),
                    statusMessage = null,
                    errorMessage = null
                )
            }

            try {
                val missing = tiles.filterNot {
                    TerrariumDemRepository.isTileCachedOnDisk(context, it.zoom, it.x, it.y)
                }
                val doneCounter = AtomicInteger(alreadyOnDisk)
                val failCounter = AtomicInteger(0)
                val semaphore = Semaphore(PACK_DOWNLOAD_CONCURRENCY)

                if (missing.isNotEmpty()) {
                    coroutineScope {
                        missing.map { coord ->
                            async {
                                semaphore.withPermit {
                                    ensureActive()
                                    val res = TerrariumDemRepository.ensureTileCached(
                                        context = context,
                                        coord = coord,
                                        allowNetworkWhenOffline = true
                                    )
                                    if (res.isFailure) {
                                        failCounter.incrementAndGet()
                                    }
                                    val completed = doneCounter.incrementAndGet().coerceAtMost(total)
                                    _uiState.update { current ->
                                        current.copy(
                                            activeDownload = current.activeDownload?.copy(
                                                downloadedTiles = completed,
                                                failedTiles = failCounter.get()
                                            )
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                val stats = TerrariumDemRepository.computeDemDiskStats(context)
                val failed = failCounter.get()
                _uiState.update {
                    it.copy(
                        cachedTileCount = stats.fileCount,
                        cacheSizeBytes = stats.totalBytes,
                        activeDownload = null,
                        statusMessage = if (failed == 0) {
                            "$packTitle cached ($total/$total tiles ready offline)."
                        } else {
                            "$packTitle finished with $failed failed tile(s)."
                        },
                        errorMessage = if (failed > 0) "Unable to download $failed DEM tile(s). Check network connectivity." else null
                    )
                }
            } catch (_: CancellationException) {
                val stats = withContext(Dispatchers.IO) {
                    TerrariumDemRepository.computeDemDiskStats(context)
                }
                _uiState.update {
                    it.copy(
                        cachedTileCount = stats.fileCount,
                        cacheSizeBytes = stats.totalBytes,
                        activeDownload = null,
                        statusMessage = "$packTitle download canceled (partial tiles retained)."
                    )
                }
            } finally {
                if (activeDownloadJob?.isActive != true) {
                    activeDownloadJob = null
                }
            }
        }
        activeDownloadJob = job
        return job
    }
}
