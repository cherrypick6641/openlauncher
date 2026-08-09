package com.openlauncher.app

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.GradientDirection
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.ui.components.Sidebar
import com.openlauncher.app.ui.screen.AppLibraryScreen
import com.openlauncher.app.ui.screen.HomeScreen
import com.openlauncher.app.ui.screen.OnboardingScreen
import com.openlauncher.app.ui.screen.SettingsScreen
import com.openlauncher.app.ui.theme.OpenLauncherTheme
import com.openlauncher.app.viewmodel.LauncherViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    private val widgetPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val appWidgetId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (result.resultCode == RESULT_OK) {
            if (appWidgetId != -1) {
                val info = vm.appWidgetManager.getAppWidgetInfo(appWidgetId)
                if (info?.configure != null) {
                    val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    intent.component = info.configure
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    widgetConfigLauncher.launch(intent)
                } else {
                    vm.addAndroidWidget(appWidgetId)
                }
            }
        } else if (appWidgetId != -1) {
            vm.appWidgetHost.deleteAppWidgetId(appWidgetId)
        }
    }

    private val widgetConfigLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val appWidgetId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (result.resultCode == RESULT_OK) {
            if (appWidgetId != -1) vm.addAndroidWidget(appWidgetId)
        } else if (appWidgetId != -1) {
            vm.appWidgetHost.deleteAppWidgetId(appWidgetId)
        }
    }

    private fun startWidgetPicker() {
        val appWidgetId = vm.appWidgetHost.allocateAppWidgetId()
        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
        pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        widgetPicker.launch(pickIntent)
    }

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.startLocationUpdates()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Reset to home screen when the HOME button is pressed
        vm.navigate(NavDestination.HOME)
        vm.exitRearrangeMode()
        vm.setWidgetLibraryOpen(false)
        vm.cancelShortcutPicker()
        vm.cancelCarPlayPicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        setContent {
            val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
            val settings       by vm.settings.collectAsStateWithLifecycle()
            val nav            by vm.nav.collectAsStateWithLifecycle()
            val apps        by vm.apps.collectAsStateWithLifecycle()
            val appsLoading by vm.appsLoading.collectAsStateWithLifecycle()
            val nowPlaying  by vm.nowPlaying.collectAsStateWithLifecycle()
            val weather     by vm.weather.collectAsStateWithLifecycle()
            val location    by vm.location.collectAsStateWithLifecycle()
            val bearing     by vm.compassBearing.collectAsStateWithLifecycle()
            val wifiLevel   by vm.wifiLevel.collectAsStateWithLifecycle()
            val mobileLevel by vm.mobileLevel.collectAsStateWithLifecycle()
            val isDayModeVM by vm.isDayMode.collectAsStateWithLifecycle()
            val hardwareRadio by vm.hardwareRadio.collectAsStateWithLifecycle()
            val appIconMap  by vm.appIconMap.collectAsStateWithLifecycle()
            val systemIsDark = isSystemInDarkTheme()
            val isDayMode = if (settings.dayNightMode == DayNightMode.SYSTEM) !systemIsDark else isDayModeVM
            val pickerSlot      by vm.shortcutPickerSlot.collectAsStateWithLifecycle()
            val appPickerTarget by vm.appPickerTarget.collectAsStateWithLifecycle()

            val editMode by vm.rearrangeMode.collectAsStateWithLifecycle()
            val widgetLibraryOpen by vm.widgetLibraryOpen.collectAsStateWithLifecycle()

            AppAutostartHandler(
                settingsLoaded = settingsLoaded,
                autostartPackages = settings.autostartPackages,
                autostartDelay = settings.autostartDelay,
                onLaunchApp = vm::launchApp
            )

            val accent         = Color(settings.accentColor)
            val bg             = if (settings.useCustomBackgroundColor) {
                Color(settings.backgroundColor)
            } else {
                if (isDayMode) Color(0xFFEEEEEE) else Color.Black
            }
            val textColor      = if (isDayMode) Color(0xFF111111) else Color(settings.fontColor)
            val bgGradientEnd  = Color(settings.gradientEndColor)
            val bgBrush        = if (settings.useCustomBackgroundColor && settings.useGradient) {
                val colors = listOf(bg, bgGradientEnd)
                when (settings.gradientDirection) {
                    GradientDirection.TOP_TO_BOTTOM -> androidx.compose.ui.graphics.Brush.verticalGradient(colors)
                    GradientDirection.LEFT_TO_RIGHT -> androidx.compose.ui.graphics.Brush.horizontalGradient(colors)
                    GradientDirection.DIAGONAL -> androidx.compose.ui.graphics.Brush.linearGradient(colors)
                    GradientDirection.RADIAL -> androidx.compose.ui.graphics.Brush.radialGradient(colors)
                }
            } else null

            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density   = baseDensity.density * settings.uiScale,
                    fontScale = baseDensity.fontScale
                )
            ) {
                if (!settingsLoaded) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                } else OpenLauncherTheme(
                    accent     = accent,
                    background = bg,
                    textColor  = textColor,
                    fontBold   = settings.fontBold,
                    textScale  = settings.textScale,
                    isDayMode  = isDayMode,
                    useCustomBg = settings.useCustomBackgroundColor
                ) {
                    MainContentShell(
                        settings = settings,
                        nav = nav,
                        apps = apps,
                        appsLoading = appsLoading,
                        nowPlaying = nowPlaying,
                        weather = weather,
                        location = location,
                        bearing = bearing,
                        wifiLevel = wifiLevel,
                        mobileLevel = mobileLevel,
                        isDayMode = isDayMode,
                        hardwareRadio = hardwareRadio,
                        appIconMap = appIconMap,
                        pickerSlot = pickerSlot,
                        appPickerTarget = appPickerTarget,
                        editMode = editMode,
                        onSetEditMode = { vm.toggleRearrangeMode() },
                        onSetNowPlayingCompact = { compact -> vm.updateSettings { copy(nowPlayingCompact = compact) } },
                        widgetLibraryOpen = widgetLibraryOpen,
                        onSetWidgetLibraryOpen = { vm.setWidgetLibraryOpen(it) },
                        accent = accent,
                        bg = bg,
                        bgBrush = bgBrush,
                        vm = vm,
                        startWidgetPicker = ::startWidgetPicker,
                        onPlayPause = { vm.playPause(this@MainActivity) }
                    )
                }
            } // CompositionLocalProvider
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshConnectivity()
        vm.refreshMedia()
    }

    override fun onStop() {
        super.onStop()
        vm.stopListeningWidgets()
        vm.stopLocationUpdates()
    }

    override fun onStart() {
        super.onStart()
        vm.startListeningWidgets()
        vm.startLocationUpdates()
    }
}

