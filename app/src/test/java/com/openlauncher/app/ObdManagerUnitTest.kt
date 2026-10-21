package com.openlauncher.app

import com.openlauncher.app.util.ObdManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ObdManagerUnitTest {

    @Test
    fun testParseCoolantTemp() {
        // Standard OBD2 PID 0105 response: 41 05 5A (0x5A = 90 -> 90 - 40 = 50°C)
        val response = "41 05 5A >"
        val coolant = ObdManager.parseCoolantTempResponse(response)
        assertNotNull(coolant)
        assertEquals(50.0, coolant!!, 0.1)
    }

    @Test
    fun testParseDpfTemperature() {
        // Kia Ceed Mode 22 PID 221105 response: 62 11 05 03 A8 (0x03A8 = 936 -> 93.6 - 40 = 53.6°C)
        val response1 = "62 11 05 03 A8 >"
        val dpfTemp1 = ObdManager.parseDpfTempResponse(response1)
        assertNotNull(dpfTemp1)
        assertEquals(53.6, dpfTemp1!!, 0.1)

        // Active DPF Regeneration: 62 11 05 15 18 (0x1518 = 5400 -> 540.0 - 40 = 500.0°C)
        val response2 = "62 11 05 15 18 >"
        val dpfTemp2 = ObdManager.parseDpfTempResponse(response2)
        assertNotNull(dpfTemp2)
        assertEquals(500.0, dpfTemp2!!, 0.1)
    }

    @Test
    fun testParseSootGramsAndPercent() {
        // Kia Ceed Mode 22 PID 221101 response: 62 11 01 00 00 0C (0x0C = 12g soot mass)
        val response = "62 11 01 00 00 0C >"
        val pair = ObdManager.parseSootGramsAndPercent(response)
        assertNotNull(pair)
        assertEquals(12.0, pair!!.first, 0.1) // 12.0 g
        assertEquals(66.6, pair.second, 0.1)  // 12g / 18g = 66.6%
    }

    @Test
    fun testParseDistanceSinceRegen() {
        // Kia Ceed Mode 22 PID 221102 response: 62 11 02 01 A4 (0x01A4 = 420 km)
        val response = "62 11 02 01 A4 >"
        val dist = ObdManager.parseDistanceResponse(response)
        assertNotNull(dist)
        assertEquals(420, dist!!)
    }
}
