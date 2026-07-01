package com.openlauncher.app.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DefaultShortcutIcon
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.ui.theme.LocalDayMode
import kotlin.math.roundToInt

private val ICON_SIZE   = 36.dp
private val SHORTCUT_ICON_SIZE = 48.dp
private val SHORTCUT_SLOT_SIZE = 56.dp
private val NAV_SLOT_SIZE = 42.dp
private val SIDEBAR_W   = 60.dp

@Composable
fun Sidebar(
    currentDest: NavDestination,
    settings: AppSettings,
    installedIconFor: (String) -> Drawable?,
    onNavigate: (NavDestination) -> Unit,
    onShortcutClick: (Int) -> Unit,
    onShortcutLongPress: (Int) -> Unit,
    onShortcutRemove: (Int) -> Unit,
    onShortcutSetIcon: (Int, DefaultShortcutIcon?) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    wifiLevel: Int = -1,
    mobileLevel: Int = -1,
    editMode: Boolean = false,
    onToggleEditMode: () -> Unit = {},
    onOpenWidgetLibrary: () -> Unit = {},
    isHorizontal: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isDayMode    = LocalDayMode.current
    val accent       = Color(settings.accentColor)
    val sidebarBg    = if (isDayMode) Color(0xFFE0E0E0) else Color.Black.copy(alpha = 0.0f)
    val iconInactive = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    val dividerColor = if (isDayMode) Color(0xFFCCCCCC) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.0f)
    val density      = LocalDensity.current
    val slotSizePx   = with(density) { SHORTCUT_SLOT_SIZE.toPx() }

    var actionSheetSlot by remember { mutableStateOf<Int?>(null) }
    var iconPickerSlot  by remember { mutableStateOf<Int?>(null) }

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx  by remember { mutableFloatStateOf(0f) }

    fun dragTargetIndex(): Int = if (draggingIndex < 0) -1 else
        (draggingIndex + (dragOffsetPx / slotSizePx).roundToInt())
            .coerceIn(0, settings.shortcuts.size - 1)

    fun slotTranslation(index: Int): Float {
        if (draggingIndex < 0 || index == draggingIndex) return 0f
        val from = draggingIndex
        val to   = dragTargetIndex()
        return when {
            from < to && index in (from + 1)..to -> -slotSizePx
            from > to && index in to until from  ->  slotSizePx
            else -> 0f
        }
    }

    val statusIconColor   = if (isDayMode) Color(0xFF444444) else Color(0xFF666666)

    val timeFormatter = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val dateFormatter = remember { java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()) }
    val dayFormatter  = remember { java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault()) }

    var timeText by remember { mutableStateOf(timeFormatter.format(java.util.Date())) }
    var dateText by remember { mutableStateOf(dateFormatter.format(java.util.Date())) }
    var dayText  by remember { mutableStateOf(dayFormatter.format(java.util.Date())) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = java.util.Date()
            timeText = timeFormatter.format(now)
            dateText = dateFormatter.format(now)
            dayText  = dayFormatter.format(now)
            kotlinx.coroutines.delay(1000)
        }
    }

    val statusIcons: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = if (isHorizontal) Modifier.padding(horizontal = 8.dp) else Modifier.padding(vertical = 4.dp)
        ) {
            // Mobile Signal Icon
            if (mobileLevel >= 0) {
                val mobileIcon = when (mobileLevel) {
                    1 -> Icons.Filled.SignalCellularAlt1Bar
                    2 -> Icons.Filled.SignalCellularAlt2Bar
                    3 -> Icons.Filled.SignalCellularAlt
                    4 -> Icons.Filled.SignalCellular4Bar
                    else -> Icons.Filled.SignalCellular0Bar
                }
                Icon(mobileIcon, null, tint = statusIconColor, modifier = Modifier.size(20.dp))
            }

            // Wifi Signal Icon (only if connected)
            if (wifiLevel >= 0) {
                val wifiIcon = when (wifiLevel) {
                    1 -> Icons.Filled.NetworkWifi1Bar
                    2 -> Icons.Filled.NetworkWifi2Bar
                    3 -> Icons.Filled.NetworkWifi3Bar
                    4 -> Icons.Filled.Wifi
                    else -> Icons.Filled.SignalWifi0Bar
                }
                Icon(wifiIcon, null, tint = statusIconColor, modifier = Modifier.size(20.dp))
            }
        }
    }

    val clockContent: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = timeText,
                color = if (isDayMode) Color.Black else Color.White,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                text = dateText.uppercase(),
                color = if (isDayMode) Color(0xFF666666) else Color(0xFF999999),
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
            Text(
                text = dayText,
                color = if (isDayMode) Color(0xFF666666) else Color(0xFF999999),
                fontSize = 14.sp
            )
        }
    }

    val editButtons: @Composable () -> Unit = {
        val content = @Composable {
            if (editMode) {
                IconButton(onClick = onOpenWidgetLibrary, modifier = Modifier.size(NAV_SLOT_SIZE)) {
                    Icon(Icons.Default.Dashboard, null, tint = statusIconColor, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = onToggleEditMode, modifier = Modifier.size(NAV_SLOT_SIZE)) {
                Icon(Icons.Default.Edit, null, tint = if (editMode) accent else statusIconColor, modifier = Modifier.size(20.dp))
            }
        }

        if (isHorizontal) {
            Row(verticalAlignment = Alignment.CenterVertically) { content() }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }

    val shortcutsContent: @Composable () -> Unit = {
        settings.shortcuts.forEachIndexed { index, shortcut ->
            val isDragging  = (index == draggingIndex)
            val translation = if (isDragging) dragOffsetPx else slotTranslation(index)

            ShortcutSlot(
                shortcut        = shortcut,
                accent          = accent,
                resolvedIcon    = if (shortcut.packageName.isNotEmpty())
                                      installedIconFor(shortcut.packageName) else null,
                isDragging      = isDragging,
                dragTranslation = translation,
                isHorizontal    = isHorizontal,
                size            = SHORTCUT_SLOT_SIZE,
                onClick         = { onShortcutClick(index) },
                onLongPress     = {
                    if (shortcut.packageName.isNotEmpty()) {
                        actionSheetSlot = index
                    } else {
                        onShortcutLongPress(index)
                    }
                },
                onDragStart = {
                    draggingIndex = index
                    dragOffsetPx  = 0f
                },
                onDragDelta = { d -> dragOffsetPx += d },
                onDragEnd   = {
                    val from = draggingIndex
                    val to   = dragTargetIndex()
                    if (from >= 0 && to != from) onReorder(from, to)
                    draggingIndex = -1
                    dragOffsetPx  = 0f
                }
            )
        }
    }

    val navButtons: @Composable () -> Unit = {
        NavButton(
            icon         = Icons.Default.Apps,
            label        = "Apps",
            isActive     = currentDest == NavDestination.APP_LIBRARY,
            accent       = accent,
            iconInactive = iconInactive,
            isHorizontal = isHorizontal,
            onClick      = { 
                if (currentDest == NavDestination.APP_LIBRARY) onNavigate(NavDestination.HOME) 
                else onNavigate(NavDestination.APP_LIBRARY) 
            }
        )
        NavButton(
            icon         = Icons.Default.Settings,
            label        = "Settings",
            isActive     = currentDest == NavDestination.SETTINGS,
            accent       = accent,
            iconInactive = iconInactive,
            isHorizontal = isHorizontal,
            onClick      = { 
                if (currentDest == NavDestination.SETTINGS) onNavigate(NavDestination.HOME) 
                else onNavigate(NavDestination.SETTINGS) 
            }
        )
    }

    if (isHorizontal) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(SIDEBAR_W)
                .background(sidebarBg)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Controls pinned to the LEFT
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp)) {
                    NavButton(
                        icon         = Icons.Default.Settings,
                        label        = "Settings",
                        isActive     = currentDest == NavDestination.SETTINGS,
                        accent       = accent,
                        iconInactive = iconInactive,
                        isHorizontal = true,
                        onClick      = { 
                            if (currentDest == NavDestination.SETTINGS) onNavigate(NavDestination.HOME) 
                            else onNavigate(NavDestination.SETTINGS) 
                        }
                    )
                    NavButton(
                        icon         = Icons.Default.Apps,
                        label        = "Apps",
                        isActive     = currentDest == NavDestination.APP_LIBRARY,
                        accent       = accent,
                        iconInactive = iconInactive,
                        isHorizontal = true,
                        onClick      = { 
                            if (currentDest == NavDestination.APP_LIBRARY) onNavigate(NavDestination.HOME) 
                            else onNavigate(NavDestination.APP_LIBRARY) 
                        }
                    )
                    editButtons()
                }

                // Shortcuts centered in the middle
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    shortcutsContent()
                }

                // Clock and status icons pinned to the RIGHT
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    statusIcons()
                    Spacer(Modifier.width(16.dp))
                    clockContent()
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .width(SIDEBAR_W)
                .fillMaxHeight()
                .background(sidebarBg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            clockContent()
            statusIcons()
            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 2.dp, bottom = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                shortcutsContent()
            }

            HorizontalDivider(color = dividerColor)
            editButtons()
            navButtons()
            Spacer(Modifier.height(4.dp))
        }
    }

    // ── Action sheet dialog ──────────────────────────────────────────────────
    actionSheetSlot?.let { slot ->
        ShortcutActionDialog(
            accent      = accent,
            onChangeApp = {
                actionSheetSlot = null
                onShortcutLongPress(slot)
            },
            onCustomizeIcon = {
                actionSheetSlot = null
                iconPickerSlot = slot
            },
            onRemove = {
                actionSheetSlot = null
                onShortcutRemove(slot)
            },
            onDismiss = { actionSheetSlot = null }
        )
    }

    // ── Icon picker dialog ───────────────────────────────────────────────────
    iconPickerSlot?.let { slot ->
        IconPickerDialog(
            accent          = accent,
            hasNativeIcon   = settings.shortcuts.getOrNull(slot)?.packageName?.isNotEmpty() == true,
            currentOverride = settings.shortcuts.getOrNull(slot)?.customIconOverride,
            onPick  = { icon ->
                iconPickerSlot = null
                onShortcutSetIcon(slot, icon)
            },
            onDismiss = { iconPickerSlot = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutSlot(
    shortcut: ShortcutConfig,
    accent: Color,
    resolvedIcon: Drawable?,
    isDragging: Boolean,
    dragTranslation: Float,
    isHorizontal: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val currentOnClick      by rememberUpdatedState(onClick)
    val currentOnLongPress  by rememberUpdatedState(onLongPress)
    val currentOnDragStart  by rememberUpdatedState(onDragStart)
    val currentOnDragDelta  by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd    by rememberUpdatedState(onDragEnd)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(
                if (isHorizontal) Modifier.fillMaxHeight().width(size)
                else              Modifier.fillMaxWidth().height(size)
            )
            .padding(4.dp)
            .clip(MaterialTheme.shapes.medium)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                if (isHorizontal) translationX = dragTranslation else translationY = dragTranslation
                alpha = if (isDragging) 0.55f else 1f
            }
            .combinedClickable(
                onClick     = { currentOnClick() },
                onLongClick = { }
            )
            .pointerInput(isHorizontal) {
                var longPressTriggered = false
                var hasSignificantDrag = false
                var totalDrag          = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { _ ->
                        longPressTriggered = true
                        hasSignificantDrag = false
                        totalDrag          = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val delta = if (isHorizontal) dragAmount.x else dragAmount.y
                        totalDrag += delta
                        if (!hasSignificantDrag && kotlin.math.abs(totalDrag) > viewConfiguration.touchSlop) {
                            hasSignificantDrag = true
                            currentOnDragStart()
                        }
                        if (hasSignificantDrag) currentOnDragDelta(delta)
                    },
                    onDragEnd = {
                        if (longPressTriggered && !hasSignificantDrag) currentOnLongPress()
                        if (hasSignificantDrag) currentOnDragEnd()
                        longPressTriggered = false
                        hasSignificantDrag = false
                        totalDrag          = 0f
                    },
                    onDragCancel = {
                        if (hasSignificantDrag) currentOnDragEnd()
                        longPressTriggered = false
                        hasSignificantDrag = false
                        totalDrag          = 0f
                    }
                )
            }
    ) {
        val iconInactive = if (LocalDayMode.current) Color(0xFF777777) else Color(0xFF3A3A3A)
        val override = shortcut.customIconOverride
        when {
            override != null && override != DefaultShortcutIcon.NONE -> {
                Icon(
                    imageVector        = override.toIcon(),
                    contentDescription = shortcut.label,
                    tint               = iconInactive,
                    modifier           = Modifier.size(SHORTCUT_ICON_SIZE)
                )
            }
            resolvedIcon != null -> {
                // Cache per icon — every slot recomposes each drag frame, and an
                // un-remembered toBitmap allocated a fresh bitmap per slot per frame
                val bmp = remember(resolvedIcon) { resolvedIcon.toBitmap(80, 80) }
                Icon(
                    painter            = BitmapPainter(bmp.asImageBitmap()),
                    contentDescription = shortcut.label,
                    tint               = Color.Unspecified,
                    modifier           = Modifier.size(SHORTCUT_ICON_SIZE)
                )
            }
            shortcut.isDefault -> {
                Icon(
                    imageVector        = shortcut.defaultIcon.toIcon(),
                    contentDescription = shortcut.label,
                    tint               = iconInactive,
                    modifier           = Modifier.size(SHORTCUT_ICON_SIZE)
                )
            }
            else -> {
                Icon(
                    imageVector        = Icons.Default.Add,
                    contentDescription = "Add shortcut",
                    tint               = if (LocalDayMode.current) Color(0xFFBBBBBB) else Color(0xFF252525),
                    modifier           = Modifier.size(SHORTCUT_ICON_SIZE)
                )
            }
        }
    }
}

