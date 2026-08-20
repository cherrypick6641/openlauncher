package com.openlauncher.app.ui.widget

import android.media.MediaMetadata
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.service.MediaListenerService
import kotlinx.coroutines.delay
import kotlin.math.sin

@Composable
fun NowPlayingWidget(
    state: NowPlayingState?,
    accent: Color,
    carPlayPackage: String,
    androidAutoPackage: String,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onLaunchCarPlay: () -> Unit,
    onLaunchAndroidAuto: () -> Unit,
    onTapToOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
    isEditing: Boolean = false,
    isDayMode: Boolean = false,
    isCompact: Boolean = false
) {
    val isConnected by MediaListenerService.isConnected.collectAsState()
    val hasCarPlay  = carPlayPackage.isNotEmpty()
    val hasAutoApp  = androidAutoPackage.isNotEmpty()
    val hasContent  = state != null && state.title.isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
    ) {
        StandardMinimalPlayer(
            state = state,
            accent = accent,
            hasContent = hasContent,
            isEditing = isEditing,
            isDayMode = isDayMode,
            isCompact = isCompact,
            isConnected = isConnected,
            hasCarPlay = hasCarPlay,
            hasAutoApp = hasAutoApp,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrev = onPrev,
            onLaunchCarPlay = onLaunchCarPlay,
            onLaunchAndroidAuto = onLaunchAndroidAuto,
            onTapToOpenApp = onTapToOpenApp,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun WaveProgressIndicator(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {

        val centerY = size.height / 2f
        val progressX = size.width * progress

        // Línea gris restante
        drawLine(
            color = trackColor,
            start = Offset(progressX, centerY),
                 end = Offset(size.width, centerY),
                 strokeWidth = 3.dp.toPx(),
                 cap = StrokeCap.Round
        )

        // Onda naranja
        var previousX = 0f
        var previousY = centerY

        val amplitude = 3.dp.toPx()
        val wavelength = 20.dp.toPx()
        val step = 1.dp.toPx()

        var x = step

        while (x <= progressX) {
            val y = centerY +
            amplitude * sin(
                (x / wavelength) * (2f * Math.PI).toFloat()
            )

            drawLine(
                color = color,
                start = Offset(previousX, previousY),
                     end = Offset(x, y),
                     strokeWidth = 3.dp.toPx(),
                     cap = StrokeCap.Round
            )

            previousX = x
            previousY = y
            x += step
        }

        // Círculo naranja
        drawCircle(
            color = color,
            radius = 5.dp.toPx(),
                   center = Offset(progressX, centerY)
        )
    }
}

@Composable
private fun StandardMinimalPlayer(
    state: NowPlayingState?,
    accent: Color,
    hasContent: Boolean,
    isEditing: Boolean,
    isDayMode: Boolean,
    isCompact: Boolean,
    isConnected: Boolean,
    hasCarPlay: Boolean,
    hasAutoApp: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onLaunchCarPlay: () -> Unit,
    onLaunchAndroidAuto: () -> Unit,
    onTapToOpenApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // UI Theme colors
    val idleTextColor = if (isDayMode) Color(0xFF555555) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
    val currentTextColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val currentSubTextColor = if (isDayMode) Color(0xFF666666) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)

    Box(modifier = modifier) {
        // Transparent clickable background overlay (underneath controls) to open the player
        if (!isEditing) {
            Box(
                modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                           indication = null
                ) { onTapToOpenApp() }
            )
        }

        if (!hasContent) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (hasCarPlay || hasAutoApp) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasCarPlay) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .let { if (!isEditing) it.clickable { onLaunchCarPlay() } else it }
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.PhoneAndroid, null, tint = accent.copy(alpha = 0.7f), modifier = Modifier.size(28.dp))
                                    Text("CARPLAY", color = accent.copy(alpha = 0.6f), fontSize = 8.sp, letterSpacing = 2.sp)
                                }
                            }
                        }
                        if (hasCarPlay && hasAutoApp) {
                            VerticalDivider(
                                modifier = Modifier.fillMaxHeight().padding(vertical = 16.dp),
                                color = if (isDayMode) Color(0xFFBBBBBB) else Color(0xFF1E1E1E)
                            )
                        }
                        if (hasAutoApp) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .let { if (!isEditing) it.clickable { onLaunchAndroidAuto() } else it }
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.DirectionsCar, null, tint = accent.copy(alpha = 0.7f), modifier = Modifier.size(28.dp))
                                    Text("ANDROID AUTO", color = accent.copy(alpha = 0.6f), fontSize = 8.sp, letterSpacing = 2.sp)
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isDayMode) Color(0xFF111111) else accent.copy(alpha = 0.9f))
                            .clickable { onTapToOpenApp() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                 contentDescription = "Play",
                                 tint = if (isDayMode) Color.White else Color.Black,
                                 modifier = Modifier.size(24.dp)
                            )
                        }
                        Text("TAP TO PLAY MUSIC", color = idleTextColor, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Non-null playing track state
            val nonNullState = state!!
            
            // Fix: Include albumArt in keys so the model updates when the bitmap arrives later
            val artworkModel = remember(
                nonNullState.artUri,
                nonNullState.albumArt,
                nonNullState.title,
                nonNullState.artist
            ) {
                nonNullState.artUri ?: nonNullState.albumArt
            }
            
            var positionMs by remember { mutableLongStateOf(nonNullState.controller?.playbackState?.position ?: 0L) }
            val durationMs = nonNullState.controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

            LaunchedEffect(nonNullState.isPlaying, nonNullState.title) {
                while (nonNullState.isPlaying) {
                    positionMs = nonNullState.controller?.playbackState?.position ?: positionMs
                    delay(500)
                }
            }

            // Draw Album Art as background with smooth blur overlay if present
            val hasAlbumArt = nonNullState.albumArt != null || nonNullState.artUri != null
            val useDarkTheme = hasAlbumArt || !isDayMode

            val currentTextColorOnArt = if (hasAlbumArt) Color.White else if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
            val currentSubTextColorOnArt = if (hasAlbumArt) Color.White.copy(alpha = 0.6f) else if (isDayMode) Color(0xFF666666) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            val currentProgressColor = if (useDarkTheme) accent else Color(0xFF111111)
            val currentProgressTrack = currentTextColor.copy(alpha = 0.15f)
            val currentIconColor = if (hasAlbumArt) Color.White.copy(alpha = 0.75f) else currentTextColor.copy(alpha = 0.75f)
            val currentPlayBgColor = if (useDarkTheme) accent.copy(alpha = 0.9f) else Color(0xFF111111)
            val currentPlayIconColor = if (useDarkTheme) Color.White else Color.Black

            if (!isCompact) {
                if (hasAlbumArt) {
                    // Reworked art loading: Use ImageRequest for better caching/crossfade
                    val imageRequest = remember(artworkModel) {
                        coil.request.ImageRequest.Builder(context)
                            .data(artworkModel)
                            .crossfade(true)
                            .diskCacheKey("${nonNullState.title}-${nonNullState.artist}")
                            .build()
                    }

                    coil.compose.AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                        // Show a dim placeholder while the art loads
                        placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color.Black.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxSize()
                    )
                    // 35% dimming layer overlay for better readability on art
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                    )
                }
            }

            if (isCompact) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left: Album Cover (Rectangle)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isDayMode) Color(0xFFE0E0E0) else Color(0xFF1A1A1A))
                    ) {
                        if (hasAlbumArt) {
                            coil.compose.AsyncImage(
                                model = artworkModel,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Default.MusicNote, null,
                                tint = accent.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp).align(Alignment.Center)
                            )
                        }
                    }

                    // Right: Info
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = nonNullState.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = currentTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp
                        )
                        Text(
                            text = nonNullState.artist.ifEmpty { "Unknown" },
                            style = MaterialTheme.typography.bodySmall,
                            color = currentSubTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Track info (top — clickable to open app)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .let { if (!isEditing) it.clickable { onTapToOpenApp() } else it }
                    ) {

                    }

                    // Progress + controls (bottom)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Track info (top — clickable to open app)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(60.dp))
                                .background(Color.Black.copy(alpha = 0.35f))
                                .let {
                                    if (!isEditing) {
                                        it.clickable { onTapToOpenApp() }
                                    } else {
                                        it
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = nonNullState.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = currentTextColorOnArt,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp
                                )

                                Text(
                                    text = nonNullState.artist.ifEmpty { "Unknown" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = currentSubTextColorOnArt,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (durationMs > 0) {
                            WaveProgressIndicator(
                                progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                                color = currentProgressColor,
                                trackColor = currentProgressTrack,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(14.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatMs(positionMs), style = MaterialTheme.typography.labelSmall, color = currentSubTextColorOnArt.copy(alpha = 0.75f), fontSize = 9.sp)
                                Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = currentSubTextColorOnArt.copy(alpha = 0.75f), fontSize = 9.sp)
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // button Prev
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.15f))
                                    .clickable(enabled = !isEditing) { onPrev() }
                            ) {
                                Icon(Icons.Default.SkipPrevious, "Prev", tint = currentIconColor, modifier = Modifier.size(30.dp))
                            }

                            // button play and pause
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(currentPlayBgColor)
                                    // Pasamos el click aquí. Si está editando, se deshabilita tanto el click como el efecto visual (ripple)
                                    .clickable(enabled = !isEditing) { onPlayPause() }
                            ) {
                                Icon(
                                    imageVector = if (nonNullState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (nonNullState.isPlaying) "Pause" else "Play",
                                    tint = currentPlayIconColor,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .offset(x = if (!nonNullState.isPlaying) -1.dp else 0.dp)
                                )
                            }
                            // button Next
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.15f))
                                    .clickable(enabled = !isEditing) { onNext() }
                            ) {
                                Icon(Icons.Default.SkipNext, "Next", tint = currentIconColor, modifier = Modifier.size(30.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
