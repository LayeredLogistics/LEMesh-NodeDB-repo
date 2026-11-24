package us.lemesh.nodedb.ui.state

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.RelayBridgeEngine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class WorkspaceMode(val label: String) {
    MESH("Mesh Map"),
    VIEWSHED("Viewshed"),
    BRIDGE("Bridge Finder"),
    LIDAR_STREET("LiDAR")
}

data class NodeRfPreset(
    val mastHeightMeters: Float,
    val txPowerDbm: Float,
    val antennaGainDbi: Float,
    val sensitivityDbm: Float
)

data class RfWorkspaceState(
    val activeMode: WorkspaceMode = WorkspaceMode.MESH,
    val lastHandoffSourceNodeKey: String? = null,
    val activeJobCount: Int = 0
)

class RfWorkspaceCoordinator {
    private val _state = MutableStateFlow(RfWorkspaceState())
    val state: StateFlow<RfWorkspaceState> = _state.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun switchMode(mode: WorkspaceMode) {
        if (_state.value.activeMode == mode) return
        // Cancel transient probe/debounce jobs from the outgoing mode without clearing solved RF outputs.
        when (_state.value.activeMode) {
            WorkspaceMode.MESH -> cancelJobsWithPrefix("mesh_transient_")
            WorkspaceMode.VIEWSHED -> cancelJobsWithPrefix("viewshed_transient_")
            WorkspaceMode.BRIDGE -> cancelJobsWithPrefix("bridge_transient_")
            WorkspaceMode.LIDAR_STREET -> cancelJobsWithPrefix("lidar_transient_")
        }
        _state.update { it.copy(activeMode = mode) }
    }

    fun recordHandoff(node: MeshNode, targetMode: WorkspaceMode) {
        _state.update {
            it.copy(
                activeMode = targetMode,
                lastHandoffSourceNodeKey = node.stableKey
            )
        }
    }

    fun onPinDragStarted(mode: WorkspaceMode) {
        when (mode) {
            WorkspaceMode.VIEWSHED -> {
                cancelJobsWithPrefix("viewshed_")
            }
            WorkspaceMode.BRIDGE -> {
                cancelJobsWithPrefix("bridge_")
            }
            WorkspaceMode.MESH -> {
                cancelJobsWithPrefix("mesh_los_")
            }
            WorkspaceMode.LIDAR_STREET -> {
                cancelJobsWithPrefix("lidar_")
            }
        }
    }

    fun registerJob(key: String, job: Job) {
        activeJobs.put(key, job)?.cancel()
        updateActiveJobCount()
        job.invokeOnCompletion {
            activeJobs.remove(key, job)
            updateActiveJobCount()
        }
    }

    fun cancelJob(key: String) {
        activeJobs.remove(key)?.cancel()
        updateActiveJobCount()
    }

    fun cancelJobsWithPrefix(prefix: String) {
        val matchingKeys = activeJobs.keys.filter { it.startsWith(prefix) }
        matchingKeys.forEach { key ->
            activeJobs.remove(key)?.cancel()
        }
        updateActiveJobCount()
    }

    fun hasActiveJob(key: String): Boolean = activeJobs[key]?.isActive == true

    private fun updateActiveJobCount() {
        val count = activeJobs.values.count { it.isActive }
        _state.update { it.copy(activeJobCount = count) }
    }

    companion object {
        fun deriveNodeRfPreset(node: MeshNode): NodeRfPreset {
            val upperRole = node.role?.trim()?.uppercase(Locale.US).orEmpty()
            val upperHw = node.hwModel?.trim()?.uppercase(Locale.US).orEmpty()

            val mastHeight = when {
                "REPEATER" in upperRole || "ROUTER_LATE" in upperRole || upperRole == "ROUTER" -> 15f
                "ROUTER_CLIENT" in upperRole || "CLIENT_BASE" in upperRole -> 12f
                else -> 10f
            }

            val txPower = when {
                "STATION_G2" in upperHw -> 30f
                "STATION_G1" in upperHw -> 27f
                "T_DECK" in upperHw || "TBEAM" in upperHw || "HELTEC" in upperHw || "RAK4631" in upperHw -> 22f
                else -> RelayBridgeEngine.DEFAULT_TX_POWER_DBM.toFloat()
            }

            val gainDbi = when {
                "REPEATER" in upperRole || upperRole == "ROUTER" || "ROUTER_LATE" in upperRole -> 5.8f
                "CLIENT_BASE" in upperRole -> 4.0f
                else -> RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI.toFloat()
            }

            return NodeRfPreset(
                mastHeightMeters = mastHeight,
                txPowerDbm = txPower,
                antennaGainDbi = gainDbi,
                sensitivityDbm = RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM.toFloat()
            )
        }
    }
}
