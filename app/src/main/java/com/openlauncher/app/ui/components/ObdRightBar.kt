package com.openlauncher.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Water
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.model.ObdState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ObdRightBar(
    settings: AppSettings,
    obdState: ObdState,
    modifier: Modifier = Modifier,
    isDayMode: Boolean = false
) {
    val barWidth = settings.obdRightBarWidthDp.dp
    val accent = Color(settings.accentColor)
    val containerBg = if (isDayMode) Color(0xFFE8E8E8) else Color(0xFF101012)
    val containerBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF222224)
    val textColor = if (isDayMode) Color.Black else Color.White
    val mutedColor = if (isDayMode) Color(0xFF666666) else Color(0xFFAAAAAA)
    val warningColor = Color(0xFFE53935)

    val isRegenActive = obdState.isDpfRegenActive

    Box(
        modifier = modifier
            .width(barWidth)
            .fillMaxHeight()
            .padding(top = 6.dp, end = 6.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerBg)
            .border(width = 1.dp, color = containerBorder, shape = RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!obdState.isConnected) {
            // Disconnected / Connecting State
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BluetoothDisabled,
                    contentDescription = "OBD Disconnected",
                    tint = warningColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (obdState.isConnecting) "RETRYING..." else "NO OBD",
                    color = warningColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "VLINK",
                    color = mutedColor,
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Connected Telemetry Stack
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxHeight()
            ) {
                // 1. DPF Temperature Before DPF (Accent highlighted when > 500°C)
                if (settings.showDpfTemp) {
                    val dpfTemp = obdState.dpfTemperatureCelsius
                    val dpfTempText = dpfTemp?.let { "${it.roundToInt()}°" } ?: "--°"
                    val dpfColor = if (isRegenActive) accent else textColor
                    val dpfIconColor = if (isRegenActive) accent else mutedColor

                    ObdGaugeItem(
                        icon = Icons.Default.Thermostat,
                        valueText = dpfTempText,
                        label = if (isRegenActive) "REGEN!" else "DPF",
                        valueColor = dpfColor,
                        iconColor = dpfIconColor
                    )
                }

                // 2. Soot Accumulation (Grams)
                if (settings.showSootLevel) {
                    val grams = obdState.sootGrams
                    val sootText = grams?.let { String.format(Locale.US, "%.1fg", it) }
                        ?: obdState.sootLevelPercent?.let { "${it.roundToInt()}%" }
                        ?: "--g"

                    ObdGaugeItem(
                        icon = Icons.Default.FilterAlt,
                        valueText = sootText,
                        label = "SOOT",
                        valueColor = textColor,
                        iconColor = accent
                    )
                }

                // 3. Distance Since Last DPF Clean (km)
                if (settings.showLastRegenDist) {
                    val dist = obdState.distanceSinceRegenKm
                    val distText = dist?.let { "$it km" } ?: "-- km"

                    ObdGaugeItem(
                        icon = Icons.Default.DirectionsCar,
                        valueText = distText,
                        label = "LAST CLEAN",
                        valueColor = textColor,
                        iconColor = accent
                    )
                }

                // 4. Coolant Temperature (°C)
                if (settings.showCoolantTemp) {
                    val coolant = obdState.coolantTemperatureCelsius
                    val coolantText = coolant?.let { "${it.roundToInt()}°" } ?: "--°"

                    ObdGaugeItem(
                        icon = Icons.Default.Water,
                        valueText = coolantText,
                        label = "COOLANT",
                        valueColor = textColor,
                        iconColor = accent
                    )
                }
            }
        }
    }
}

@Composable
private fun ObdGaugeItem(
    icon: ImageVector,
    valueText: String,
    label: String,
    valueColor: Color,
    iconColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = valueText,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = label,
            color = valueColor.copy(alpha = 0.7f),
            fontSize = 8.sp,
            textAlign = TextAlign.Center
        )
    }
}
