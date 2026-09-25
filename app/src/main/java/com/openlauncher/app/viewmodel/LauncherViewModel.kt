package com.openlauncher.app.viewmodel

import android.app.Application
import android.appwidget.AppWidgetHost
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.DefaultShortcutIcon
import com.openlauncher.app.data.NominatimApi
import com.openlauncher.app.data.SettingsRepository
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.data.WeatherApi
import com.openlauncher.app.data.defaultShortcuts
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.model.DailyForecast
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
import java.util.Locale

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val APPWIDGET_HOST_ID = 1024
    }

    private val settingsRepo = SettingsRepository(application)
    private val locationMgr  = LocationCompassManager(application)
    
    // Android AppWidget Support
    val appWidgetHost = AppWidgetHost(application, APPWIDGET_HOST_ID)

    fun startListeningWidgets() { appWidgetHost.startListening() }
    fun stopListeningWidgets()  { appWidgetHost.stopListening() }

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

    // ── App picker & Autostart ────────────────────────────────────────────────
    enum class AppPickerTarget { PIP, AUTOSTART_1, AUTOSTART_2, AUTOSTART_3, AUTOSTART_4 }

    private val _appPickerTarget = MutableStateFlow<AppPickerTarget?>(null)
    val appPickerTarget: StateFlow<AppPickerTarget?> = _appPickerTarget

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

    fun clearAutostartApp(index: Int) {
        updateSettings {
            val list = autostartPackages.toMutableList()
            if (index in list.indices) {
                list[index] = ""
                copy(autostartPackages = list)
            } else this
        }
    }

    fun cancelPicker() {
        _appPickerTarget.value = null
    }

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

    fun fetchWeather(lat: Double, lon: Double, metric: Boolean) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            try {
                val resp = WeatherApi.service.getForecast(lat, lon, temperatureUnit = "celsius")
                val daily = resp.dailyData
                val currentData = resp.currentData

                val temp = currentData?.temperature
                val windSpd = currentData?.windspeed
                val windDir = currentData?.winddirection
                val wCode = currentData?.weathercode

                val locationName = try {
                    var name: String? = null
                    try {
                        val nomResp = NominatimApi.service.reverseGeocode(lat, lon)
                        name = nomResp.address?.getCityOrTown() ?: nomResp.name
                    } catch (_: Exception) {}

                    if (name.isNullOrEmpty()) {
                        val geocoder = Geocoder(getApplication(), Locale.getDefault())
                        val addresses = withContext(Dispatchers.IO) {
                            @Suppress("DEPRECATION")
                            geocoder.getFromLocation(lat, lon, 1)
                        }
                        addresses?.firstOrNull()?.let { addr ->
                            name = addr.locality ?: addr.subLocality
                        }
                    }

                    name?.replace(Regex("(?i)\\b(county|district|province|municipality|city|town)\\b"), "")
                       ?.replace(Regex("[0-9]+"), "")
                       ?.trim()
                       ?.takeIf { it.isNotEmpty() }
                } catch (_: Exception) { null }

                if (daily != null) {
                    val daysList = daily.dates.mapIndexed { index, date ->
                        DailyForecast(
                            date = date,
                            maxTemperatureCelsius = daily.maxTemperatures.getOrNull(index) ?: 0.0,
                            minTemperatureCelsius = daily.minTemperatures.getOrNull(index) ?: 0.0,
                            weatherCode = daily.weatherCodes.getOrNull(index) ?: 0
                        )
                    }

                    val today = daysList.firstOrNull()
                    val sunriseTime = daily.sunrises?.firstOrNull()?.let { formatIsoTime(it) }
                    val sunsetTime = daily.sunsets?.firstOrNull()?.let { formatIsoTime(it) }
                    val precip = currentData?.precipitation ?: daily.precipitationSums?.firstOrNull()

                    val nuevoEstado = WeatherState(
                        currentTemperature = temp,
                        windSpeed = windSpd,
                        windDirection = windDir,
                        weatherCode = wCode,
                        maxTemperatureToday = today?.maxTemperatureCelsius,
                        minTemperatureToday = today?.minTemperatureCelsius,
                        precipitationMm = precip,
                        precipitationProbability = daily.precipitationProbabilities?.firstOrNull(),
                        sunriseTime = sunriseTime,
                        sunsetTime = sunsetTime,
                        locationName = locationName,
                        forecastDays = daysList,
                        isLoading = false,
                        error = null
                    )

                    _weather.value = nuevoEstado
                    _weatherError.value = null
                }
            } catch (e: Exception) {
                _weatherError.value = e.message
            }
        }
    }

        private fun formatIsoTime(isoStr: String): String {
            return try {
                if (isoStr.contains("T")) {
                    isoStr.substringAfter("T").take(5)
                } else isoStr.takeLast(5)
            } catch (_: Exception) { isoStr }
        }

        fun refreshWeatherManually() {
            val loc = locationMgr.location.value
            if (loc != null) {
                fetchWeather(loc.latitude, loc.longitude, settings.value.unitSystem.name == "METRIC")
            }
        }


    // ── Location & Compass ────────────────────────────────────────────────────
    val location: StateFlow<LocationData?> = locationMgr.location
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val compassBearing: StateFlow<Float> = locationMgr.bearing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    val satelliteCount: StateFlow<Int> = locationMgr.satellites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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
