package com.openlauncher.app.util

import java.util.Calendar
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

object SunriseSunset {

    fun isDay(lat: Double, lon: Double): Boolean {
        val (rise, set) = localMinutes(lat, lon)
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (rise < set) now in rise..set else now >= rise || now <= set
    }

    fun localMinutes(lat: Double, lon: Double): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15.0
        val riseUtc = compute(day, lngHour, lat, rising = true)
        val setUtc  = compute(day, lngHour, lat, rising = false)
        val tzMins  = cal.timeZone.getOffset(cal.timeInMillis) / 60_000
        return (riseUtc + tzMins).mod(1440) to (setUtc + tzMins).mod(1440)
    }

    private fun compute(dayOfYear: Int, lngHour: Double, lat: Double, rising: Boolean): Int {
        val t  = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m  = 0.9856 * t - 3.289
        val l  = (m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634).mod(360.0)
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l)))).mod(360.0)
        ra = (ra + (floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0)) / 15.0
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH   = (cos(Math.toRadians(90.833)) - sinDec * sin(Math.toRadians(lat))) /
                     (cosDec * cos(Math.toRadians(lat)))
        val h = if (rising)
            (360.0 - Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0)))) / 15.0
        else
            Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0))) / 15.0
        return ((h + ra - 0.06571 * t - 6.622 - lngHour).mod(24.0) * 60).toInt()
    }
}
