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
 * Queries Mode 21 (2194) matching Car Scanner profile for Kia/Hyundai Diesels.
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

        // ── Response Cleaning & Hex Extraction ────────────────────────────────

        /**
         * Cleans ELM327 response strings without corrupting payload hex bytes.
         */
        fun cleanResponse(rawResponse: String): String {
            return rawResponse
                .replace("SEARCHING...", "")
                .replace("BUS INIT", "")
                .replace("UNABLE TO CONNECT", "")
                .replace("CAN ERROR", "")
                .replace("NO DATA", "")
                .replace(">", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .uppercase()
        }

        /**
         * Extracts byte list following a specific response header (e.g., "6194", "4105").
         */
        fun extractHexBytes(rawResponse: String, header: String): List<Int>? {
            val clean = cleanResponse(rawResponse)
            val headerClean = header.replace(" ", "").uppercase()

            val index = clean.indexOf(headerClean)
            if (index < 0) return null

            val afterHeader = clean.substring(index + headerClean.length)
            val hexPairs = afterHeader.chunked(2)
                .takeWhile { it.length == 2 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
            
            if (hexPairs.isEmpty()) return null
            return hexPairs.mapNotNull { it.toIntOrNull(16) }
        }

        // ── Parsers ──────────────────────────────────────────────────────────

        /**
         * Standard OBD2 PID 0105: Coolant Temp = A - 40 (°C)
         */
        fun parseCoolantTempResponse(response: String): Double? {
            val bytes = extractHexBytes(response, "4105") ?: extractHexBytes(response, "41 05") ?: return null
            if (bytes.isEmpty()) return null
            return (bytes[0] - 40).toDouble().coerceIn(-40.0, 150.0)
        }

        /**
         * Primary Mode 21 PID 2194 parser for Kia Ceed JD 1.6 CRDi (Bosch EDC17 ECU).
         * Expected Header: "61 94"
         * Byte Layout after "61 94":
         *   Bytes 0..1 : DPF Temp (EGT) -> ((A*256 + B)/10 - 40)
         *   Byte 2     : Coolant Temp -> (C - 40)
         *   Byte 4     : Soot Accumulation (grams)
         *   Bytes 6..7 : Distance Since Last Regeneration (km) -> (A*256 + B)
         */
        fun parse2194Response(response: String): ParsedMetrics? {
            val bytes = extractHexBytes(response, "6194") ?: extractHexBytes(response, "61 94") ?: return null
            if (bytes.size < 8) return null

            // 1. DPF Temperature (°C)
            val rawDpf = (bytes[0] shl 8) or bytes[1]
            val dpfTemp = ((rawDpf / 10.0) - 40.0).coerceIn(0.0, 950.0)

            // 2. Coolant Temperature (°C)
            val coolantTemp = (bytes[2] - 40).toDouble().coerceIn(-40.0, 150.0)

            // 3. Soot Mass (grams & percent)
            val sootGrams = bytes[4].toDouble().coerceIn(0.0, 50.0)
            val sootPercent = ((sootGrams / DPF_MAX_SOOT_GRAMS) * 100.0).coerceIn(0.0, 100.0)

            // 4. Distance Since Last Regeneration (km)
            val distanceKm = ((bytes[6] shl 8) or bytes[7]).coerceIn(0, 10000)

            return ParsedMetrics(
                coolantTemp = coolantTemp,
                dpfTemp = dpfTemp,
                sootGrams = sootGrams,
                sootPercent = sootPercent,
                distanceKm = distanceKm
            )
        }

        /**
         * Standard OBD2 PID 0178 parser (Exhaust Gas Temperature - EGT).
         * Header: "41 78"
         * Bytes Layout: Byte 0 is sensor mask, Bytes 1..2 are raw temperature ((A*256 + B)/10 - 40)
         */
        fun parseEgt0178Response(response: String): Double? {
            val bytes = extractHexBytes(response, "4178") ?: extractHexBytes(response, "41 78") ?: return null
            if (bytes.size < 3) return null

            val rawTemp = (bytes[1] shl 8) or bytes[2]
            return ((rawTemp / 10.0) - 40.0).coerceIn(0.0, 1000.0)
        }

        /**
         * Fallback DPF Temperature parser across available responses.
         */
        fun parseDpfTempResponse(response: String): Double? {
            val m21 = parse2194Response(response)
            if (m21?.dpfTemp != null) return m21.dpfTemp

            val egt = parseEgt0178Response(response)
            if (egt != null) return egt

            return null
        }

        /**
         * Fallback Soot Grams and Percent parser.
         */
        fun parseSootGramsAndPercent(response: String): Pair<Double, Double>? {
            val m21 = parse2194Response(response)
            if (m21?.sootGrams != null && m21.sootPercent != null) {
                return m21.sootGrams to m21.sootPercent
            }
            return null
        }

        /**
         * Fallback Distance Since Last Regeneration parser.
         */
        fun parseDistanceResponse(response: String): Int? {
            val m21 = parse2194Response(response)
            if (m21?.distanceKm != null) return m21.distanceKm
            return null
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

            // ELM327 Initialization Commands for Kia Ceed JD 1.6 CRDi (Car Scanner profile sequence)
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

            // Polling Loop
            while (scope.isActive) {
                // 1. Primary Mode 21 PID for Kia Ceed 1.6 CRDi: 21 94
                val resp2194 = sendCmd("21 94\r")
                delay(200)
                val m21 = parse2194Response(resp2194)

                // 2. Fallback Standard OBD2 Coolant Temp if needed
                val coolant = m21?.coolantTemp ?: run {
                    val resp0105 = sendCmd("01 05\r")
                    delay(150)
                    parseCoolantTempResponse(resp0105)
                }

                // 3. Fallback Standard OBD2 EGT DPF Temp if needed
                val dpfTemp = m21?.dpfTemp ?: run {
                    val resp0178 = sendCmd("01 78\r")
                    delay(150)
                    parseEgt0178Response(resp0178)
                }

                val sootGrams = m21?.sootGrams
                val sootPercent = m21?.sootPercent
                val dist = m21?.distanceKm

                val rawSummary = "2194:$resp2194"
                FileLogger.log(context, "OBD Stats -> Coolant:$coolant, DPF Temp:$dpfTemp, Soot:${sootGrams}g, Dist:${dist}km")

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