@Composable
private fun ShortcutActionDialog(
    accent: Color,
    onChangeApp: () -> Unit,
    onCustomizeIcon: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF111111))
                .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp)
                .width(180.dp)
        ) {
            ActionRow("CHANGE APP",     Icons.Default.SwapHoriz, accent, onChangeApp)
            HorizontalDivider(color = Color(0xFF1A1A1A))
            ActionRow("CUSTOMIZE ICON", Icons.Default.Palette,   accent, onCustomizeIcon)
            HorizontalDivider(color = Color(0xFF1A1A1A))
            ActionRow("REMOVE",         Icons.Default.Delete,     Color(0xFF993333), onRemove)
        }
    }
}

@Composable
private fun ActionRow(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, color = tint, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun IconPickerDialog(
    accent: Color,
    hasNativeIcon: Boolean,
    currentOverride: DefaultShortcutIcon?,
    onPick: (DefaultShortcutIcon?) -> Unit,
    onDismiss: () -> Unit
) {
    val vectorOptions = DefaultShortcutIcon.entries.filter { it != DefaultShortcutIcon.NONE }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF111111))
                .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                .padding(12.dp)
        ) {
            Text(
                "CHOOSE ICON",
                color         = Color(0xFF888888),
                fontSize      = 9.sp,
                letterSpacing = 2.sp,
                modifier      = Modifier.padding(bottom = 10.dp)
            )

            if (hasNativeIcon) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (currentOverride == null) accent.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onPick(null) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Apps, null, tint = if (currentOverride == null) accent else Color(0xFF666666), modifier = Modifier.size(18.dp))
                    Text(
                        "NATIVE APP ICON",
                        color         = if (currentOverride == null) accent else Color(0xFF888888),
                        fontSize      = 9.sp,
                        letterSpacing = 1.sp
                    )
                }
                HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 6.dp))
            }

            LazyVerticalGrid(
                columns               = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement   = Arrangement.spacedBy(5.dp),
                modifier              = Modifier.heightIn(max = 300.dp)
            ) {
                items(vectorOptions) { iconOption ->
                    val isSelected = currentOverride == iconOption
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) accent.copy(alpha = 0.18f) else Color(0xFF1A1A1A))
                            .clickable { onPick(iconOption) }
                    ) {
                        Icon(
                            imageVector        = iconOption.toIcon(),
                            contentDescription = iconOption.name,
                            tint               = if (isSelected) accent else Color(0xFF888888),
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    accent: Color,
    iconInactive: Color,
    isHorizontal: Boolean = false,
    onClick: () -> Unit
) {
    val isDayMode = LocalDayMode.current
    val activeIconColor = if (isDayMode) Color(0xFF111111) else Color.White
    val activeBg = if (isDayMode) Color(0xFF000000).copy(alpha = 0.08f) else Color.White.copy(alpha = 0.06f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(
                if (isHorizontal) Modifier.fillMaxHeight().width(NAV_SLOT_SIZE)
                else              Modifier.fillMaxWidth().height(NAV_SLOT_SIZE)
            )
            .padding(2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (isActive) activeBg else Color.Transparent)
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = if (isActive) activeIconColor else iconInactive,
            modifier           = Modifier.size(ICON_SIZE)
        )
    }
}

