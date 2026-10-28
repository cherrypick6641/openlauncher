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
    fun testParseTripStartAndEndValues() {
        // Trip Start values in driving log D:\launcher_logs.txt (Soot = 12.94g, Temp = 169.65°C, Dist = 269.44km):
        val resp2102Start = "029\r0:6102FFFFFFFF 1:232D63000E00A5 2:340000806E037F 3:FF7E667ED6381D 4:1800FFFFFFFFFF 5:FFFFFFFFFFFFFF >"
        val m2102Start = ObdManager.parse2102Response(resp2102Start)
        assertNotNull(m2102Start)
        assertEquals(169.65, m2102Start!!.dpfTemp!!, 0.1) // b[6]=0x63=99 -> 99*3.297-156.75 = 169.65°C!
        assertEquals(269, m2102Start.distanceKm!!)       // b[8]=0x0E=14 -> 269.44km -> 269 km!

        // Trip End values in driving log D:\launcher_logs.txt (Soot = 12.94g, Temp = 202.62°C, Dist = 271.50km):
        val resp2102End = "029\r0:6102FFFFFFFF 1:232D6D003200A0 2:330000806E047F 3:FF75D175F7391D 4:1800FFFFFFFFFF 5:FFFFFFFFFFFFFF >"
        val m2102End = ObdManager.parse2102Response(resp2102End)
        assertNotNull(m2102End)
        assertEquals(202.62, m2102End!!.dpfTemp!!, 0.1)  // b[6]=0x6D=109 -> 109*3.297-156.75 = 202.62°C!
        assertEquals(272, m2102End.distanceKm!!)       // b[8]=0x32=50 -> 271.50km -> 272 km!

        val resp2101End = "028\r0:6101FFFFFFFF 1:0023001D464843 2:53A14A261300FF 3:14910D0AFF0000 4:48A23FFF1E0300 5:0024FFFFFFFF55 >"
        val m2101End = ObdManager.parse2101Response(resp2101End)
        assertNotNull(m2101End)
        assertEquals(12.94, m2101End!!.sootGrams!!, 0.1) // b[6]=0x48=72 -> 72 / 5.564 = 12.94g!
    }

    @Test
    fun testDecodeResponseMultiFrame() {
        val raw = "00C\r0: 61 FF 15 00 1B 91\r1: 59 00 0C 03 14 FF\r\r>"
        val bytes = ObdManager.decodeResponse(raw)
        assertNotNull(bytes)
        assertEquals(12, bytes!!.size)
        assertEquals(0x61, bytes[0])
        assertEquals(0xFF, bytes[1])
        assertEquals(0x03, bytes[9])
        assertEquals(0x14, bytes[10])
    }
}
