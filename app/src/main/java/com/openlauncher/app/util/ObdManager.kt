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
 * Queries Mode 21 (2194 / 2103) & Mode 01 (0105 / 0178) PIDs matching Car Scanner profile.
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
        const val DPF_MAX_SOOT_GRAMS = 18.0

        data class ParsedMetrics(
            val coolantTemp: Double? = null,
            val dpfTemp: Double? = null,
            val sootGrams: Double? = null,
            val sootPercent: Double? = null,
            val distanceKm: Int? = null
        )

        // ── Response Parsing Helpers ─────────────────────────────────────────

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
         * Mode 21 PID 2194 parser for Kia Ceed JD 1.6 CRDi (128 HP EDC17)
         * Response Header: "61 94"
         * Bytes 2, 3: DPF Temperature ((A*256+B)/10 - 40)
         * Byte 4 or 5: Coolant Temp (A - 40)
         * Bytes 6, 7: Soot Accumulation Mass ((A*256+B)/10) in grams
         * Bytes 8, 9: Distance Since Last Regeneration ((A*256+B)/10) in km
         */
        fun parse2194Response(response: String): ParsedMetrics? {
            val bytes = extractHexBytes(response, "61 94") ?: extractHexBytes(response, "6194") ?: return null
            if (bytes.size < 4) return null

            // Bytes 2, 3: DPF Temp
            val dpfRaw = (bytes[2] shl 8) or bytes[3]
            val dpfTemp = if (dpfRaw > 0) ((dpfRaw / 10.0) - 40.0).coerceIn(0.0, 1000.0) else null

            // Coolant index: handles ISO-TP frame indicator 0x21 at byte 4 if present
            val coolantIndex = if (bytes.size > 5 && bytes[4] == 0x21) 5 else 4
            val coolantRaw = bytes.getOrNull(coolantIndex)
            val coolant = coolantRaw?.let { (it - 40).toDouble().coerceIn(-40.0, 150.0) }

            // Soot Mass (Grams & %)
            val sootIndex = if (coolantIndex == 5) 6 else 5
            val sootRaw = if (bytes.size >= sootIndex + 2) (bytes[sootIndex] shl 8) or bytes[sootIndex + 1] else 0
            val sootGrams = if (sootRaw > 0) (sootRaw / 10.0).coerceIn(0.0, 40.0) else null
            val sootPercent = sootGrams?.let { ((it / DPF_MAX_SOOT_GRAMS) * 100.0).coerceIn(0.0, 100.0) }

            // Distance
            val distIndex = sootIndex + 2
            val distRaw = if (bytes.size >= distIndex + 2) (bytes[distIndex] shl 8) or bytes[distIndex + 1] else 0
            val distKm = if (distRaw > 0) (distRaw / 10).coerceIn(0, 100000) else null

            return ParsedMetrics(coolant, dpfTemp, sootGrams, sootPercent, distKm)
        }

        /**
         * Parses DPF Temperature before DPF (°C).
         */
        fun parseDpfTempResponse(response: String): Double? {
            // Check Mode 21 PID 2194
            val m21 = parse2194Response(response)
            if (m21?.dpfTemp != null) return m21.dpfTemp

            // Check Mode 22 PID 221105
            var bytes = extractHexBytes(response, "62 11 05") ?: extractHexBytes(response, "621105")
            if (bytes != null && bytes.size >= 2) {
                val raw = (bytes[0] shl 8) or bytes[1]
                val temp = (raw / 10.0) - 40.0
                return temp.coerceIn(0.0, 1000.0)
            }

            // Check Mode 21 PID 2103
            bytes = extractHexBytes(response, "61 03") ?: extractHexBytes(response, "6103")
            if (bytes != null && bytes.size >= 4) {
                for (i in 0 until bytes.size - 1) {
                    val raw = (bytes[i] shl 8) or bytes[i + 1]
                    val temp = (raw / 10.0) - 40.0
                    if (temp in 30.0..950.0) return temp
                }
            }

            // Check Standard OBD2 PID 0178
            bytes = extractHexBytes(response, "41 78") ?: extractHexBytes(response, "4178")
            if (bytes != null && bytes.size >= 2) {
                val startIndex = if (bytes.size >= 3) 1 else 0
                for (i in startIndex until bytes.size - 1 step 2) {
                    val raw = (bytes[i] shl 8) or bytes[i + 1]
                    val temp = (raw / 10.0) - 40.0
                    if (temp in 30.0..950.0) return temp
                }
            }

            return null
        }

        /**
         * Parses DPF Soot Accumulation (grams and percent) for Kia 1.6 CRDi EDC17 ECU.
         */
        fun parseSootGramsAndPercent(response: String): Pair<Double, Double>? {
            val m21 = parse2194Response(response)
            if (m21?.sootGrams != null && m21.sootPercent != null) {
                return m21.sootGrams to m21.sootPercent
            }

            val bytes = extractHexBytes(response, "61 03") ?: extractHexBytes(response, "6103")
                ?: extractHexBytes(response, "62 11 01") ?: extractHexBytes(response, "621101")
                ?: return null
            if (bytes.isEmpty()) return null

            val rawByte = bytes.getOrNull(2) ?: bytes.getOrNull(1) ?: bytes[0]
            val grams = rawByte.toDouble().coerceIn(0.0, 40.0)
            val percent = ((grams / DPF_MAX_SOOT_GRAMS) * 100.0).coerceIn(0.0, 100.0)

            return grams to percent
        }

        fun parseSootLevelResponse(response: String): Double? {
            return parseSootGramsAndPercent(response)?.second
        }

        /**
         * Parses Distance Since Last DPF Regeneration (km).
         */
        fun parseDistanceResponse(response: String): Int? {
            val m21 = parse2194Response(response)
            if (m21?.distanceKm != null) return m21.distanceKm

            val bytes = extractHexBytes(response, "61 03") ?: extractHexBytes(response, "6103")
                ?: extractHexBytes(response, "62 11 02") ?: extractHexBytes(response, "621102")
                ?: return null
            if (bytes.size < 2) return null

            val dist = (bytes[0] shl 8) or bytes[1]
            return dist.coerceIn(0, 100000)
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

            val cleanedStr = clean
                .replace(Regex("7E[0-9A-F]\\s*"), "")
                .replace(" ", "")

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

            // ELM327 Initialization Commands for Kia Ceed JD 1.6 CRDi (Car Scanner sequence)
            sendCmd("AT Z\r")           // Reset ELM327
            delay(1000)
            sendCmd("AT E0\r")          // Echo Off
            delay(150)
            sendCmd("AT L0\r")          // Linefeeds Off
            delay(150)
            sendCmd("AT H0\r")          // Headers OFF
            delay(150)
            sendCmd("AT CAF1\r")        // CAN Automatic Formatting ON
            delay(150)
            sendCmd("AT SP 6\r")        // ISO 15765-4 CAN (11 bit ID, 500 kbaud)
            delay(150)
            sendCmd("AT SH 7E0\r")      // CAN Header 7E0 (Engine ECM)
            delay(150)

            FileLogger.log(context, "OBD: Connected & Initialized matching Car Scanner profile!")
            _obdState.value = _obdState.value.copy(
                isConnected = true,
                isConnecting = false,
                statusMessage = "CONNECTED"
            )

            // Polling Loop (Car Scanner sequence)
            while (scope.isActive) {
                // 1. Primary Car Scanner Mode 21 PID for Kia Ceed 1.6 CRDi: 21 94
                val resp2194 = sendCmd("21 94\r")
                delay(100)
                val m21 = parse2194Response(resp2194)

                // 2. Secondary Car Scanner PID: 21 03
                val resp2103 = sendCmd("21 03\r")
                delay(100)

                // 3. Fallback Standard OBD2 PIDs: 01 05 & 01 78
                val resp0105 = sendCmd("01 05\r")
                delay(100)
                val coolant = m21?.coolantTemp ?: parseCoolantTempResponse(resp0105)

                val resp0178 = sendCmd("01 78\r")
                delay(100)
                val dpfTemp = m21?.dpfTemp ?: parseDpfTempResponse(resp2103) ?: parseDpfTempResponse(resp0178)

                val sootPair = if (m21?.sootGrams != null && m21.sootPercent != null) {
                    m21.sootGrams to m21.sootPercent
                } else {
                    parseSootGramsAndPercent(resp2103)
                }

                val dist = m21?.distanceKm ?: parseDistanceResponse(resp2103)

                val rawSummary = "2194:$resp2194 | 2103:$resp2103 | 0105:$resp0105"
                FileLogger.log(context, "OBD Stats -> Coolant:$coolant, DPF Temp:$dpfTemp, Soot:${sootPair?.first}g, Dist:${dist}km")
                FileLogger.log(context, "OBD Raw -> $rawSummary")

                val updatedState = _obdState.value.copy(
                    isConnected = true,
                    isConnecting = false,
                    coolantTemperatureCelsius = coolant ?: _obdState.value.coolantTemperatureCelsius,
                    dpfTemperatureCelsius = dpfTemp ?: _obdState.value.dpfTemperatureCelsius,
                    sootGrams = sootPair?.first ?: _obdState.value.sootGrams,
                    sootLevelPercent = sootPair?.second ?: _obdState.value.sootLevelPercent,
                    distanceSinceRegenKm = dist ?: _obdState.value.distanceSinceRegenKm,
                    statusMessage = "CONNECTED",
                    rawFrameDump = rawSummary,
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
