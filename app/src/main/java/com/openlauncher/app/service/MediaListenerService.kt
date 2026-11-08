package com.openlauncher.app.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.openlauncher.app.model.NowPlayingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MediaListenerService : NotificationListenerService() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(object : ContextWrapper(base) {
            override fun unbindService(conn: ServiceConnection) {
                try {
                    super.unbindService(conn)
                } catch (e: IllegalArgumentException) {
                    // System bug: notification listener could not be unbound
                }
            }
        })
    }

    private var activeController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshNowPlaying()
        override fun onMetadataChanged(metadata: MediaMetadata?)   = refreshNowPlaying()
        override fun onSessionDestroyed()                          = refreshNowPlaying()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected.value = true
        refreshNowPlaying()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        isConnected.value = false
        clearController()
        _nowPlaying.value = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?)  { refreshNowPlaying() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { refreshNowPlaying() }

    override fun onDestroy() {
        instance = null
        clearController()
        // Clear the static flow so the UI doesn't keep showing a dead session
        // (and pinning its album-art bitmap) after the service is killed
        _nowPlaying.value = null
        super.onDestroy()
    }

    private fun clearController() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
    }

    private fun refreshNowPlaying() {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val sessions: List<MediaController> = try {
            msm.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
        } catch (_: SecurityException) {
            emptyList()
        }

        val active = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()

        if (active == null) {
            clearController()
            _nowPlaying.value = null
            return
        }

        // getActiveSessions returns NEW MediaController instances on every call —
        // compare session tokens, not references, or we churn callbacks and
        // recompose the UI on every notification system-wide.
        if (active.sessionToken != activeController?.sessionToken) {
            clearController()
            activeController = active
            active.registerCallback(
                controllerCallback,
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        }

        updateFromController(activeController)
    }

    private fun updateFromController(controller: MediaController?) {
        if (controller == null) { _nowPlaying.value = null; return }
        lastMediaPackage = controller.packageName
        val meta = controller.metadata

        val appName = try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(controller.packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) { "Online Radio" }

        val rawTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val title = if (!rawTitle.isNullOrBlank() && rawTitle != "Unknown") rawTitle else appName

        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val artUri = meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING

        val prev = _nowPlaying.value

        // Optimization: check if metadata/state changed before extracting bitmaps
        val sameSong = prev?.title == title && prev?.artist == artist
        val sameArtUri = prev?.artUri == artUri
        val sameSession = prev?.controller?.sessionToken == controller.sessionToken
        val sameState = prev?.isPlaying == isPlaying

        // If it's the same track and we already have artwork (URI or Bitmap), skip redundant updates
        if (prev != null && sameSession && sameSong && sameArtUri && sameState && (prev.albumArt != null || prev.artUri != null)) {
            return
        }

        // Only extract bitmap if we don't have a URI or if the song changed
        val art = try {
            listOfNotNull(
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ).maxByOrNull { it.width * it.height }
        } catch (_: Exception) { null }

        _nowPlaying.value = NowPlayingState(
            title      = title,
            artist     = artist,
            albumArt   = art,
            artUri     = artUri,
            isPlaying  = isPlaying,
            controller = controller
        )
    }

    companion object {
        private val _nowPlaying = MutableStateFlow<NowPlayingState?>(null)
        val nowPlaying: StateFlow<NowPlayingState?> = _nowPlaying
        val isConnected = MutableStateFlow(false)
        var lastMediaPackage: String = ""

        @Volatile private var instance: MediaListenerService? = null
        fun requestRefresh() { instance?.refreshNowPlaying() }
    }
}
