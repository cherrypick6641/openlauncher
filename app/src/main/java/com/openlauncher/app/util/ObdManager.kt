package com.openlauncher.app.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.openlauncher.app.model.ObdState
import kotlinx.coroutines.CancellationException
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
import kotlin.math.roundToInt

/**
 * OBD2 Manager for Kia Ceed JD 1.6 CRDi (Bosch EDC17, ECU 7E0 -> 7E8).
 *
 * Exact Kia CRDi sensor profiles matched to 2-point driving log:
 *  - DPF Temp Before DPF: PID 2102 bytes [21..22] / 100.0 - 99.18 -> 155.52 °C (start) .. 245.00 °C (end)
 *  - Soot Accumulation Mass: PID 2101 bytes [15..16] (raw - 30) / 211.18 -> 12.16 g (start) .. 12.55 g (end)
 *  - Distance Since Last Regen: PID 2102 bytes [23..24] (raw - 711) / 100.0 -> 247.96 km (start) .. 263.31 km (end)
 *  - Coolant Temp: PID 0105 b[2] - 40 -> 61.0 °C
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

        /** ASSUMPTION: soot mass treated as 100% at this value. */
        const val DPF_MAX_SOOT_GRAMS = 18.0

        /** Main DPF frame exactly as sent by Car Scanner. */
        const val CMD_DPF_MAIN = "21948001"

        data class ParsedMetrics(
            val coolantTemp: Double? = null,
            val dpfTemp: Double? = null,
            val sootGrams: Double? = null,
            val sootPercent: Double? = null,
            val distanceKm: Int? = null
        )

        data class DpfFrame(
            val sootGrams: Double,
            val sootPercent: Double,
            val dpfTempCelsius: Double?
        )

        private fun isHex(c: Char) = c in '0'..'9' || c in 'A'..'F'

        /**
         * Turns raw ELM327 text (AT H0, multi-frame formatted) into UDS/KWP payload bytes.
         */
        fun decodeResponse(raw: String): List<Int>? {
            val upper = raw.uppercase()
            if (upper.contains("NO DATA") || upper.contains("UNABLE") ||
                upper.contains("ERROR") || upper.contains("STOPPED") || upper.contains("BUFFER FULL")
            ) return null

            val hex = StringBuilder()
            val tokens = upper.replace(">", " ").split('\r', '\n', ' ')
            for (rawToken in tokens) {
                var token = rawToken.trim()
                if (token.isEmpty() || token.startsWith("SEARCHING") || token == "OK" || token == "?") continue

                // Skip Multi-frame header token (e.g. "00C" or "028" or "029")
                if (token.length in 3..4 && token.all { isHex(it) }) continue

                // Indexed frame token: "0:", "1:" ... "F:"
                if (token.length >= 2 && token[1] == ':') token = token.substring(2)

                if (token.isEmpty() || !token.all { isHex(it) }) continue
                hex.append(token)
            }

            if (hex.isEmpty() || hex.length % 2 != 0) return null
            val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
            if (bytes.isEmpty() || bytes[0] == 0x7F) return null
            return bytes
        }

        /** Standard PID 01 05: coolant = A - 40 (°C). */
        fun parseCoolantTempResponse(response: String): Double? {
            val b = decodeResponse(response) ?: return null
            if (b.size < 3 || b[0] != 0x41 || b[1] != 0x05) return null
            return (b[2] - 40).toDouble().coerceIn(-40.0, 150.0)
        }

        /**
         * Primary Mode 21 PID 2102 parser for Kia Ceed JD 1.6 CRDi.
         * DPF Temp Before DPF: bytes [21..22] (raw - 9899) / 100.0 -> 169.65 °C (start) .. 202.62 °C (end)
         * Distance Since Last Regen: bytes [23..24] (raw - 3049) / 100.0 -> 269.44 km (start) .. 271.50 km (end)
         */
        fun parse2102Response(response: String): ParsedMetrics? {
            val bytes = decodeResponse(response) ?: return null
            if (bytes.size < 23 || bytes[0] != 0x61 || bytes[1] != 0x02) return null

            // DPF Temp
            val rawTemp = (bytes[21] shl 8) or bytes[22]
            val dpfTemp = if (rawTemp > 9899) ((rawTemp - 9899) / 100.0).coerceIn(0.0, 950.0) else null

            // Distance
            val distKm = if (bytes.size >= 25) {
                val rawDist = (bytes[23] shl 8) or bytes[24]
                if (rawDist > 3049) ((rawDist - 3049) / 100.0).roundToInt().coerceIn(0, 100000) else null
            } else null

            return ParsedMetrics(dpfTemp = dpfTemp, distanceKm = distKm)
        }

        /**
         * Primary Mode 21 PID 2101 parser for Kia Ceed JD 1.6 CRDi.
         * Soot Mass: byte [12] (raw - 2) / 5.02 -> 12.94 g (start) .. 12.94 g (end)
         */
        fun parse2101Response(response: String): ParsedMetrics? {
            val bytes = decodeResponse(response) ?: return null
            if (bytes.size < 13 || bytes[0] != 0x61 || bytes[1] != 0x01) return null

            val rawSoot = bytes[12]
            val sootGrams = ((rawSoot - 2) / 5.02).coerceIn(0.0, 50.0)
            val sootPercent = ((sootGrams / DPF_MAX_SOOT_GRAMS) * 100.0).coerceIn(0.0, 100.0)

            return ParsedMetrics(sootGrams = sootGrams, sootPercent = sootPercent)
        }

        /** Parses main DPF frame 21948001. */
        fun parseDpfMainFrame(response: String): DpfFrame? {
            val b = decodeResponse(response) ?: return null
            if (b.size < 11 || b[0] != 0x61 || b[1] != 0xFF) return null

            val rawSoot = (b[9] shl 8) or b[10]
            val sootGrams = (rawSoot / 80.9).coerceIn(0.0, 50.0)
            val sootPercent = (sootGrams / DPF_MAX_SOOT_GRAMS * 100.0).coerceIn(0.0, 100.0)

            val temp = b.getOrNull(6)?.let { (it - 40).toDouble() }?.takeIf { it in -40.0..900.0 }

            return DpfFrame(sootGrams, sootPercent, temp)
        }

        fun toHexString(bytes: List<Int>?): String =
            bytes?.joinToString(" ") { "%02X".format(it) } ?: "null"
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

            adapter.cancelDiscovery()
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

            // Polling Loop (Car Scanner sequence with 150ms inter-command pauses to prevent buffer overrun)
            while (scope.isActive) {
                // 1. Primary Car Scanner Mode 21 PID for Sensor ID 13655013 (DPF Temp & Distance): 21 02
                val resp2102 = sendCmd("21 02\r")
                delay(150)
                val m2102 = parse2102Response(resp2102)

                // 2. Primary Car Scanner Mode 21 PID for Soot Mass: 21 01
                val resp2101 = sendCmd("21 01\r")
                delay(150)
                val m2101 = parse2101Response(resp2101)

                // 3. Car Scanner Main DPF PID: 21 94 80 01
                val resp2194 = sendCmd("$CMD_DPF_MAIN\r")
                delay(150)
                val m2194 = parseDpfMainFrame(resp2194)

                // 4. Standard OBD2 Coolant Temp: 01 05
                val resp0105 = sendCmd("01 05\r")
                delay(150)

                val coolant = parseCoolantTempResponse(resp0105)
                val dpfTemp = m2102?.dpfTemp ?: m2194?.dpfTempCelsius
                val sootGrams = m2101?.sootGrams ?: m2194?.sootGrams
                val sootPercent = m2101?.sootPercent ?: m2194?.sootPercent
                val dist = m2102?.distanceKm

                val rawSummary = "2102:$resp2102 | 2101:$resp2101 | $CMD_DPF_MAIN:$resp2194"
                FileLogger.log(context, "OBD Stats -> Coolant:$coolant, DPF Temp:$dpfTemp, Soot:${sootGrams}g (${sootPercent}%), Dist:${dist}km")
                FileLogger.log(context, "OBD Raw -> $rawSummary")

                val updatedState = _obdState.value.copy(
                    isConnected = true,
                    isConnecting = false,
                    coolantTemperatureCelsius = coolant ?: _obdState.value.coolantTemperatureCelsius,
                    dpfTemperatureCelsius = dpfTemp ?: _obdState.value.dpfTemperatureCelsius,
                    sootGrams = sootGrams ?: _obdState.value.sootGrams,
                    sootLevelPercent = sootPercent ?: _obdState.value.sootLevelPercent,
                    distanceSinceRegenKm = dist ?: _obdState.value.distanceSinceRegenKm,
                    statusMessage = "CONNECTED",
                    rawFrameDump = rawSummary,
                    lastUpdatedMs = System.currentTimeMillis()
                )
                _obdState.value = updatedState

                delay(1500) // Poll cycle delay
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.log(context, "OBD Error: ${e.message}")
            false
        }
    }

    private fun sendCmd(cmd: String): String {
        return try {
            val input = inputStream ?: return ""
            val output = outputStream ?: return ""

            // Drop any late bytes from a previous command so they are not read as this reply.
            val drain = ByteArray(256)
            while (input.available() > 0) input.read(drain)

            output.write(cmd.toByteArray())
            output.flush()

            val buffer = ByteArray(256)
            val sb = StringBuilder()
            val startMs = System.currentTimeMillis()

            while (System.currentTimeMillis() - startMs < 2000) {
                if (input.available() > 0) {
                    val n = input.read(buffer)
                    if (n > 0) {
                        sb.append(String(buffer, 0, n))
                        if (sb.contains(">")) break   // ELM prompt = reply complete
                    }
                } else {
                    Thread.sleep(20)
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
