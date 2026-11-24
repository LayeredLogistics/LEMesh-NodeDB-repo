package us.lemesh.nodedb.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

enum class BleConnectionStatus(val label: String) {
    OFFLINE("Offline"),
    SCANNING("Scanning"),
    CONNECTING("Connecting"),
    SYNCING("Syncing"),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting")
}

data class DiscoveredMeshtasticDevice(
    val address: String,
    val name: String,
    val rssiDbm: Int,
    val lastSeenEpochMs: Long = System.currentTimeMillis()
) {
    val signalBars: Int
        get() = when {
            rssiDbm >= -60 -> 4
            rssiDbm >= -72 -> 3
            rssiDbm >= -85 -> 2
            else -> 1
        }
}

data class SavedBleRadio(
    val address: String,
    val name: String,
    val shortName: String? = null
)

data class BleSyncUiState(
    val status: BleConnectionStatus = BleConnectionStatus.OFFLINE,
    val isScanning: Boolean = false,
    val discoveredDevices: List<DiscoveredMeshtasticDevice> = emptyList(),
    val connectedAddress: String? = null,
    val connectedDeviceName: String? = null,
    val connectedShortName: String? = null,
    val myNodeNum: Long? = null,
    val savedRadio: SavedBleRadio? = null,
    val nodesSyncedCount: Int = 0,
    val packetsReceivedCount: Int = 0,
    val lastPacketEpochSeconds: Long? = null,
    val isAutoConnecting: Boolean = false,
    val autoConnectTimedOut: Boolean = false,
    val reconnectAttempt: Int = 0,
    val lastError: String? = null
) {
    val isActiveOrConnected: Boolean
        get() = status == BleConnectionStatus.CONNECTED ||
            status == BleConnectionStatus.SYNCING ||
            status == BleConnectionStatus.CONNECTING ||
            status == BleConnectionStatus.RECONNECTING
}

/**
 * Direct Bluetooth Low Energy (BLE) Meshtastic PhoneAPI client.
 * Handles BLE scanning, GATT handshake (MTU 512, FromNum notifications, want_config_id),
 * sequential FromRadio draining, exponential backoff reconnects, and session radio persistence.
 */
