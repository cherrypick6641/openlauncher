package com.openlauncher.app.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.openlauncher.app.model.ObdState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class ObdManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var workerJob: Job? = null

    private val _obdState = MutableStateFlow(ObdState())
    val obdState: StateFlow<ObdState> = _obdState

    private var currentAddress: String = ""
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val RETRY_INTERVAL_MS = 120_000L // 2 minutes

        // ── Response Parsing Helpers (Tested via Unit Tests) ─────────────────

        /**
         * Standard OBD2 PID 0105: Coolant Temp = A - 40 (°C)
         */
        fun parseCoolantTempResponse(response: String): Double? {
            val bytes = extractHexBytes(response, "41 05") ?: extractHexBytes(response, "4105") ?: return null
            if (bytes.isEmpty()) return null
            val rawA = bytes[0]
            return (rawA - 40).toDouble().coerceIn(-40.0, 150.0)
        }

        /**
         * Mode 22 PID 221105 or 221101: DPF Temperature Before DPF (°C)
         * Equation: ((A * 256) + B) / 10.0 - 40.0  or  ((A * 256) + B) / 10.0
         */
        fun parseDpfTempResponse(response: String): Double? {
            val bytes = extractHexBytes(response, "62 11 05")
                ?: extractHexBytes(response, "621105")
                ?: extractHexBytes(response, "62 11 01")
                ?: extractHexBytes(response, "621101")
                ?: return null
            if (bytes.size < 2) return null
            val raw = (bytes[0] shl 8) or bytes[1]
            val temp = if (raw > 4000) (raw / 10.0) - 40.0 else raw / 10.0
            return temp.coerceIn(0.0, 1000.0)
        }

        /**
         * Mode 22 PID 221101: Soot Accumulation Level
         * For Kia 1.6 CRDi EDC17 ECU:
         * Byte A represents DPF Soot Accumulation in Grams (g) (where 25.0g = 100% capacity).
         * Returns Pair(grams, percent)
         */
        fun parseSootGramsAndPercent(response: String): Pair<Double, Double>? {
            val bytes = extractHexBytes(response, "62 11 01") ?: extractHexBytes(response, "621101") ?: return null
            if (bytes.isEmpty()) return null
            val rawByte = bytes.getOrNull(2) ?: bytes[0]

            val grams = if (rawByte <= 35) rawByte.toDouble() else (rawByte / 255.0) * 25.0
            val percent = (grams / 25.0) * 100.0

            return grams.coerceIn(0.0, 35.0) to percent.coerceIn(0.0, 100.0)
        }

        fun parseSootLevelResponse(response: String): Double? {
            return parseSootGramsAndPercent(response)?.second
        }

        /**
         * Mode 22 PID 221102 or 221101: Distance Since Last DPF Regeneration (km)
         * Equation: (A * 256) + B
         */
        fun parseDistanceResponse(response: String): Int? {
            val bytes = extractHexBytes(response, "62 11 02")
                ?: extractHexBytes(response, "621102")
                ?: extractHexBytes(response, "62 11 01")
                ?: extractHexBytes(response, "621101")
                ?: return null
            if (bytes.size < 2) return null
            val dist = (bytes[0] shl 8) or bytes[1]
            return dist.coerceIn(0, 100000)
        }

        private fun extractHexBytes(rawResponse: String, header: String): List<Int>? {
            val clean = rawResponse.replace(">", "").replace("\r", " ").replace("\n", " ").trim()
            val headerClean = header.replace(" ", "")
            val cleanNoSpaces = clean.replace(" ", "")

            val index = cleanNoSpaces.indexOf(headerClean)
            if (index < 0) return null

            val afterHeader = cleanNoSpaces.substring(index + headerClean.length)
            val hexPairs = afterHeader.chunked(2).takeWhile { it.length == 2 && it.all { c -> c.isLetterOrDigit() } }
            if (hexPairs.isEmpty()) return null

            return hexPairs.mapNotNull { it.toIntOrNull(16) }
        }
    }

    fun start(deviceAddress: String) {
        if (deviceAddress.isEmpty()) {
            stop()
            return
        }
        if (currentAddress == deviceAddress && workerJob?.isActive == true) return

        currentAddress = deviceAddress
        workerJob?.cancel()
        workerJob = scope.launch {
            connectionLoop(deviceAddress)
        }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        currentAddress = ""
        disconnectSocket()
        _obdState.value = ObdState(isConnected = false, isConnecting = false, statusMessage = "DISABLED")
    }

    private suspend fun connectionLoop(address: String) {
        while (scope.isActive) {
            _obdState.value = _obdState.value.copy(
                isConnecting = true,
                isConnected = false,
                statusMessage = "CONNECTING..."
            )

            val success = connectAndPoll(address)
            disconnectSocket()

            if (!success) {
                _obdState.value = ObdState(
                    isConnected = false,
                    isConnecting = false,
                    statusMessage = "NO OBD CONNECTION"
                )
                FileLogger.log(context, "OBD: Connection failed/lost. Retrying in 2 minutes...")
                delay(RETRY_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndPoll(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext false
            if (!adapter.isEnabled) return@withContext false

            val device = adapter.getRemoteDevice(address) ?: return@withContext false
            FileLogger.log(context, "OBD: Connecting to Bluetooth SPP: ${device.name} ($address)")

            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()

            inputStream = socket?.inputStream
            outputStream = socket?.outputStream

            if (inputStream == null || outputStream == null) return@withContext false

            // ELM327 Initialization Commands for Kia Ceed JD
            sendCmd("AT Z\r")    // Reset
            delay(1000)
            sendCmd("AT E0\r")   // Echo Off
            delay(200)
            sendCmd("AT L0\r")   // Linefeeds Off
            delay(200)
            sendCmd("AT SP 6\r") // ISO 15765-4 CAN (11 bit ID, 500 kbaud) - Kia Ceed
            delay(200)
            sendCmd("AT SH 7E0\r") // Set Header 7E0
            delay(200)

            FileLogger.log(context, "OBD: Connected & initialized successfully!")
            _obdState.value = _obdState.value.copy(
                isConnected = true,
                isConnecting = false,
                statusMessage = "CONNECTED"
            )

            // Polling Loop
            while (scope.isActive) {
                // 1. Coolant Temp (0105)
                val coolantResp = sendCmd("01 05\r")
                val coolant = parseCoolantTempResponse(coolantResp)

                // 2. DPF Temp Before DPF (221105 or 221101)
                val dpfTempResp = sendCmd("22 11 05\r")
                val dpfTemp = parseDpfTempResponse(dpfTempResp)

                // 3. Soot Level & Grams (221101)
                val sootResp = sendCmd("22 11 01\r")
                val sootPair = parseSootGramsAndPercent(sootResp) ?: parseSootGramsAndPercent(dpfTempResp)

                // 4. Distance since last regen (221102)
                val distResp = sendCmd("22 11 02\r")
                val dist = parseDistanceResponse(distResp) ?: parseDistanceResponse(sootResp)

                val updatedState = _obdState.value.copy(
                    isConnected = true,
                    isConnecting = false,
                    coolantTemperatureCelsius = coolant ?: _obdState.value.coolantTemperatureCelsius,
                    dpfTemperatureCelsius = dpfTemp ?: _obdState.value.dpfTemperatureCelsius,
                    sootGrams = sootPair?.first ?: _obdState.value.sootGrams,
                    sootLevelPercent = sootPair?.second ?: _obdState.value.sootLevelPercent,
                    distanceSinceRegenKm = dist ?: _obdState.value.distanceSinceRegenKm,
                    statusMessage = "CONNECTED",
                    lastUpdatedMs = System.currentTimeMillis()
                )
                _obdState.value = updatedState

                delay(1500) // Poll every 1.5s
            }
            true
        } catch (e: Exception) {
            FileLogger.log(context, "OBD Error: ${e.message}")
            false
        }
    }

    private fun sendCmd(cmd: String): String {
        return try {
            outputStream?.write(cmd.toByteArray())
            outputStream?.flush()

            val buffer = ByteArray(256)
            val sb = StringBuilder()
            var bytesRead: Int
            val startMs = System.currentTimeMillis()

            while (System.currentTimeMillis() - startMs < 1200) {
                if ((inputStream?.available() ?: 0) > 0) {
                    bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        val str = String(buffer, 0, bytesRead)
                        sb.append(str)
                        if (sb.contains(">")) break
                    }
                } else {
                    Thread.sleep(50)
                }
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun disconnectSocket() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
    }
}
