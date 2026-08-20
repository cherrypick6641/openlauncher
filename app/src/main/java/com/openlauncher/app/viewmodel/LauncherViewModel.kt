package com.openlauncher.app.viewmodel

import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.DefaultShortcutIcon
import com.openlauncher.app.data.GRID_COLS
import com.openlauncher.app.data.GRID_ROWS
import com.openlauncher.app.data.MapProvider
import com.openlauncher.app.data.SettingsRepository
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.data.SoundPadConfig
import com.openlauncher.app.data.WeatherApi
import com.openlauncher.app.data.activeWidgetIds
import com.openlauncher.app.data.computeWidgetMove
import com.openlauncher.app.data.defaultShortcuts
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.service.MediaListenerService
import com.openlauncher.app.util.LocationCompassManager
import com.openlauncher.app.util.LocationData
import com.openlauncher.app.util.SunriseSunset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val APPWIDGET_HOST_ID = 1024
    }

    private val settingsRepo = SettingsRepository(application)
    private val locationMgr  = LocationCompassManager(application)
    
    // Android AppWidget Support
    val appWidgetHost = AppWidgetHost(application, APPWIDGET_HOST_ID)
    val appWidgetManager = AppWidgetManager.getInstance(application)

    fun startListeningWidgets() { appWidgetHost.startListening() }
    fun stopListeningWidgets()  { appWidgetHost.stopListening() }

    fun addAndroidWidget(appWidgetId: Int) {
        updateSettings {
            val activeIds = activeWidgetIds()
            var currentLayout = widgetLayout
            
            // Try to find a 2x2 area first, then fallback to 1x1 if needed
            var targetSpanX = 2
            var targetSpanY = 2
            var cell = freeAreaIn(currentLayout, activeIds, targetSpanX, targetSpanY)

            if (cell == null) {
                // Try 1x1
                targetSpanX = 1
                targetSpanY = 1
                cell = freeAreaIn(currentLayout, activeIds, targetSpanX, targetSpanY)
            }

            if (cell == null) {
                // Try to shrink something to make room for at least 1x1
                val candidate = currentLayout
                    .filter { it.enabled && it.id in activeIds && it.spanX * it.spanY > 1 }
                    .maxByOrNull { it.spanX * it.spanY }
                if (candidate != null) {
                    currentLayout = currentLayout.map { w ->
                        if (w.id == candidate.id)
                            if (w.spanY > 1) w.copy(spanY = w.spanY - 1) else w.copy(spanX = w.spanX - 1)
                        else w
                    }
                    cell = freeAreaIn(currentLayout, activeIds, targetSpanX, targetSpanY)
                }
            }

            val targetCell = cell ?: return@updateSettings this
            val uniqueId = "ANDROID_WIDGET_$appWidgetId"
            
            val newWidget = com.openlauncher.app.data.WidgetConfig(
                id = uniqueId,
                gridX = targetCell.first,
                gridY = targetCell.second,
                spanX = targetSpanX,
                spanY = targetSpanY,
                appWidgetId = appWidgetId
            )
            copy(widgetLayout = currentLayout + newWidget)
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow
        .onEach { _settingsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun updateSettings(block: AppSettings.() -> AppSettings) {
        viewModelScope.launch { settingsRepo.updateSettings { it.block() } }
    }

    fun resetSettings() {
        viewModelScope.launch { settingsRepo.resetToDefaults() }
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    private val _nav = MutableStateFlow(NavDestination.HOME)
    val nav: StateFlow<NavDestination> = _nav

    fun navigate(dest: NavDestination) { _nav.value = dest }

    // ── Shortcut picker ───────────────────────────────────────────────────────
    private val _shortcutPickerSlot = MutableStateFlow<Int?>(null)
    val shortcutPickerSlot: StateFlow<Int?> = _shortcutPickerSlot

    fun startShortcutPicker(slot: Int) {
        _shortcutPickerSlot.value = slot
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun assignShortcut(slot: Int, app: AppInfo) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                while (list.size <= slot) list.add(ShortcutConfig())
                list[slot] = ShortcutConfig(packageName = app.packageName, label = app.appName)
            })
        }
        _shortcutPickerSlot.value = null
        _nav.value = NavDestination.HOME
    }

    fun removeShortcut(slot: Int) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                if (slot in list.indices) {
                    list[slot] = defaultShortcuts().getOrNull(slot) ?: ShortcutConfig()
                }
            })
        }
    }

    fun reorderShortcut(from: Int, to: Int) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                val item = list.removeAt(from)
                list.add(to.coerceIn(0, list.size), item)
            })
        }
    }

    fun setShortcutIcon(slot: Int, icon: DefaultShortcutIcon?) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                if (slot in list.indices) list[slot] = list[slot].copy(customIconOverride = icon)
            })
        }
    }

    fun cancelShortcutPicker() {
        _shortcutPickerSlot.value = null
    }

    // ── CarPlay / Android Auto / Autostart picker ─────────────────────────────
    enum class AppPickerTarget { CARPLAY, ANDROID_AUTO, PIP, AUTOSTART_1, AUTOSTART_2, AUTOSTART_3, AUTOSTART_4 }

    private val _appPickerTarget = MutableStateFlow<AppPickerTarget?>(null)
    val appPickerTarget: StateFlow<AppPickerTarget?> = _appPickerTarget

    fun startCarPlayPicker() {
        _appPickerTarget.value = AppPickerTarget.CARPLAY
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startAndroidAutoPicker() {
        _appPickerTarget.value = AppPickerTarget.ANDROID_AUTO
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startPipPicker() {
        _appPickerTarget.value = AppPickerTarget.PIP
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startAutostartPicker(slot: Int) {
        _appPickerTarget.value = when (slot) {
            0 -> AppPickerTarget.AUTOSTART_1
            1 -> AppPickerTarget.AUTOSTART_2
            2 -> AppPickerTarget.AUTOSTART_3
            3 -> AppPickerTarget.AUTOSTART_4
            else -> AppPickerTarget.AUTOSTART_1
        }
        _nav.value = NavDestination.APP_LIBRARY
    }

    private fun assignAutostart(index: Int, packageName: String) {
        updateSettings {
            val list = autostartPackages.toMutableList()
            while (list.size <= index) list.add("")
            list[index] = packageName
            copy(autostartPackages = list)
        }
    }

    fun assignPickerApp(app: AppInfo) {
        when (_appPickerTarget.value) {
            AppPickerTarget.CARPLAY      -> updateSettings { copy(carPlayPackage = app.packageName) }
            AppPickerTarget.ANDROID_AUTO -> updateSettings { copy(androidAutoPackage = app.packageName) }
            AppPickerTarget.PIP          -> updateSettings { copy(pipAppPackage = app.packageName) }
            AppPickerTarget.AUTOSTART_1  -> assignAutostart(0, app.packageName)
            AppPickerTarget.AUTOSTART_2  -> assignAutostart(1, app.packageName)
            AppPickerTarget.AUTOSTART_3  -> assignAutostart(2, app.packageName)
            AppPickerTarget.AUTOSTART_4  -> assignAutostart(3, app.packageName)
            null -> {}
        }
        _appPickerTarget.value = null
        _nav.value = NavDestination.HOME
    }

    fun clearCarPlayApp()      { updateSettings { copy(carPlayPackage = "") } }
    fun clearAndroidAutoApp()  { updateSettings { copy(androidAutoPackage = "") } }
    fun clearPipApp()          { updateSettings { copy(pipAppPackage = "") } }
    fun clearAutostartApp(index: Int) {
        updateSettings {
            val list = autostartPackages.toMutableList()
            if (index in list.indices) {
                list[index] = ""
                copy(autostartPackages = list)
            } else this
        }
    }

    fun updateWidgetConfig(id: String, spanX: Int, spanY: Int) {
        updateSettings {
            val resized = widgetLayout.map { w ->
                if (w.id == id) w.copy(
                    spanX = spanX.coerceIn(1, GRID_COLS - w.gridX),
                    spanY = spanY.coerceIn(1, GRID_ROWS - w.gridY)
                ) else w
            }
            // Re-run collision resolution so enlarging a widget pushes neighbors
            // aside instead of stacking on top of them
            val activeIds = activeWidgetIds()
            val active    = resized.filter { it.enabled && it.id in activeIds }
            val inactive  = resized.filter { !it.enabled || it.id !in activeIds }
            val target    = active.find { it.id == id }
            copy(widgetLayout = if (target != null)
                computeWidgetMove(active, id, target.gridX, target.gridY) + inactive
            else resized)
        }
    }

    fun moveWidgetConfig(id: String, gridX: Int, gridY: Int) {
        updateSettings {
            val activeIds = activeWidgetIds()
            val active   = widgetLayout.filter { it.enabled && it.id in activeIds }
            val inactive = widgetLayout.filter { !it.enabled || it.id !in activeIds }
            copy(widgetLayout = computeWidgetMove(active, id, gridX, gridY) + inactive)
        }
    }

    fun addWidget(id: String) {
        updateSettings {
            val activeIds = activeWidgetIds()
            var layout    = widgetLayout
            var cell      = freeCellIn(layout, activeIds)

            // If grid is full, shrink the largest multi-cell widget by one span to make room
            if (cell == null) {
                val candidate = layout
                    .filter { it.enabled && it.id in activeIds && it.spanX * it.spanY > 1 }
                    .maxByOrNull { it.spanX * it.spanY }
                if (candidate != null) {
                    layout = layout.map { w ->
                        if (w.id == candidate.id)
                            if (w.spanY > 1) w.copy(spanY = w.spanY - 1) else w.copy(spanX = w.spanX - 1)
                        else w
                    }
                    cell = freeCellIn(layout, activeIds)
                }
            }

            val cell_ = cell ?: return@updateSettings this

            val withShow = when (id) {
                "CLOCK"       -> copy(showClock = true)
                "WEATHER"     -> copy(showWeather = true)
                "NOW_PLAYING" -> copy(showNowPlaying = true)
                "TELEMETRY"   -> copy(showTelemetry = true)
                "ALTIMETER"   -> copy(showAltimeter = true)
                "SPEEDOMETER" -> copy(showSpeedometer = true)
                "VITALS"      -> copy(showVitals = true)
                "TRIP_TRACKER" -> copy(showTripTracker = true)
                "SOUNDBOARD"  -> copy(showSoundboard = true)
                "MAP" -> copy(showMap = true)
                "PIP" -> copy(showPip = true)
                else          -> this
            }
            val idx       = layout.indexOfFirst { it.id == id }
            val newLayout = if (idx >= 0) layout.toMutableList().also { list ->
                val w = list[idx]
                val targetSpanX = if (id == "PIP") 4 else w.spanX
                val targetSpanY = if (id == "PIP") 4 else w.spanY
                
                val area = if (targetSpanX > 1 || targetSpanY > 1) freeAreaIn(layout, activeIds, targetSpanX, targetSpanY) else null
                list[idx] = if (area != null)
                    w.copy(enabled = true, gridX = area.first, gridY = area.second, spanX = targetSpanX, spanY = targetSpanY)
                else
                    w.copy(enabled = true, gridX = cell_.first, gridY = cell_.second, spanX = 1, spanY = 1)
            } else {
                val span = if (id == "PIP") 4 else 1
                layout + com.openlauncher.app.data.WidgetConfig(id, cell_.first, cell_.second, spanX = span, spanY = span)
            }
            withShow.copy(widgetLayout = newLayout)
        }
    }

    fun toggleMapProvider() {
        updateSettings {
            copy(mapProvider = if (mapProvider == MapProvider.OSM) MapProvider.GOOGLE else MapProvider.OSM)
        }
    }

    fun setMapType(type: com.openlauncher.app.data.MapType) {
        updateSettings {
            copy(mapType = type)
        }
    }

    fun toggleTraffic() {
        updateSettings {
            copy(showTraffic = !showTraffic)
        }
    }

    fun removeWidget(id: String) {
        updateSettings {
            val withShowRemoved = when (id) {
                "CLOCK"       -> copy(showClock = false)
                "WEATHER"     -> copy(showWeather = false)
                "NOW_PLAYING" -> copy(showNowPlaying = false)
                "TELEMETRY"   -> copy(showTelemetry = false)
                "ALTIMETER"   -> copy(showAltimeter = false)
                "SPEEDOMETER" -> copy(showSpeedometer = false)
                "VITALS"      -> copy(showVitals = false)
                "TRIP_TRACKER" -> copy(showTripTracker = false)
                "SOUNDBOARD"  -> copy(showSoundboard = false)
                "MAP" -> copy(showMap = false)
                "PIP" -> copy(showPip = false)
                else          -> this
            }
            
            // For custom Android widgets, remove them entirely from the list
            // For built-in widgets, just set enabled = false
            val isCustom = id.startsWith("ANDROID_WIDGET_")
            val newLayout = if (isCustom) {
                withShowRemoved.widgetLayout.filter { w ->
                    if (w.id == id) {
                        w.appWidgetId?.let { appWidgetHost.deleteAppWidgetId(it) }
                        false
                    } else true
                }
            } else {
                withShowRemoved.widgetLayout.map { w ->
                    if (w.id == id) {
                        w.appWidgetId?.let { appWidgetHost.deleteAppWidgetId(it) }
                        w.copy(enabled = false)
                    } else w
                }
            }
            withShowRemoved.copy(widgetLayout = newLayout)
        }
    }

    fun updateSoundboardPad(index: Int, pad: SoundPadConfig) {
        updateSettings {
            // Persisted lists from older versions may be shorter than the 6 pads
            // the widget displays — pad before assigning to avoid IndexOutOfBounds
            val padded = soundboardPads.toMutableList()
            while (padded.size <= index) padded.add(SoundPadConfig("+", synthType = ""))
            padded[index] = pad
            copy(soundboardPads = padded)
        }
    }

    private fun freeCellIn(
        layout: List<com.openlauncher.app.data.WidgetConfig>,
        activeIds: Set<String>
    ): Pair<Int, Int>? = freeAreaIn(layout, activeIds, 1, 1)

    private fun freeAreaIn(
        layout: List<com.openlauncher.app.data.WidgetConfig>,
        activeIds: Set<String>,
        spanX: Int,
        spanY: Int
    ): Pair<Int, Int>? {
        val occupied = buildSet<Pair<Int, Int>> {
            layout.filter { it.enabled && it.id in activeIds }.forEach { w ->
                for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) add(w.gridX + dx to w.gridY + dy)
            }
        }
        for (row in 0 until GRID_ROWS) for (col in 0 until GRID_COLS) {
            if (col + spanX > GRID_COLS || row + spanY > GRID_ROWS) continue
            if ((0 until spanX).all { dx -> (0 until spanY).all { dy -> (col + dx to row + dy) !in occupied } })
                return col to row
        }
        return null
    }

    fun cancelCarPlayPicker() {
        _appPickerTarget.value = null
    }

    // ── Rearrange mode ────────────────────────────────────────────────────────
    private val _rearrangeMode = MutableStateFlow(false)
    val rearrangeMode: StateFlow<Boolean> = _rearrangeMode

    fun toggleRearrangeMode() { _rearrangeMode.value = !_rearrangeMode.value }
    fun exitRearrangeMode()   { _rearrangeMode.value = false }

    private val _widgetLibraryOpen = MutableStateFlow(false)
    val widgetLibraryOpen: StateFlow<Boolean> = _widgetLibraryOpen

    fun setWidgetLibraryOpen(open: Boolean) { _widgetLibraryOpen.value = open }

    // ── Installed apps ────────────────────────────────────────────────────────
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _appIconMap = MutableStateFlow<Map<String, android.graphics.drawable.Drawable>>(emptyMap())
    val appIconMap: StateFlow<Map<String, android.graphics.drawable.Drawable>> = _appIconMap

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadInstalledApps()
        }
    }

    fun loadInstalledApps() {
        if (_appsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _appsLoading.value = true
            val pm = getApplication<Application>().packageManager

            // Query specifically for apps with a Launcher interface (Graphical UI)
            // This captures both User and System apps (like Chrome, Maps, YouTube)
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }

            _apps.value = resolveInfos.mapNotNull { info ->
                try {
                    AppInfo(
                        packageName = info.activityInfo.packageName,
                        appName     = info.loadLabel(pm).toString(),
                        icon        = info.loadIcon(pm),
                        isSystemApp = (info.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                } catch (_: Exception) { null }
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName }
            
            _appIconMap.value = _apps.value.associate { it.packageName to it.icon }
            _appsLoading.value = false
        }
    }

    fun launchApp(packageName: String) {
        val app = getApplication<Application>()
        val pm  = app.packageManager
        // Try standard launch intent first; fall back to first ACTION_MAIN activity in package
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).setPackage(packageName), 0
            ).firstOrNull()?.activityInfo?.let { ai ->
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName(ai.packageName, ai.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        intent?.let { 
            runCatching {
                app.startActivity(it)
            }.onFailure { e ->
                android.util.Log.e("LauncherVM", "Failed to launch app: $packageName", e)
            }
        }
    }

    // ── Now Playing ───────────────────────────────────────────────────────────
    val nowPlaying: StateFlow<NowPlayingState?> = MediaListenerService.nowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun playPause(context: Context) {
            val ctrl = nowPlaying.value?.controller
            if (ctrl != null) {
                val state = ctrl.playbackState?.state
                if (state == android.media.session.PlaybackState.STATE_PLAYING)
                    ctrl.transportControls?.pause()
                    else
                        ctrl.transportControls?.play()
            } else {
                playLastOrOpenActive(context)
            }
        }

    fun skipNext() { nowPlaying.value?.controller?.transportControls?.skipToNext() }
    fun skipPrev() { nowPlaying.value?.controller?.transportControls?.skipToPrevious() }

    private fun getFallbackMusicPackage(context: Context): String {
        val pm = context.packageManager
        val candidates = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music",
            "org.videolan.vlc",
            "com.pandora.android",
            "com.jetappfactory.jetaudio",
            "com.maxmpz.audioplayer",
            "com.deezer.android"
        )
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return ""
    }

    fun playLastOrOpenActive(context: Context) {
        val state = nowPlaying.value
        val controller = state?.controller
        val pkg = controller?.packageName ?: MediaListenerService.lastMediaPackage.ifEmpty {
            getFallbackMusicPackage(context)
        }

        // Launch the app
        if (pkg.isNotEmpty()) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }

        // Start playback if not already playing
        if (state?.isPlaying != true) {
            if (controller != null) {
                controller.transportControls?.play()
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(eventDown)
                val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(eventUp)
            }
        }
    }

    // ── Weather ───────────────────────────────────────────────────────────────
    private val _weather = MutableStateFlow<WeatherState?>(null)
    val weather: StateFlow<WeatherState?> = _weather

    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError

    private var weatherJob: Job? = null

        // CORREGIDO: Usamos 'application' en lugar de 'context'
        private val sharedPrefs = application.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)
        private val gson = Gson()

        fun fetchWeather(lat: Double, lon: Double, metric: Boolean) {
            weatherJob?.cancel()
            weatherJob = viewModelScope.launch {
                try {
                    // INTENTAR TRAER DE INTERNET (Hay Wi-Fi/Datos)
                    val resp = WeatherApi.service.getForecast(lat, lon, temperatureUnit = "celsius")
                    val daily = resp.dailyData
                    val current = resp.currentWeather

                    if (daily != null) {
                        val daysList = daily.dates.mapIndexed { index, date ->
                            com.openlauncher.app.model.DailyForecast(
                                date = date,
                                maxTemperatureCelsius = daily.maxTemperatures.getOrNull(index) ?: 0.0,
                                                                     minTemperatureCelsius = daily.minTemperatures.getOrNull(index) ?: 0.0,
                                                                     weatherCode = daily.weatherCodes.getOrNull(index) ?: 0
                            )
                        }

                        val nuevoEstado = WeatherState(
                            currentTemperature = current?.temperature,
                            forecastDays = daysList,
                                isLoading = false,
                                error = null
                        )

                        // Almacenamos en caché el JSON de los 7 días de forma asíncrona
                        withContext(Dispatchers.IO) {
                            val json = gson.toJson(nuevoEstado)
                            sharedPrefs.edit().putString("cached_state", json).apply()
                        }

                        _weather.value = nuevoEstado
                        _weatherError.value = null
                    } // CORREGIDO: Se eliminó el caracter '/' sobrante que causaba el error de sintaxis
                } catch (e: Exception) {
                    // MODALIDAD OFFLINE: Si falla internet, cargamos del caché
                    val jsonGuardado = sharedPrefs.getString("cached_state", null)
                    if (!jsonGuardado.isNullOrEmpty()) {
                        // CORREGIDO: Casteo explícito seguro para evitar la confusión de GSON con Map.Entry
                        val estadoRecuperado = gson.fromJson(jsonGuardado, WeatherState::class.java) as WeatherState

                        // CORREGIDO: Reconstruimos el estado clonando únicamente los días para evitar invocar 'currentTemperature'
                        _weather.value = WeatherState(
                            currentTemperature = estadoRecuperado.currentTemperature,
                            forecastDays = estadoRecuperado.forecastDays,
                                isLoading = false,
                                error = null
                        )
                        _weatherError.value = null
                    } else {
                        _weatherError.value = e.message
                    }
                }
            }
        }


    // ── Location & Compass ────────────────────────────────────────────────────
    val location: StateFlow<LocationData?> = locationMgr.location
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val compassBearing: StateFlow<Float> = locationMgr.bearing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    // Re-evaluated every minute: a parked car produces no location updates
    // (minDistance filters), so AUTO mode must also flip on time alone.
    private val minuteTicker = flow { while (true) { emit(Unit); delay(60_000L) } }

    val isDayMode: StateFlow<Boolean> = combine(settings, locationMgr.location, minuteTicker) { s, loc, _ ->
        when (s.dayNightMode) {
            DayNightMode.DARK   -> false
            DayNightMode.LIGHT  -> true
            DayNightMode.AUTO   -> if (loc != null) SunriseSunset.isDay(loc.latitude, loc.longitude) else false
            DayNightMode.SYSTEM -> false // placeholder — overridden in MainActivity via isSystemInDarkTheme()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun startLocationUpdates() = locationMgr.start()
    fun stopLocationUpdates()  = locationMgr.stop()

    // ── Connectivity ──────────────────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _wifiLevel = MutableStateFlow(-1) // 0-4, -1 = disconnected
    val wifiLevel: StateFlow<Int> = _wifiLevel

    private val _mobileLevel = MutableStateFlow(-1) // 0-4, -1 = disconnected
    val mobileLevel: StateFlow<Int> = _mobileLevel

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    private fun startSignalListeners() {
        telephonyManager = getApplication<Application>().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                super.onSignalStrengthsChanged(signalStrength)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    _mobileLevel.value = signalStrength?.level ?: -1
                } else {
                    // Fallback for API < 23 if necessary, but Junsun is Android 10
                    _mobileLevel.value = -1 
                }
            }
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
    }

    fun refreshMedia() {
        MediaListenerService.requestRefresh()
    }

    fun refreshConnectivity() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = getApplication<Application>().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            _isConnected.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            
            // Wifi Strength
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val info = wm.connectionInfo
                _wifiLevel.value = WifiManager.calculateSignalLevel(info.rssi, 5)
            } else {
                _wifiLevel.value = -1
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            val connected = info?.isConnected == true
            _isConnected.value = connected
            
            if (connected && info?.type == ConnectivityManager.TYPE_WIFI) {
                val wifiInfo = wm.connectionInfo
                _wifiLevel.value = WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)
            } else {
                _wifiLevel.value = -1
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(packageReceiver)
        locationMgr.stop()
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        getApplication<Application>().registerReceiver(packageReceiver, filter)

        loadInstalledApps()
        refreshConnectivity()
        startSignalListeners()
        // Fetch weather on first location fix, then every 30 minutes.
        // The minute ticker covers the parked case where no location updates arrive.
        viewModelScope.launch {
            var lastFetchMs = 0L
            merge(
                locationMgr.location.filterNotNull(),
                minuteTicker.mapNotNull { locationMgr.location.value }
            ).collect { loc ->
                val now = System.currentTimeMillis()
                if (now - lastFetchMs >= 30 * 60 * 1_000L) {
                    lastFetchMs = now
                    fetchWeather(loc.latitude, loc.longitude, settings.value.unitSystem.name == "METRIC")
                }
            }
        }
    }
}