@Composable
private fun AppAutostartHandler(
    settingsLoaded: Boolean,
    autostartPackages: List<String>,
    autostartDelay: Int,
    onLaunchApp: (String) -> Unit
) {
    var autostartLaunched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settingsLoaded) {
        if (settingsLoaded && !autostartLaunched) {
            val appsToLaunch = autostartPackages.filter { it.isNotEmpty() }
            if (appsToLaunch.isNotEmpty()) {
                delay(autostartDelay * 1000L)
                appsToLaunch.forEach { pkg ->
                    onLaunchApp(pkg)
                    delay(500)
                }
                autostartLaunched = true
            }
        }
    }
}

@Composable
private fun MainContentShell(
    settings: com.openlauncher.app.data.AppSettings,
    nav: com.openlauncher.app.model.NavDestination,
    apps: List<com.openlauncher.app.model.AppInfo>,
    appsLoading: Boolean,
    nowPlaying: com.openlauncher.app.model.NowPlayingState?,
    weather: com.openlauncher.app.model.WeatherState?,
    location: com.openlauncher.app.util.LocationData?,
    bearing: Float,
    wifiLevel: Int,
    mobileLevel: Int,
    isDayMode: Boolean,
    hardwareRadio: com.openlauncher.app.viewmodel.LauncherViewModel.HardwareRadioState?,
    appIconMap: Map<String, android.graphics.drawable.Drawable>,
    pickerSlot: Int?,
    appPickerTarget: com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget?,
    editMode: Boolean,
    onSetEditMode: (Boolean) -> Unit,
    onSetNowPlayingCompact: (Boolean) -> Unit,
    widgetLibraryOpen: Boolean,
    onSetWidgetLibraryOpen: (Boolean) -> Unit,
    accent: Color,
    bg: Color,
    bgBrush: androidx.compose.ui.graphics.Brush?,
    vm: com.openlauncher.app.viewmodel.LauncherViewModel,
    startWidgetPicker: () -> Unit,
    onPlayPause: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    if (!settings.onboardingCompleted) {
        OnboardingScreen(
            accent = accent,
            onComplete = {
                vm.updateSettings { copy(onboardingCompleted = true) }
                vm.startLocationUpdates()
            }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().let { m ->
            if (bgBrush != null) m.background(bgBrush) else m.background(bg)
        }) {
            // Optional wallpaper layer
            if (settings.wallpaperUri.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model              = android.net.Uri.parse(settings.wallpaperUri),
                    contentDescription = null,
                    contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
                Box(modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = settings.wallpaperDim)))
            }

            val isBottomBar    = settings.sidebarPosition == com.openlauncher.app.data.SidebarPosition.BOTTOM
            val layoutDivColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF000000)

            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            val sidebarContent: @Composable () -> Unit = {
                val sidebarDensity = Density(
                    density = baseDensity.density * (1.0f + (settings.uiScale - 1.0f) * 0.35f),
                    fontScale = baseDensity.fontScale
                )
                CompositionLocalProvider(LocalDensity provides sidebarDensity) {
                    Sidebar(
                        currentDest   = nav,
                        settings      = settings,
                        isHorizontal  = isBottomBar,
                        installedIconFor = { pkg -> appIconMap[pkg] },
                        onNavigate    = { dest ->
                            vm.cancelShortcutPicker()
                            vm.cancelCarPlayPicker()
                            vm.exitRearrangeMode()
                            vm.navigate(dest)
                        },
                        onShortcutClick = { slot ->
                            val shortcut = settings.shortcuts[slot]
                            if (shortcut.packageName.isNotEmpty()) {
                                if (settings.showPip) {
                                    vm.updateSettings { copy(pipAppPackage = shortcut.packageName) }
                                } else {
                                    vm.launchApp(shortcut.packageName)
                                }
                            }
                        },
                        onShortcutLongPress  = { slot -> vm.startShortcutPicker(slot) },
                        onShortcutRemove     = { slot -> vm.removeShortcut(slot) },
                        onShortcutSetIcon    = { slot, icon -> vm.setShortcutIcon(slot, icon) },
                        onReorder            = { from, to -> vm.reorderShortcut(from, to) },
                        wifiLevel            = wifiLevel,
                        mobileLevel          = mobileLevel,
                        editMode             = editMode,
                        onToggleEditMode     = { onSetEditMode(!editMode) },
                        onOpenWidgetLibrary  = { onSetWidgetLibraryOpen(true) }
                    )
                }
            }

            val mainPane: @Composable (Modifier) -> Unit = { paneModifier ->
                Box(modifier = paneModifier) {
                    HomeScreen(
                        settings            = settings,
                        weather             = weather,
                        nowPlaying          = nowPlaying,
                        location            = location,
                        bearing             = bearing,
                        isDayMode           = isDayMode,
                        onPlayPause         = onPlayPause,
                        onNext              = vm::skipNext,
                        onPrev              = vm::skipPrev,
                        onLaunchCarPlay     = { vm.launchApp(settings.carPlayPackage) },
                        onLaunchAndroidAuto = { vm.launchApp(settings.androidAutoPackage) },
                        onAssignCarPlay     = { vm.startCarPlayPicker() },
                        onAssignAndroidAuto = { vm.startAndroidAutoPicker() },
                        onClearCarPlay      = { vm.clearCarPlayApp() },
                        onClearAndroidAuto  = { vm.clearAndroidAutoApp() },
                        onAssignPip         = { vm.startPipPicker() },
                        onClearPip          = { vm.clearPipApp() },
                        onLaunchPip         = { vm.launchApp(settings.pipAppPackage) },
                        onAddAndroidWidget  = { startWidgetPicker() },
                        onTapNowPlaying     = {
                            val pkg = nowPlaying?.controller?.packageName
                            if (!pkg.isNullOrEmpty()) vm.launchApp(pkg)
                            vm.playLastOrOpenActive(context)
                        },
                        onUpdateWidget      = { id, sx, sy -> vm.updateWidgetConfig(id, sx, sy) },
                        onMoveWidget        = { id, gx, gy -> vm.moveWidgetConfig(id, gx, gy) },
                        onAddWidget         = { id -> vm.addWidget(id) },
                        onRemoveWidget      = { id -> vm.removeWidget(id) },
                        onSetClockStyle     = { style -> vm.updateSettings { copy(clockStyle = style) } },
                        onSetNowPlayingCompact = onSetNowPlayingCompact,
                        onSetVitalsAsBars   = { asBars -> vm.updateSettings { copy(vitalsAsBars = asBars) } },
                        onSetSpeedometerDigitalOnly = { digital -> vm.updateSettings { copy(speedometerDigitalOnly = digital) } },
                        onUpdateSoundPad    = { idx, pad -> vm.updateSoundboardPad(idx, pad) },
                        hardwareRadio         = hardwareRadio,
                        onLaunchHardwareRadio = { vm.launchHardwareRadioApp() },
                        onStopHardwareRadio   = { vm.stopHardwareRadioApp() },
                        onRadioSeekUp         = { vm.radioSeekUp() },
                        onRadioSeekDown       = { vm.radioSeekDown() },
                        onRadioCycleFm        = { vm.radioCycleFm() },
                        onRadioSwitchAm       = { vm.radioSwitchAm() },
                        onRadioTune           = { band, freq -> vm.radioTune(band, freq) },
                        onAssignRadio         = { vm.startRadioPicker() },
                        onToggleMapProvider = { vm.toggleMapProvider() },
                        onToggleTraffic     = { vm.toggleTraffic() },
                        onSetMapType        = { vm.setMapType(it) },
                        appWidgetHost       = vm.appWidgetHost,
                        editMode            = editMode,
                        onToggleEditMode    = { onSetEditMode(!editMode) },
                        widgetLibraryOpen   = widgetLibraryOpen,
                        onSetWidgetLibraryOpen = { onSetWidgetLibraryOpen(it) },
                        isOverlayOpen       = nav != com.openlauncher.app.model.NavDestination.HOME,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay screens
                    AnimatedVisibility(
                        visible = nav == com.openlauncher.app.model.NavDestination.APP_LIBRARY,
                        enter = fadeIn() + slideInHorizontally { it / 10 },
                        exit = fadeOut() + slideOutHorizontally { -it / 10 }
                    ) {
                        AppLibraryScreen(
                            apps                = apps,
                            isLoading           = appsLoading,
                            isPickerMode        = pickerSlot != null,
                            pickerSlot          = pickerSlot,
                            isCarPlayPickerMode = appPickerTarget != null,
                            carPlayPickerLabel  = when (appPickerTarget) {
                                LauncherViewModel.AppPickerTarget.ANDROID_AUTO -> "CHOOSE ANDROID AUTO APP"
                                LauncherViewModel.AppPickerTarget.PIP          -> "CHOOSE PIP APP"
                                LauncherViewModel.AppPickerTarget.RADIO        -> "CHOOSE RADIO APP"
                                LauncherViewModel.AppPickerTarget.AUTOSTART_1 -> "CHOOSE AUTOSTART APP 1"
                                LauncherViewModel.AppPickerTarget.AUTOSTART_2 -> "CHOOSE AUTOSTART APP 2"
                                LauncherViewModel.AppPickerTarget.AUTOSTART_3 -> "CHOOSE AUTOSTART APP 3"
                                LauncherViewModel.AppPickerTarget.AUTOSTART_4 -> "CHOOSE AUTOSTART APP 4"
                                else -> "CHOOSE CARPLAY APP"
                            },
                            accent              = accent,
                            onAppClick          = { app -> vm.launchApp(app.packageName) },
                            onPickerSelect      = { slot, app -> vm.assignShortcut(slot, app) },
                            onCarPlaySelect     = { app -> vm.assignPickerApp(app) },
                            modifier = Modifier.fillMaxSize().background(bg).clickable(onClick = {})
                        )
                    }

                    AnimatedVisibility(
                        visible = nav == com.openlauncher.app.model.NavDestination.SETTINGS,
                        enter = fadeIn() + slideInHorizontally { it / 10 },
                        exit = fadeOut() + slideOutHorizontally { -it / 10 }
                    ) {
                        SettingsScreen(
                            settings = settings,
                            accent   = accent,
                            onUpdate = { block -> vm.updateSettings(block) },
                            onReset  = { vm.resetSettings() },
                            onAssignAutostart = { slot -> vm.startAutostartPicker(slot) },
                            onClearAutostart = { slot -> vm.clearAutostartApp(slot) },
                            modifier = Modifier.fillMaxSize().background(bg).clickable(onClick = {})
                        )
                    }
                }
            }

            if (isBottomBar) {
                Column(modifier = Modifier.fillMaxSize()) {
                    mainPane(Modifier.weight(1f).fillMaxWidth())
                    androidx.compose.material3.HorizontalDivider(color = layoutDivColor)
                    sidebarContent()
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    val vDivider: @Composable () -> Unit = {
                        androidx.compose.material3.VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            color    = layoutDivColor
                        )
                    }
                    if (settings.sidebarPosition == com.openlauncher.app.data.SidebarPosition.LEFT) {
                        sidebarContent()
                        vDivider()
                    }
                    mainPane(Modifier.weight(1f).fillMaxHeight())
                    if (settings.sidebarPosition == com.openlauncher.app.data.SidebarPosition.RIGHT) {
                        vDivider()
                        sidebarContent()
                    }
                }
            }
        }
    }
}
