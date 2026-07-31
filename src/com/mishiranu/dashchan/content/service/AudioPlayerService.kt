package com.mishiranu.dashchan.content.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import chan.content.Chan
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.LocaleManager
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.ReadFileTask
import com.mishiranu.dashchan.content.async.ReadFileTask.FileCallback
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.ui.MainActivity
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.WeakObservable
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ThemeEngine
import java.io.File

/**
 * Plays an audio attachment as a `mediaPlayback` foreground service.
 *
 * Backed by Media3 [ExoPlayer] behind a [MediaSession], so playback is a real system media
 * session: the notification uses the platform media template, and the lock screen, the output
 * switcher and the headset/Bluetooth transport keys all drive the same player. ExoPlayer owns
 * audio focus and the becoming-noisy handling; the service only keeps the wake lock and the
 * notification, and downloads the file first when it is not cached yet.
 *
 * The Media3 pieces used here ([ExoPlayer], [MediaSession.getPlatformToken]) are marked
 * `@UnstableApi`, so the whole service opts in once.
 */
@OptIn(UnstableApi::class)
class AudioPlayerService :
    BaseService(),
    FileCallback {
    private val callbacks = WeakObservable<Callback>()
    private lateinit var notificationManager: NotificationManager
    private var notificationColor = 0
    private lateinit var wakeLock: WakeLock

    private var readFileTask: ReadFileTask? = null
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private var chanName: String? = null
    private var chanTitle: String? = null
    private var fileName: String? = null
    private var audioFile: File? = null

    private var foregroundStarted = false

    private var progress = 0
    private var progressMax = 0
    private var lastUpdate: Long = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.getInstance().apply(newBase))
    }

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationColor = ThemeEngine.attachAndApply(this).accent
        notificationManager.createNotificationChannel(
            NotificationChannel(
                C.NOTIFICATION_CHANNEL_AUDIO_PLAYER,
                getString(R.string.audio_player),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":AudioPlayerWakeLock",
            )
        wakeLock.setReferenceCounted(false)
        addOnDestroyListener(ChanDatabase.getInstance().requireCookies())
    }

    private fun notifyToggle() {
        for (callback in callbacks) {
            callback.onTogglePlayback()
        }
    }

    private fun notifyCancel() {
        for (callback in callbacks) {
            callback.onCancel()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent != null) {
            val action = intent.getAction()
            if (ACTION_START == action) {
                cleanup(false, false)
                val cacheManager: CacheManager = CacheManager.getInstance()
                if (!cacheManager.isCacheAvailable) {
                    ClickableToast.show(R.string.cache_is_unavailable)
                    cleanup(true, true)
                } else {
                    val uri = intent.getData()
                    chanName = intent.getStringExtra(EXTRA_CHAN_NAME)
                    fileName = intent.getStringExtra(EXTRA_FILE_NAME)
                    val chan = Chan.getPreferred(chanName, uri)
                    chanTitle = chan.configuration.getTitle()
                    val cachedFile = cacheManager.getMediaFile(uri, true)
                    if (cachedFile == null) {
                        ClickableToast.show(R.string.cache_is_unavailable)
                        cleanup(true, true)
                    } else {
                        wakeLock.acquire()
                        if (cachedFile.exists()) {
                            initAndPlayAudio(cachedFile)
                        } else {
                            val readFileTask =
                                ReadFileTask.createCachedMediaFile(this, chan, uri!!, cachedFile)
                            this.readFileTask = readFileTask
                            readFileTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
                        }
                    }
                }
            } else if (ACTION_CANCEL == action) {
                cleanup(true, true)
            } else if (ACTION_TOGGLE == action) {
                togglePlayback()
            }
        }
        return START_NOT_STICKY
    }

    public override fun onDestroy() {
        cleanup(false, true)
        super.onDestroy()
    }

    private fun startForegroundNotification(notification: Notification) {
        startForeground(C.NOTIFICATION_ID_AUDIO_PLAYER, notification)
        foregroundStarted = true
    }

    private fun cleanup(
        stopSelf: Boolean,
        notify: Boolean,
    ) {
        readFileTask?.cancel()
        readFileTask = null
        // The session must go before the player it wraps.
        mediaSession?.release()
        mediaSession = null
        player?.let { player ->
            player.removeListener(playerListener)
            player.release()
        }
        player = null
        wakeLock.release()
        setActive(false)
        if (stopSelf) {
            // The service is always launched with startForegroundService, so it must have been
            // in the foreground at least once before it is allowed to stop.
            if (!foregroundStarted) {
                startForegroundNotification(buildPlaybackNotification())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
        if (notify) {
            notifyCancel()
        }
    }

    private fun togglePlayback() {
        val player = this.player ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            // Playing again after the track ran out has to rewind first: play() alone would leave
            // the player sitting at the end. This is what the session's own play command does too.
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        }
        // The player events refresh the notification and the bound dialog
    }

    interface Callback {
        fun onTogglePlayback()

        fun onCancel()
    }

    /** Notified when audio playback starts or stops, so the UI can offer the player. */
    fun interface StateCallback {
        fun onAudioPlayerStateChanged(active: Boolean)
    }

    inner class Binder : android.os.Binder() {
        fun registerCallback(callback: Callback) {
            callbacks.register(callback)
        }

        fun unregisterCallback(callback: Callback) {
            callbacks.unregister(callback)
        }

        fun togglePlayback() {
            this@AudioPlayerService.togglePlayback()
        }

        fun stop() {
            cleanup(true, true)
        }

        val isRunning: Boolean
            get() = player != null

        val isPlaying: Boolean
            get() = player?.isPlaying == true

        fun getFileName(): String? = fileName

        val position: Int
            get() = player?.getCurrentPosition()?.toInt() ?: -1

        /** The duration in milliseconds, or 0 while the file is not parsed yet, -1 with no player. */
        val duration: Int
            get() {
                val duration = player?.getDuration() ?: return -1
                val unset = androidx.media3.common.C.TIME_UNSET
                return if (duration == unset) 0 else duration.toInt()
            }

        fun seekTo(msec: Int) {
            player?.seekTo(msec.toLong())
        }

        /**
         * The playback speed, 1 while there is no player. Pitch is left alone, so a sped-up track
         * still sounds like itself.
         */
        var speed: Float
            get() = player?.getPlaybackParameters()?.speed ?: 1f
            set(speed) {
                player?.setPlaybackSpeed(speed)
            }
    }

    override fun onBind(intent: Intent?): Binder? = this.Binder()

    private val playerListener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED,
                    )
                ) {
                    if (player.isPlaying) {
                        wakeLock.acquire()
                    } else {
                        wakeLock.acquire(PAUSED_WAKE_LOCK_TIMEOUT)
                    }
                    updatePlaybackNotification()
                    notifyToggle()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                ClickableToast.show(R.string.playback_error)
                // A file that cannot be decoded is most likely truncated: drop it so the next
                // attempt downloads it again.
                audioFile?.delete()
                cleanup(true, true)
            }
        }

    private fun initAndPlayAudio(file: File) {
        audioFile = file
        val player = ExoPlayer.Builder(this).build()
        this.player = player
        // ExoPlayer handles audio focus, ducking and the becoming-noisy broadcast itself
        player.setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        player.setHandleAudioBecomingNoisy(true)
        // Honour the same "action on playback completion" setting the video player uses: loop the
        // track, or stop at its end. Either way the service stays up so it can be played again.
        player.setRepeatMode(
            if (Preferences.videoCompletionMode == Preferences.VideoCompletionMode.LOOP) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            },
        )
        player.addListener(playerListener)
        player.setMediaItem(
            MediaItem
                .Builder()
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(fileName)
                        .setArtist(chanTitle)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build(),
                ).build(),
        )
        mediaSession =
            MediaSession
                .Builder(this, player)
                .setSessionActivity(createPlayerActivityIntent())
                .build()
        player.prepare()
        player.play()
        setActive(true)
        startForegroundNotification(buildPlaybackNotification())
    }

    private fun createPlayerActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(C.ACTION_PLAYER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createServiceIntent(
        requestCode: Int,
        action: String,
    ): PendingIntent =
        PendingIntent.getForegroundService(
            this,
            requestCode,
            obtainIntent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun updatePlaybackNotification() {
        if (player != null) {
            startForegroundNotification(buildPlaybackNotification())
        }
    }

    private fun buildPlaybackNotification(): Notification {
        val playing = player?.isPlaying == true
        val builder = Notification.Builder(this, C.NOTIFICATION_CHANNEL_AUDIO_PLAYER)
        builder.setSmallIcon(R.drawable.ic_audiotrack_white_24dp)
        builder.setColor(notificationColor)
        // With a session attached the platform prefers the session metadata, but these are what
        // the pre-media-template layouts and the "app is running" list show.
        builder.setContentTitle(fileName ?: getString(R.string.audio_playback))
        if (chanTitle != null) {
            builder.setContentText(chanTitle)
        }
        builder.setContentIntent(createPlayerActivityIntent())
        builder.setDeleteIntent(createServiceIntent(REQUEST_CODE_CANCEL, ACTION_CANCEL))
        builder.setOngoing(playing)
        builder.setShowWhen(false)
        builder.addAction(
            Notification.Action
                .Builder(
                    Icon.createWithResource(
                        this,
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                    ),
                    getString(if (playing) R.string.pause else R.string.play),
                    createServiceIntent(REQUEST_CODE_TOGGLE, ACTION_TOGGLE),
                ).build(),
        )
        builder.addAction(
            Notification.Action
                .Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop),
                    getString(R.string.stop),
                    createServiceIntent(REQUEST_CODE_CANCEL, ACTION_CANCEL),
                ).build(),
        )
        val mediaSession = this.mediaSession
        if (mediaSession != null) {
            // Hands the notification over to the platform media template: the transport controls
            // and the seek bar are then driven by the session, which is also what puts the same
            // controls on the lock screen, in the output switcher and on the headset keys.
            builder.setStyle(
                Notification
                    .MediaStyle()
                    .setMediaSession(mediaSession.getPlatformToken())
                    .setShowActionsInCompactView(0, 1),
            )
        }
        return builder.build()
    }

    private fun buildDownloadingNotification(
        error: Boolean,
        uri: Uri?,
    ): Notification {
        val builder = Notification.Builder(this, C.NOTIFICATION_CHANNEL_AUDIO_PLAYER)
        builder.setSmallIcon(
            if (error) {
                android.R.drawable.stat_sys_download_done
            } else {
                android.R.drawable.stat_sys_download
            },
        )
        builder.setColor(notificationColor)
        builder.setDeleteIntent(createServiceIntent(REQUEST_CODE_CANCEL, ACTION_CANCEL))
        if (error) {
            builder.setContentTitle(getString(R.string.download_completed))
            builder.setContentText(
                getString(R.string.success_number_not_loaded_number__format, 0, 1),
            )
            val retryIntent =
                PendingIntent.getForegroundService(
                    this,
                    REQUEST_CODE_RETRY,
                    obtainIntent(this, ACTION_START)
                        .setData(uri)
                        .putExtra(EXTRA_CHAN_NAME, chanName)
                        .putExtra(EXTRA_FILE_NAME, fileName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(
                Notification.Action
                    .Builder(null, getString(R.string.retry), retryIntent)
                    .build(),
            )
        } else {
            builder.setContentTitle(getString(R.string.downloading_audio))
            builder.setContentText(getString(R.string.file_name__format, fileName))
            builder.setProgress(
                progressMax,
                progress,
                progressMax == 0 || progress > progressMax || progress < 0,
            )
            builder.addAction(
                Notification.Action
                    .Builder(
                        null,
                        getString(android.R.string.cancel),
                        createServiceIntent(REQUEST_CODE_CANCEL, ACTION_CANCEL),
                    ).build(),
            )
        }
        return builder.build()
    }

    override fun onStartDownloading() {
        lastUpdate = 0L
        startForegroundNotification(buildDownloadingNotification(false, null))
    }

    override fun onFinishDownloading(
        success: Boolean,
        uri: Uri,
        file: File,
        errorItem: ErrorItem?,
    ) {
        wakeLock.acquire(PAUSED_WAKE_LOCK_TIMEOUT)
        readFileTask = null
        if (success) {
            initAndPlayAudio(file)
        } else {
            cleanup(true, true)
            notificationManager.notify(
                C.NOTIFICATION_ID_AUDIO_PLAYER,
                buildDownloadingNotification(true, uri),
            )
        }
    }

    override fun onUpdateProgress(
        progress: Long,
        progressMax: Long,
    ) {
        this.progress = progress.toInt()
        this.progressMax = progressMax.toInt()
        val t = SystemClock.elapsedRealtime()
        if (t - lastUpdate >= 1000L) {
            lastUpdate = t
            startForegroundNotification(buildDownloadingNotification(false, null))
        }
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_TOGGLE = "toggle"

        private const val EXTRA_CHAN_NAME = "chanName"
        private const val EXTRA_FILE_NAME = "fileName"

        private const val REQUEST_CODE_TOGGLE = 1
        private const val REQUEST_CODE_CANCEL = 2
        private const val REQUEST_CODE_RETRY = 3

        // Enough to finish writing the state after playback stopped, without holding the CPU
        private const val PAUSED_WAKE_LOCK_TIMEOUT = 15000L

        private val stateCallbacks = WeakObservable<StateCallback>()

        /** Whether a player exists, i.e. whether opening [MainActivity]'s player dialog makes sense. */
        @JvmStatic
        var isActive: Boolean = false
            private set

        @JvmStatic
        fun registerStateCallback(callback: StateCallback) {
            stateCallbacks.register(callback)
        }

        @JvmStatic
        fun unregisterStateCallback(callback: StateCallback) {
            stateCallbacks.unregister(callback)
        }

        private fun setActive(active: Boolean) {
            if (isActive != active) {
                isActive = active
                for (callback in stateCallbacks) {
                    callback.onAudioPlayerStateChanged(active)
                }
            }
        }

        private fun obtainIntent(
            context: Context?,
            action: String?,
        ): Intent = Intent(context, AudioPlayerService::class.java).setAction(action)

        @JvmStatic
        fun start(
            context: Context,
            chanName: String?,
            uri: Uri?,
            fileName: String?,
        ) {
            context.startForegroundService(
                obtainIntent(context, ACTION_START)
                    .setData(uri)
                    .putExtra(EXTRA_CHAN_NAME, chanName)
                    .putExtra(EXTRA_FILE_NAME, fileName),
            )
        }
    }
}
