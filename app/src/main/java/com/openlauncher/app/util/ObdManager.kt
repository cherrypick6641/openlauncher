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

/**
 * OBD2 Manager optimized for Kia Ceed JD 1.6 CRDi (128 HP, Bosch EDC17 ECU).
 * Reads Coolant Temp, DPF Temp, Soot Accumulation (grams & %), and Distance Since Last Regeneration.
 */
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

        // Nominal DPF Capacity threshold in grams for Kia 1.6 CRDi (EDC17 ECU)
        // Active regeneration starts around 14-16g; 18g represents ~100% capacity threshold.
        const val DPF_MAX_SOOT_GRAMS = 18.0

        // ── Response Parsing Helpers ─────────────────────────────────────────

        /**
         * Standard OBD2 PID 0105: Coolant Temp = A - 40 (°C)
         * Expected Header Response: "41 05"
         */
        fun parseCoolantTempResponse(response: String): Double? {
            val bytes = extractHexBytes(response, "41 05") ?: extractHexBytes(response, "4105") ?: return null
            if (bytes.isEmpty()) return null
            val rawA = bytes[0]
            return (rawA - 40).toDouble().coerceIn(-40.0, 150.0)
        }

        /**
         * Parses DPF Temperature before DPF (°C).
         * Supports:
         * - Mode 22 PID 22 11 05 / 22 21 01 (Header "62 11 05" or "62 21 01")
         * - Mode 01 PID 01 78 (Exhaust Gas Temp, Header "41 78")
         * Formula: ((A * 256) + B) / 10.0 - 40.0
         * Uses strict fixed byte offset rather than arbitrary range searching.
         */
        fun parseDpfTempResponse(response: String): Double? {
            // Check PID 221105
            var bytes = extractHexBytes(response, "62 11 05") ?: extractHexBytes(response, "621105")
            if (bytes != null && bytes.size >= 2) {
                val raw = (bytes[0] shl 8) or bytes[1]
                val temp = (raw / 10.0) - 40.0
                return temp.coerceIn(0.0, 1000.0)
            }

            // Check Mode 01 PID 0178
            bytes = extractHexBytes(response, "41 78") ?: extractHexBytes(response, "4178")
            if (bytes != null && bytes.size >= 2) {
                val raw = (bytes[0] shl 8) or bytes[1]
                val temp = (raw / 10.0) - 40.0
                return temp.coerceIn(0.0, 1000.0)
            }

            // Check PID 222101 / 221101 (DPF Temp is at fixed offset bytes [2] and [3] after header if present)
            bytes = extractHexBytes(response, "62 21 01") ?: extractHexBytes(response, "622101")
                ?: extractHexBytes(response, "62 11 01") ?: extractHexBytes(response, "621101")
            if (bytes != null && bytes.size >= 4) {
                val raw = (bytes[2] shl 8) or bytes[3]
                val temp = (raw / 10.0) - 40.0
                if (temp in 0.0..1000.0) return temp
            }

            return null
        }

        /**
         * Parses DPF Soot Accumulation (grams and percent) for Kia 1.6 CRDi EDC17 ECU.
         * Mode 22 PID 22 21 01 or 22 11 01.
         * Reads soot mass from exact byte offset (index 0 for 221101, or index 1 for 222101).
         * Calculates percentage based on realistic 18.0g 100% capacity threshold.
         * Returns Pair(grams, percent)
         */
        fun parseSootGramsAndPercent(response: String): Pair<Double, Double>? {
            val bytes = extractHexBytes(response, "62 11 01") ?: extractHexBytes(response, "621101")
                ?: extractHexBytes(response, "62 21 01") ?: extractHexBytes(response, "622101")
                ?: return null
            if (bytes.isEmpty()) return null

            // Read soot value from offset index 2 (or index 1/0)
            val rawByte = bytes.getOrNull(2) ?: bytes.getOrNull(1) ?: bytes[0]
            val grams = rawByte.toDouble().coerceIn(0.0, 40.0)
            val percent = ((grams / DPF_MAX_SOOT_GRAMS) * 100.0).coerceIn(0.0, 100.0)

            return grams to percent
        }

        fun parseSootLevelResponse(response: String): Double? {
            return parseSootGramsAndPercent(response)?.second
        }

        /**
         * Mode 22 PID 22 11 02, 22 21 01, or 22 11 01: Distance Since Last DPF Regeneration (km).
         * Formula: (A * 256) + B at fixed byte index.
         */
        fun parseDistanceResponse(response: String): Int? {
            // DEDICATED PID 221102
            var bytes = extractHexBytes(response, "62 11 02") ?: extractHexBytes(response, "621102")
            if (bytes != null && bytes.size >= 2) {
                val dist = (bytes[0] shl 8) or bytes[1]
                return dist.coerceIn(0, 100000)
            }

            // MULTI-PARAM PID 222101 / 221101 (Distance fixed offset at bytes [4] and [5] after header)
            bytes = extractHexBytes(response, "62 21 01") ?: extractHexBytes(response, "622101")
                ?: extractHexBytes(response, "62 11 01") ?: extractHexBytes(response, "621101")
            if (bytes != null && bytes.size >= 6) {
                val dist = (bytes[4] shl 8) or bytes[5]
                return dist.coerceIn(0, 100000)
            }

            return null
        }

        /**
         * Cleans response string and extracts hex payload bytes following the specified header.
         */
        private fun extractHexBytes(rawResponse: String, header: String): List<Int>? {
            val clean = rawResponse
                .replace("SEARCHING...", "")
                .replace("BUS INIT", "")
                .replace("UNABLE TO CONNECT", "")
                .replace("CAN ERROR", "")
                .replace("NO DATA", "")
                .replace(">", "")
                .replace("\r", " ")
                .replace("\n", " ")
                .uppercase()
                .trim()

            // Remove whitespace for exact substring searching
            val cleanedStr = clean.replace(" ", "")
            val headerClean = header.replace(" ", "").uppercase()

            val index = cleanedStr.indexOf(headerClean)
            if (index < 0) return null

            val afterHeader = cleanedStr.substring(index + headerClean.length)
            val hexPairs = afterHeader.chunked(2).takeWhile { it.length == 2 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
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
        reconnect()
    }

    fun reconnect() {
        if (currentAddress.isEmpty()) return
        workerJob?.cancel()
        workerJob = scope.launch {
            connectionLoop(currentAddress)
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
        var consecutiveFailures = 0

        while (scope.isActive) {
            _obdState.value = _obdState.value.copy(
                isConnecting = true,
                isConnected = false,
                statusMessage = "CONNECTING..."
            )

            val success = connectAndPoll(address)
            disconnectSocket()

            if (success) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                _obdState.value = ObdState(
                    isConnected = false,
                    isConnecting = false,
                    statusMessage = "NO OBD CONNECTION"
                )

                val retryDelay = when (consecutiveFailures) {
                    1 -> 3_000L
                    2 -> 5_000L
                    3 -> 10_000L
                    else -> 120_000L
                }

                FileLogger.log(context, "OBD: Connection failed (attempt #$consecutiveFailures). Retrying in ${retryDelay / 1000}s...")
                delay(retryDelay)
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

            // ELM327 Initialization Commands for Kia Ceed JD 1.6 CRDi (128 hp)
            sendCmd("AT Z\r")           // Reset ELM327
            delay(1000)
            sendCmd("AT E0\r")          // Echo Off
            delay(150)
            sendCmd("AT L0\r")          // Linefeeds Off
            delay(150)
            sendCmd("AT H0\r")          // Headers OFF (Strips CAN ID 7E8 to keep byte offset indices stable)
            delay(150)
            sendCmd("AT SP 6\r")        // ISO 15765-4 CAN (11 bit ID, 500 kbaud)
            delay(150)
            sendCmd("AT SH 7E0\r")      // CAN Header 7E0 (Engine ECM)
            delay(150)
            sendCmd("AT CRA 7E8\r")     // Receive CAN Header 7E8
            delay(150)

            FileLogger.log(context, "OBD: Connected & Initialized for Kia Ceed 1.6 CRDi!")
            _obdState.value = _obdState.value.copy(
                isConnected = true,
                isConnecting = false,
                statusMessage = "CONNECTED"
            )

            // Polling Loop with delay between queries to prevent ELM buffer overflow
            while (scope.isActive) {
                // 1. Coolant Temp (0105)
                val coolantResp = sendCmd("01 05\r")
                delay(100)
                val coolant = parseCoolantTempResponse(coolantResp)

                // 2. Query Primary Hyundai/Kia Mode 22 PIDs
                val resp221105 = sendCmd("22 11 05\r")
                delay(100)

                val resp221101 = sendCmd("22 11 01\r")
                delay(100)

                val resp221102 = sendCmd("22 11 02\r")
                delay(100)

                // Parse DPF Temp
                val dpfTemp = parseDpfTempResponse(resp221105)
                    ?: parseDpfTempResponse(resp221101)

                // Parse Soot Mass & Percent
                val sootPair = parseSootGramsAndPercent(resp221101)

                // Parse Distance Since Last Regen
                val dist = parseDistanceResponse(resp221102)
                    ?: parseDistanceResponse(resp221101)

                FileLogger.log(context, "OBD Stats -> Coolant:$coolant, DPF Temp:$dpfTemp, Soot:${sootPair?.first}g (${sootPair?.second}%), Dist:${dist}km")

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

                delay(1500) // Poll cycle delay
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
