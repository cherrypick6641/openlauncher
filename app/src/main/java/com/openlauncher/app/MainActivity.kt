package com.openlauncher.app

import android.Manifest
import android.content.Intent
import android.graphics.drawable.Drawable
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.GradientDirection
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.WeatherState
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
            val wifiLevel   by vm.wifiLevel.collectAsStateWithLifecycle()
            val mobileLevel by vm.mobileLevel.collectAsStateWithLifecycle()
            val satelliteCount by vm.satelliteCount.collectAsStateWithLifecycle()
            val isDayModeVM by vm.isDayMode.collectAsStateWithLifecycle()
            val appIconMap  by vm.appIconMap.collectAsStateWithLifecycle()
            val systemIsDark = isSystemInDarkTheme()
            val isDayMode = if (settings.dayNightMode == DayNightMode.SYSTEM) !systemIsDark else isDayModeVM
            val pickerSlot      by vm.shortcutPickerSlot.collectAsStateWithLifecycle()
            val appPickerTarget by vm.appPickerTarget.collectAsStateWithLifecycle()

            AppAutostartHandler(
                settingsLoaded = settingsLoaded,
                autostartPackages = settings.autostartPackages,
                autostartDelay = settings.autostartDelay,
                onLaunchApp = vm::launchApp
            )

            AppMediaBootHandler(
                settingsLoaded = settingsLoaded,
                playMediaOnBoot = settings.playMediaOnBoot,
                onPlayMedia = { vm.playLastOrOpenActive(this@MainActivity) }
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
                    GradientDirection.TOP_TO_BOTTOM -> Brush.verticalGradient(colors)
                    GradientDirection.LEFT_TO_RIGHT -> Brush.horizontalGradient(colors)
                    GradientDirection.DIAGONAL -> Brush.linearGradient(colors)
                    GradientDirection.RADIAL -> Brush.radialGradient(colors)
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
                        wifiLevel = wifiLevel,
                        mobileLevel = mobileLevel,
                        satelliteCount = satelliteCount,
                        isDayMode = isDayMode,
                        appIconMap = appIconMap,
                        pickerSlot = pickerSlot,
                        appPickerTarget = appPickerTarget,
                        accent = accent,
                        bg = bg,
                        bgBrush = bgBrush,
                        vm = vm
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
private fun AppMediaBootHandler(
    settingsLoaded: Boolean,
    playMediaOnBoot: Boolean,
    onPlayMedia: () -> Unit
) {
    var mediaPlayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settingsLoaded) {
        if (settingsLoaded && playMediaOnBoot && !mediaPlayed) {
            delay(1000)
            onPlayMedia()
            mediaPlayed = true
        }
    }
}

@Composable
private fun MainContentShell(
    settings: AppSettings,
    nav: NavDestination,
    apps: List<AppInfo>,
    appsLoading: Boolean,
    nowPlaying: NowPlayingState?,
    weather: WeatherState?,
    wifiLevel: Int,
    mobileLevel: Int,
    satelliteCount: Int = 0,
    isDayMode: Boolean,
    appIconMap: Map<String, Drawable>,
    pickerSlot: Int?,
    appPickerTarget: LauncherViewModel.AppPickerTarget?,
    accent: Color,
    bg: Color,
    bgBrush: Brush?,
    vm: LauncherViewModel
) {
    val context = LocalContext.current
    
    if (!settings.onboardingCompleted) {
        OnboardingScreen(
            accent = accent,
            onComplete = {
                vm.updateSettings { copy(onboardingCompleted = true) }
                vm.startLocationUpdates()
            }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(bg)) {
            val layoutDivColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF151515)

            val baseDensity = LocalDensity.current
            val sidebarContent: @Composable () -> Unit = {
                val sidebarDensity = Density(
                    density = baseDensity.density * (1.0f + (settings.uiScale - 1.0f) * 0.35f),
                    fontScale = baseDensity.fontScale
                )
                CompositionLocalProvider(LocalDensity provides sidebarDensity) {
                    Sidebar(
                        currentDest   = nav,
                        settings      = settings,
                        installedIconFor = { pkg -> appIconMap[pkg] },
                        onNavigate    = { dest ->
                            vm.cancelShortcutPicker()
                            vm.cancelCarPlayPicker()
                            vm.exitRearrangeMode()
                            vm.navigate(dest)
                        },
                        onShortcutClick = { slot ->
                            val shortcut = settings.shortcuts.getOrNull(slot)
                            if (shortcut != null && shortcut.packageName.isNotEmpty()) {
                                vm.updateSettings { copy(pipAppPackage = shortcut.packageName) }
                            }
                        },
                        onShortcutLongPress  = { slot -> vm.startShortcutPicker(slot) },
                        onShortcutRemove     = { slot -> vm.removeShortcut(slot) },
                        onShortcutSetIcon    = { slot, icon -> vm.setShortcutIcon(slot, icon) },
                        onReorder            = { from, to -> vm.reorderShortcut(from, to) },
                        wifiLevel            = wifiLevel,
                        mobileLevel          = mobileLevel,
                        satelliteCount       = satelliteCount
                    )
                }
            }

            val mainPane: @Composable (Modifier) -> Unit = { paneModifier ->
                Box(modifier = paneModifier) {
                    HomeScreen(
                        settings            = settings,
                        weather             = weather,
                        nowPlaying          = nowPlaying,
                        isDayMode           = isDayMode,
                        onPlayPause         = { vm.playPause(context) },
                        onTapNowPlaying     = {
                            val pkg = nowPlaying?.controller?.packageName
                            if (!pkg.isNullOrEmpty()) vm.launchApp(pkg)
                            vm.playLastOrOpenActive(context)
                        },
                        isOverlayOpen       = nav != NavDestination.HOME,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay screens
                    AnimatedVisibility(
                        visible = nav == NavDestination.APP_LIBRARY,
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
                            onOpenSettings      = { vm.navigate(NavDestination.SETTINGS) },
                            modifier = Modifier.fillMaxSize().background(bg).clickable(onClick = {})
                        )
                    }

                    AnimatedVisibility(
                        visible = nav == NavDestination.SETTINGS,
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

            Row(modifier = Modifier.fillMaxSize()) {
                sidebarContent()
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color    = layoutDivColor
                )
                mainPane(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}