fun DefaultShortcutIcon.toIcon(): ImageVector = when (this) {
    // Navigation & vehicle
    DefaultShortcutIcon.RADIO       -> Icons.Default.Radio
    DefaultShortcutIcon.CAMERA      -> Icons.Default.CameraAlt
    DefaultShortcutIcon.PHONE       -> Icons.Default.Phone
    DefaultShortcutIcon.MAP         -> Icons.Default.Map
    DefaultShortcutIcon.NAVIGATION  -> Icons.Default.Navigation
    DefaultShortcutIcon.CAR         -> Icons.Default.DirectionsCar
    DefaultShortcutIcon.GAS_STATION -> Icons.Default.LocalGasStation
    DefaultShortcutIcon.DASHBOARD   -> Icons.Default.Speed
    // Audio & media
    DefaultShortcutIcon.MUSIC       -> Icons.Default.MusicNote
    DefaultShortcutIcon.SPEAKER     -> Icons.Default.Speaker
    DefaultShortcutIcon.HEADSET     -> Icons.Default.Headset
    DefaultShortcutIcon.EQUALIZER   -> Icons.Default.Equalizer
    DefaultShortcutIcon.VOLUME_UP   -> Icons.Default.VolumeUp
    // Connectivity
    DefaultShortcutIcon.BLUETOOTH   -> Icons.Default.Bluetooth
    DefaultShortcutIcon.WIFI        -> Icons.Default.Wifi
    // Lighting & climate
    DefaultShortcutIcon.LIGHTBULB   -> Icons.Default.Lightbulb
    DefaultShortcutIcon.BRIGHTNESS  -> Icons.Default.BrightnessHigh
    DefaultShortcutIcon.AC          -> Icons.Default.AcUnit
    DefaultShortcutIcon.THERMOSTAT  -> Icons.Default.Thermostat
    // General utility
    DefaultShortcutIcon.TV          -> Icons.Default.Tv
    DefaultShortcutIcon.VIDEOCAM    -> Icons.Default.Videocam
    DefaultShortcutIcon.STAR        -> Icons.Default.Star
    DefaultShortcutIcon.MESSAGE     -> Icons.Default.Message
    DefaultShortcutIcon.TIMER       -> Icons.Default.Timer
    DefaultShortcutIcon.LOCK        -> Icons.Default.Lock
    DefaultShortcutIcon.SETTINGS    -> Icons.Default.Settings
    DefaultShortcutIcon.FAVORITE    -> Icons.Default.Favorite
    // Web / location
    DefaultShortcutIcon.GLOBE       -> Icons.Default.Language
    DefaultShortcutIcon.NONE        -> Icons.Default.Apps
}
