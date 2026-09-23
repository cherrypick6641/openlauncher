package com.openlauncher.app.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.model.windDirectionToCardinal
import com.openlauncher.app.ui.widget.PipWidget
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    settings: AppSettings,
    weather: WeatherState?,
    nowPlaying: NowPlayingState?,
    isDayMode: Boolean = false,
    onPlayPause: () -> Unit,
    onTapNowPlaying: () -> Unit,
    isOverlayOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bottomBarHeight = settings.bottomBarHeightDp.dp

    Column(modifier = modifier.fillMaxSize()) {
        // ── Central Main Screen Area (PIP View) ──────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 6.dp, start = 6.dp, end = 6.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isDayMode) Color(0xFFE8E8E8) else Color(0xFF101012))
                .border(
                    width = 1.dp,
                    color = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF222224),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            PipWidget(
                packageName = settings.pipAppPackage,
                isOverlayOpen = isOverlayOpen,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── Bottom Bar ──────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bottomBarHeight)
                .background(if (isDayMode) Color(0xFFECECEC) else Color(0xFF090909))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Left: Clock & Date ────────────────────────────────────────────
                ClockDateBlock(isDayMode = isDayMode)

                // ── Middle: Now Playing Card (Equal Weight & Height) ───────────────
                NowPlayingBottomCard(
                    nowPlaying = nowPlaying,
                    accent = Color(settings.accentColor),
                    isDayMode = isDayMode,
                    onPlayPause = onPlayPause,
                    onTap = onTapNowPlaying,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                // ── Right: Weather Card (Equal Weight & Height) ────────────────────
                WeatherBottomCard(
                    weather = weather,
                    accent = Color(settings.accentColor),
                    isDayMode = isDayMode,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun ClockDateBlock(isDayMode: Boolean) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }

    var timeText by remember { mutableStateOf(timeFormat.format(Date())) }
    var dateText by remember { mutableStateOf(dateFormat.format(Date())) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            timeText = timeFormat.format(now)
            dateText = dateFormat.format(now)
            delay(1000)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = timeText,
            color = if (isDayMode) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        )
        Text(
            text = dateText,
            color = if (isDayMode) Color(0xFF666666) else Color(0xFFAAAAAA),
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun NowPlayingBottomCard(
    nowPlaying: NowPlayingState?,
    accent: Color,
    isDayMode: Boolean,
    onPlayPause: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBg = if (isDayMode) Color(0xFFDFDFDF) else Color(0xFF161618)
    val cardBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF26262A)
    val titleColor = if (isDayMode) Color.Black else Color.White
    val subtitleColor = if (isDayMode) Color(0xFF555555) else Color(0xFFAAAAAA)

    val albumArt = nowPlaying?.albumArt

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Album Art Thumbnail
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF222222))
        ) {
            if (albumArt != null) {
                Image(
                    painter = BitmapPainter(albumArt.asImageBitmap()),
                    contentDescription = "Album Art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Title and Artist Info
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = nowPlaying?.title?.ifEmpty { "No Media Playing" } ?: "No Media Playing",
                color = titleColor,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = nowPlaying?.artist?.ifEmpty { "Select audio app" } ?: "Select audio app",
                color = subtitleColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        // Play/Pause button
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (nowPlaying?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (nowPlaying?.isPlaying == true) "Pause" else "Play",
                tint = titleColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun WeatherBottomCard(
    weather: WeatherState?,
    accent: Color,
    isDayMode: Boolean,
    modifier: Modifier = Modifier
) {
    val cardBg = if (isDayMode) Color(0xFFDFDFDF) else Color(0xFF161618)
    val cardBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF26262A)
    val textColor = if (isDayMode) Color.Black else Color.White
    val mutedColor = if (isDayMode) Color(0xFF555555) else Color(0xFFAAAAAA)

    val currentTemp = weather?.currentTemperature?.let { "${it.toInt()}°" } ?: "20°"
    val maxTemp = weather?.maxTemperatureToday?.let { "${it.toInt()}°" } ?: "25°"
    val minTemp = weather?.minTemperatureToday?.let { "${it.toInt()}°" } ?: "14°"
    val locationName = weather?.locationName ?: "Juja"
    val windSpeed = weather?.windSpeed?.let { "${it.toInt()} km/h" } ?: "12 km/h"
    val windDir = windDirectionToCardinal(weather?.windDirection ?: 225.0)
    val isRainy = weather?.forecastDays?.firstOrNull()?.conditionLabel?.contains("rain", ignoreCase = true) == true

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Temperature & Icon Block
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isRainy) Icons.Default.Cloud else Icons.Default.WbSunny,
                contentDescription = "Weather",
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = currentTemp,
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        VerticalDivider(
            modifier = Modifier.height(28.dp),
            color = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF333333)
        )

        // Details Block: Location, Min/Max, Wind
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            // Location
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Location",
                    tint = accent,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = locationName.uppercase(),
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Min/Max and Wind
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "H: $maxTemp  L: $minTemp",
                    color = mutedColor,
                    fontSize = 10.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Air,
                        contentDescription = "Wind",
                        tint = mutedColor,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "$windSpeed $windDir",
                        color = mutedColor,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
