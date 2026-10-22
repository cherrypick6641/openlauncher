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
    fun testParse2194CarScannerResponse() {
        // Header "61 94", followed by:
        // bytes[0,1] = DPF Temp (0x03A8 = 936 -> 93.6 - 40 = 53.6°C)
        // bytes[2]   = Coolant (0x5C = 92 -> 92 - 40 = 52°C)
        // bytes[4]   = Soot mass (0x0C = 12g)
        // bytes[6,7] = Distance (0x01A4 = 420km)
        val response = "61 94 03 A8 5C 00 0C 00 01 A4 >"
        val m21 = ObdManager.parse2194Response(response)
        assertNotNull(m21)
        assertEquals(52.0, m21!!.coolantTemp!!, 0.1)
        assertEquals(53.6, m21.dpfTemp!!, 0.1)
        assertEquals(12.0, m21.sootGrams!!, 0.1)
        assertEquals(420, m21.distanceKm!!)
    }

    @Test
    fun testParseEgt0178Response() {
        // Mode 01 PID 0178 EGT response: 41 78 01 03 A8 (0x03A8 = 936 -> 93.6 - 40 = 53.6°C)
        val response = "41 78 01 03 A8 >"
        val temp = ObdManager.parseEgt0178Response(response)
        assertNotNull(temp)
        assertEquals(53.6, temp!!, 0.1)
    }
}