class MeshtasticBleRepository(
    context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val appContext = context?.applicationContext
    private val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        BleSyncUiState(savedRadio = loadSavedRadio())
    )
    val uiState: StateFlow<BleSyncUiState> = _uiState.asStateFlow()

    private val _incomingUpdates = MutableSharedFlow<List<LiveTelemetryUpdate>>(extraBufferCapacity = 64)
    val incomingUpdates: SharedFlow<List<LiveTelemetryUpdate>> = _incomingUpdates.asSharedFlow()

    private val gattOpMutex = Mutex()
    private val hasAttemptedLaunchAutoConnect = AtomicBoolean(false)

    @Volatile
    private var activeGatt: BluetoothGatt? = null

    @Volatile
    private var shouldStayConnected: Boolean = false

    @Volatile
    private var activeScanCallback: ScanCallback? = null

    private var scanTimeoutJob: Job? = null
    private var connectionJob: Job? = null

    @Volatile
    private var connectDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var disconnectSignal: CompletableDeferred<Unit>? = null

    @Volatile
    private var mtuDeferred: CompletableDeferred<Int>? = null

    @Volatile
    private var servicesDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var descriptorWriteDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var characteristicWriteDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var characteristicReadDeferred: CompletableDeferred<ByteArray?>? = null

    private var fromNumChannel = Channel<Unit>(Channel.CONFLATED)

    private val discoveredMap = LinkedHashMap<String, DiscoveredMeshtasticDevice>()

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val ctx = appContext ?: return null
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val ctx = appContext
        if (ctx == null || !hasBlePermissions(ctx)) {
            _uiState.update { it.copy(lastError = "Bluetooth permissions are required to scan for Meshtastic radios.") }
            return
        }
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _uiState.update { it.copy(lastError = "Turn on Bluetooth to scan for nearby Meshtastic radios.") }
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _uiState.update { it.copy(lastError = "BLE scanner is unavailable on this device.") }
            return
        }

        stopScanInternal()
        synchronized(discoveredMap) {
            discoveredMap.clear()
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                recordScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::recordScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        status = if (it.status == BleConnectionStatus.SCANNING) BleConnectionStatus.OFFLINE else it.status,
                        lastError = "BLE scan failed (code $errorCode)."
                    )
                }
            }
        }
        activeScanCallback = callback

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESHTASTIC_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            scanner.startScan(listOf(filter), settings, callback)
        }.onFailure { err ->
            activeScanCallback = null
            _uiState.update {
                it.copy(
                    isScanning = false,
                    lastError = err.message ?: "Unable to start BLE scan."
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                isScanning = true,
                discoveredDevices = emptyList(),
                status = if (state.status == BleConnectionStatus.OFFLINE) BleConnectionStatus.SCANNING else state.status,
                lastError = null
            )
        }

        scanTimeoutJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    fun stopScan() {
        stopScanInternal()
        _uiState.update { state ->
            state.copy(
                isScanning = false,
                status = if (state.status == BleConnectionStatus.SCANNING) BleConnectionStatus.OFFLINE else state.status
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val cb = activeScanCallback
        activeScanCallback = null
        if (cb != null) {
            runCatching { bluetoothAdapter()?.bluetoothLeScanner?.stopScan(cb) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val advertisedName = result.scanRecord?.deviceName?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { device.name?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
            ?: "Meshtastic ${address.takeLast(5)}"

        val entry = DiscoveredMeshtasticDevice(
            address = address,
            name = advertisedName,
            rssiDbm = result.rssi,
            lastSeenEpochMs = System.currentTimeMillis()
        )
        val sorted = synchronized(discoveredMap) {
            discoveredMap[address] = entry
            discoveredMap.values.sortedByDescending { it.rssiDbm }
        }
        _uiState.update { it.copy(discoveredDevices = sorted) }
    }

    /**
     * Attempts a bounded auto-connect to the remembered radio on app startup when BLE
     * permissions are already granted. Stops after the backoff window and falls back
     * to the remembered radio quick-connect card if out of range.
     */
    fun attemptLaunchAutoConnectIfPermitted() {
        if (!hasAttemptedLaunchAutoConnect.compareAndSet(false, true)) return
        val ctx = appContext ?: return
        val saved = _uiState.value.savedRadio ?: return
        if (_uiState.value.isActiveOrConnected) return
        if (!hasBlePermissions(ctx)) return
        val adapter = bluetoothAdapter() ?: return
        if (!adapter.isEnabled) return

        connectInternal(
            address = saved.address,
            deviceName = saved.name,
            isLaunchAutoConnect = true
        )
    }

    fun connectToDevice(device: DiscoveredMeshtasticDevice) {
        connectInternal(
            address = device.address,
            deviceName = device.name,
            isLaunchAutoConnect = false
        )
    }

    fun reconnectSavedRadio() {
        val saved = _uiState.value.savedRadio ?: return
        connectInternal(
            address = saved.address,
            deviceName = saved.name,
            isLaunchAutoConnect = false
        )
    }

    fun disconnect() {
        shouldStayConnected = false
        stopScanInternal()
        connectionJob?.cancel()
        connectionJob = null
        closeActiveGatt()
        _uiState.update { state ->
            state.copy(
                status = BleConnectionStatus.OFFLINE,
                isScanning = false,
                connectedAddress = null,
                connectedDeviceName = null,
                connectedShortName = null,
                myNodeNum = null,
                isAutoConnecting = false,
                reconnectAttempt = 0,
                lastError = null
            )
        }
    }

    fun forgetSavedRadio() {
        val savedAddr = _uiState.value.savedRadio?.address
        if (savedAddr != null && _uiState.value.connectedAddress == savedAddr) {
            disconnect()
        }
        prefs?.edit()?.clear()?.apply()
        _uiState.update {
            it.copy(
                savedRadio = null,
                autoConnectTimedOut = false
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(lastError = null) }
    }

    private fun connectInternal(
        address: String,
        deviceName: String,
        isLaunchAutoConnect: Boolean
    ) {
        val ctx = appContext
        if (ctx == null || !hasBlePermissions(ctx)) {
            _uiState.update {
                it.copy(
                    status = BleConnectionStatus.OFFLINE,
                    isAutoConnecting = false,
                    lastError = "Grant Bluetooth permissions to connect to your Meshtastic radio."
                )
            }
            return
        }
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _uiState.update {
                it.copy(
                    status = BleConnectionStatus.OFFLINE,
                    isAutoConnecting = false,
                    lastError = "Bluetooth is turned off."
                )
            }
            return
        }

        stopScanInternal()
        connectionJob?.cancel()
        closeActiveGatt()

        val existingSaved = _uiState.value.savedRadio
        val updatedSaved = SavedBleRadio(
            address = address,
            name = deviceName,
            shortName = if (existingSaved?.address == address) existingSaved.shortName else null
        )
        persistSavedRadio(updatedSaved)

        shouldStayConnected = true
        _uiState.update { state ->
            state.copy(
                status = if (isLaunchAutoConnect) BleConnectionStatus.RECONNECTING else BleConnectionStatus.CONNECTING,
                isScanning = false,
                connectedAddress = address,
                connectedDeviceName = deviceName,
                connectedShortName = updatedSaved.shortName,
                savedRadio = updatedSaved,
                isAutoConnecting = isLaunchAutoConnect,
                autoConnectTimedOut = false,
                reconnectAttempt = 0,
                lastError = null
            )
        }

        connectionJob = scope.launch {
            runConnectionLifecycle(
                address = address,
                deviceName = deviceName,
                isLaunchAutoConnect = isLaunchAutoConnect
            )
        }
    }

    private suspend fun runConnectionLifecycle(
        address: String,
        deviceName: String,
        isLaunchAutoConnect: Boolean
    ) {
        val backoffSchedule = RECONNECT_BACKOFF_MS
        var retryIndex = 0
        var everConnected = false

        try {
            while (scope.isActive && shouldStayConnected) {
                val connectTimeoutMs = if (isLaunchAutoConnect && !everConnected) {
                    LAUNCH_CONNECT_TIMEOUT_MS
                } else {
                    CONNECT_TIMEOUT_MS
                }
                val sessionSucceeded = runSingleGattSession(
                    address = address,
                    deviceName = deviceName,
                    connectTimeoutMs = connectTimeoutMs
                ) {
                    everConnected = true
                    retryIndex = 0
                }

                closeActiveGatt()
                if (!shouldStayConnected) break

                if (retryIndex >= backoffSchedule.size) {
                    shouldStayConnected = false
                    _uiState.update { state ->
                        state.copy(
                            status = BleConnectionStatus.OFFLINE,
                            connectedAddress = null,
                            connectedDeviceName = null,
                            connectedShortName = null,
                            myNodeNum = null,
                            isAutoConnecting = false,
                            autoConnectTimedOut = true,
                            reconnectAttempt = 0,
                            lastError = if (isLaunchAutoConnect && !everConnected) {
                                null
                            } else {
                                "Radio link timed out. Tap Reconnect when your radio is back in range."
                            }
                        )
                    }
                    break
                }

                val waitMs = backoffSchedule[retryIndex]
                retryIndex++
                _uiState.update { state ->
                    state.copy(
                        status = BleConnectionStatus.RECONNECTING,
                        reconnectAttempt = retryIndex,
                        lastError = if (!sessionSucceeded && !isLaunchAutoConnect) {
                            "BLE link interrupted. Retrying in ${waitMs / 1000L}s…"
                        } else {
                            state.lastError
                        }
                    )
                }
                delay(waitMs)
            }
        } catch (ce: CancellationException) {
            closeActiveGatt()
            throw ce
        } finally {
            closeActiveGatt()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runSingleGattSession(
        address: String,
        deviceName: String,
        connectTimeoutMs: Long,
        onHandshakeComplete: () -> Unit
    ): Boolean {
        val ctx = appContext ?: return false
        val adapter = bluetoothAdapter() ?: return false
        if (!adapter.isEnabled) return false

        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false

        connectDeferred = CompletableDeferred()
        disconnectSignal = CompletableDeferred()
        fromNumChannel = Channel(Channel.CONFLATED)

        val callback = createGattCallback()
        val gatt = runCatching {
            device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull() ?: return false
        activeGatt = gatt

        val connected = withTimeoutOrNull(connectTimeoutMs) {
            connectDeferred?.await() == true
        } == true
        if (!connected) return false

        // Request 512-byte MTU so larger NodeInfo/MeshPacket protobufs fit cleanly
        gattOpMutex.withLock {
            val deferred = CompletableDeferred<Int>()
            mtuDeferred = deferred
            if (gatt.requestMtu(REQUESTED_MTU)) {
                withTimeoutOrNull(GATT_OP_TIMEOUT_MS) { deferred.await() }
            }
            mtuDeferred = null
        }

        // Discover Meshtastic PhoneAPI GATT service & characteristics
        val discovered = gattOpMutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            servicesDeferred = deferred
            if (gatt.discoverServices()) {
                withTimeoutOrNull(SERVICE_DISCOVERY_TIMEOUT_MS) { deferred.await() } == true
            } else {
                false
            }.also { servicesDeferred = null }
        }
        if (!discovered) {
            _uiState.update { it.copy(lastError = "Failed to discover Meshtastic GATT service.") }
            return false
        }

        val service = gatt.getService(MESHTASTIC_SERVICE_UUID)
        val toRadioChar = service?.getCharacteristic(TO_RADIO_CHARACTERISTIC_UUID)
        val fromRadioChar = service?.getCharacteristic(FROM_RADIO_CHARACTERISTIC_UUID)
        val fromNumChar = service?.getCharacteristic(FROM_NUM_CHARACTERISTIC_UUID)
        if (service == null || toRadioChar == null || fromRadioChar == null || fromNumChar == null) {
            _uiState.update {
                it.copy(lastError = "$deviceName does not expose Meshtastic BLE PhoneAPI characteristics.")
            }
            return false
        }

        // Enable notifications on FromNum
        enableFromNumNotifications(gatt, fromNumChar)

        // Transition to Syncing & request full NodeDB handshake via want_config_id
        _uiState.update { state ->
            state.copy(
                status = BleConnectionStatus.SYNCING,
                connectedAddress = address,
                connectedDeviceName = deviceName,
                isAutoConnecting = false,
                lastError = null
            )
        }

        val wantConfigId = Random.nextInt(1, Int.MAX_VALUE)
        val wantConfigBytes = MeshtasticBleParser.buildWantConfigPacket(wantConfigId)
        val wroteWantConfig = writeCharacteristicBytes(gatt, toRadioChar, wantConfigBytes)
        if (!wroteWantConfig) {
            _uiState.update { it.copy(lastError = "Failed to send want_config handshake to $deviceName.") }
            return false
        }

        // Drain initial NodeDB handshake from FromRadio until empty
        val syncedConfig = drainFromRadioLoop(
            gatt = gatt,
            fromRadioChar = fromRadioChar,
            expectedConfigId = wantConfigId
        )

        onHandshakeComplete()
        _uiState.update { state ->
            state.copy(
                status = BleConnectionStatus.CONNECTED,
                isAutoConnecting = false,
                autoConnectTimedOut = false,
                reconnectAttempt = 0,
                lastError = null
            )
        }

        // Keep draining whenever FromNum signals newly queued packets, until link drops or cancelled
        val drop = disconnectSignal ?: return syncedConfig
        while (scope.isActive && shouldStayConnected && !drop.isCompleted) {
            val notified = withTimeoutOrNull(IDLE_POLL_CHECK_MS) {
                fromNumChannel.receive()
                true
            } ?: false
            if (drop.isCompleted || !shouldStayConnected) break
            if (notified) {
                drainFromRadioLoop(
                    gatt = gatt,
                    fromRadioChar = fromRadioChar,
                    expectedConfigId = null
                )
            }
        }
        return true
    }

    private suspend fun drainFromRadioLoop(
        gatt: BluetoothGatt,
        fromRadioChar: BluetoothGattCharacteristic,
        expectedConfigId: Int?
    ): Boolean {
        val pendingBatch = mutableListOf<LiveTelemetryUpdate>()
        var sawConfigComplete = false
        var framesRead = 0
        var nodesIncrement = 0
        var packetsIncrement = 0

        while (scope.isActive && shouldStayConnected && framesRead < MAX_DRAIN_FRAMES_PER_BURST) {
            if (disconnectSignal?.isCompleted == true) break
            val bytes = readCharacteristicBytes(gatt, fromRadioChar)
            if (bytes == null || bytes.isEmpty()) break
            framesRead++

            val currentMyNodeNum = _uiState.value.myNodeNum
            val parsed = withContext(Dispatchers.Default) {
                MeshtasticBleParser.parseFromRadio(
                    bytes = bytes,
                    myNodeNum = currentMyNodeNum
                )
            }

            when (parsed) {
                is ParsedFromRadioEvent.MyNodeInfoEvent -> {
                    _uiState.update { it.copy(myNodeNum = parsed.myNodeNum) }
                }

                is ParsedFromRadioEvent.ConfigCompleteEvent -> {
                    if (expectedConfigId == null || parsed.configId == expectedConfigId) {
                        sawConfigComplete = true
                    }
                }

                is ParsedFromRadioEvent.TelemetryBatchEvent -> {
                    pendingBatch.addAll(parsed.updates)
                    if (parsed.isNodeInfoRecord) {
                        nodesIncrement += parsed.updates.size
                    } else {
                        packetsIncrement++
                    }
                    parsed.selfShortName?.let { shortName ->
                        updateConnectedShortName(shortName)
                    }
                    if (pendingBatch.size >= HANDSHAKE_BATCH_SIZE) {
                        flushTelemetryBatch(pendingBatch.toList(), nodesIncrement, packetsIncrement)
                        pendingBatch.clear()
                        nodesIncrement = 0
                        packetsIncrement = 0
                    }
                }

                ParsedFromRadioEvent.Ignored -> Unit
            }
        }

        if (pendingBatch.isNotEmpty() || nodesIncrement > 0 || packetsIncrement > 0) {
            flushTelemetryBatch(pendingBatch.toList(), nodesIncrement, packetsIncrement)
        }
        return sawConfigComplete
    }

    private suspend fun flushTelemetryBatch(
        batch: List<LiveTelemetryUpdate>,
        nodesIncrement: Int,
        packetsIncrement: Int
    ) {
        if (batch.isNotEmpty()) {
            _incomingUpdates.emit(batch)
        }
        val nowSec = System.currentTimeMillis() / 1000L
        _uiState.update { state ->
            state.copy(
                nodesSyncedCount = state.nodesSyncedCount + nodesIncrement,
                packetsReceivedCount = state.packetsReceivedCount + packetsIncrement + (if (nodesIncrement > 0) 1 else 0),
                lastPacketEpochSeconds = nowSec
            )
        }
    }

    private fun updateConnectedShortName(shortName: String) {
        val clean = shortName.trim()
        if (clean.isEmpty()) return
        _uiState.update { state ->
            val updatedSaved = state.savedRadio?.let { saved ->
                if (saved.address == state.connectedAddress) saved.copy(shortName = clean) else saved
            }
            if (updatedSaved != null) {
                persistSavedRadio(updatedSaved)
            }
            state.copy(
                connectedShortName = clean,
                savedRadio = updatedSaved ?: state.savedRadio
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableFromNumNotifications(
        gatt: BluetoothGatt,
        fromNumChar: BluetoothGattCharacteristic
    ): Boolean = gattOpMutex.withLock {
        gatt.setCharacteristicNotification(fromNumChar, true)
        val cccd = fromNumChar.getDescriptor(CCCD_DESCRIPTOR_UUID) ?: return@withLock true
        val deferred = CompletableDeferred<Boolean>()
        descriptorWriteDeferred = deferred
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
        val result = if (started) {
            withTimeoutOrNull(GATT_OP_TIMEOUT_MS) { deferred.await() } == true
        } else {
            false
        }
        descriptorWriteDeferred = null
        result
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristicBytes(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ): Boolean = gattOpMutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        characteristicWriteDeferred = deferred
        val writeType = if (
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, payload, writeType) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        val ok = if (started) {
            withTimeoutOrNull(GATT_OP_TIMEOUT_MS) { deferred.await() } == true
        } else {
            false
        }
        characteristicWriteDeferred = null
        ok
    }

    @SuppressLint("MissingPermission")
    private suspend fun readCharacteristicBytes(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): ByteArray? = gattOpMutex.withLock {
        val deferred = CompletableDeferred<ByteArray?>()
        characteristicReadDeferred = deferred
        val started = runCatching { gatt.readCharacteristic(characteristic) }.getOrDefault(false)
        val result = if (started) {
            withTimeoutOrNull(GATT_OP_TIMEOUT_MS) { deferred.await() }
        } else {
            null
        }
        characteristicReadDeferred = null
        result
    }

    private fun createGattCallback(): BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connectDeferred?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connectDeferred?.complete(false)
                servicesDeferred?.complete(false)
                mtuDeferred?.complete(23)
                descriptorWriteDeferred?.complete(false)
                characteristicWriteDeferred?.complete(false)
                characteristicReadDeferred?.complete(null)
                disconnectSignal?.complete(Unit)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuDeferred?.complete(mtu)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorWriteDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            characteristicWriteDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
                characteristicReadDeferred?.complete(value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            characteristicReadDeferred?.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) value else null
            )
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == FROM_NUM_CHARACTERISTIC_UUID
            ) {
                fromNumChannel.trySend(Unit)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == FROM_NUM_CHARACTERISTIC_UUID) {
                fromNumChannel.trySend(Unit)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeActiveGatt() {
        val gatt = activeGatt
        activeGatt = null
        connectDeferred?.complete(false)
        servicesDeferred?.complete(false)
        descriptorWriteDeferred?.complete(false)
        characteristicWriteDeferred?.complete(false)
        characteristicReadDeferred?.complete(null)
        disconnectSignal?.complete(Unit)
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private fun persistSavedRadio(radio: SavedBleRadio) {
        prefs?.edit()?.apply {
            putString(KEY_SAVED_ADDRESS, radio.address)
            putString(KEY_SAVED_NAME, radio.name)
            putString(KEY_SAVED_SHORT_NAME, radio.shortName)
            apply()
        }
    }

    private fun loadSavedRadio(): SavedBleRadio? {
        val p = prefs ?: return null
        val address = p.getString(KEY_SAVED_ADDRESS, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val name = p.getString(KEY_SAVED_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Meshtastic Radio"
        val shortName = p.getString(KEY_SAVED_SHORT_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }
        return SavedBleRadio(address = address, name = name, shortName = shortName)
    }

    companion object {
        val MESHTASTIC_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM_CHARACTERISTIC_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa75473eaf53")
        val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val RECONNECT_BACKOFF_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L)

        private const val REQUESTED_MTU = 512
        private const val SCAN_DURATION_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val LAUNCH_CONNECT_TIMEOUT_MS = 4_500L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 8_000L
        private const val GATT_OP_TIMEOUT_MS = 4_500L
        private const val IDLE_POLL_CHECK_MS = 15_000L
        private const val HANDSHAKE_BATCH_SIZE = 12
        private const val MAX_DRAIN_FRAMES_PER_BURST = 2_000

        private const val PREFS_NAME = "lemesh_ble_sync_prefs"
        private const val KEY_SAVED_ADDRESS = "saved_ble_address"
        private const val KEY_SAVED_NAME = "saved_ble_name"
        private const val KEY_SAVED_SHORT_NAME = "saved_ble_short_name"

        fun requiredBlePermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }

        fun hasBlePermissions(context: Context): Boolean =
            requiredBlePermissions().all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
    }
}
