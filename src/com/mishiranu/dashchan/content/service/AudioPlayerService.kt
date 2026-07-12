package com.mishiranu.dashchan.content.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.net.Uri
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import chan.content.Chan.Companion.getPreferred
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.LocaleManager
import com.mishiranu.dashchan.content.LocaleManager.Companion.getInstance
import com.mishiranu.dashchan.content.NetworkObserver.Companion.getInstance
import com.mishiranu.dashchan.content.async.ReadFileTask
import com.mishiranu.dashchan.content.async.ReadFileTask.FileCallback
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.storage.DraftsStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.FavoritesStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.StatisticsStorage.Companion.getInstance
import com.mishiranu.dashchan.ui.MainActivity
import com.mishiranu.dashchan.util.AudioFocus
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.WeakObservable
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ThemeEngine
import java.io.File

class AudioPlayerService : BaseService(), OnCompletionListener, MediaPlayer.OnErrorListener,
    FileCallback {
    private val callbacks = WeakObservable<Callback>()
    private var audioFocus: AudioFocus? = null
    private var notificationManager: NotificationManager? = null
    private var notificationColor = 0
    private var wakeLock: WakeLock? = null

    private var builder: NotificationCompat.Builder? = null
    private var readFileTask: ReadFileTask? = null
    private var mediaPlayer: MediaPlayer? = null

    private var chanName: String? = null
    private var fileName: String? = null
    private var audioFile: File? = null

    private var pausedByTransientLossOfFocus = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.getInstance().apply(newBase))
    }

    override fun onCreate() {
        super.onCreate()

        audioFocus = AudioFocus(this, AudioFocus.Callback { change: AudioFocus.Change? ->
            when (change) {
                AudioFocus.Change.LOSS -> {
                    pause(true)
                }

                AudioFocus.Change.LOSS_TRANSIENT -> {
                    val playing = mediaPlayer!!.isPlaying()
                    pause(false)
                    if (playing) {
                        pausedByTransientLossOfFocus = true
                    }
                }

                AudioFocus.Change.GAIN -> {
                    if (pausedByTransientLossOfFocus) {
                        play(false)
                    }
                }

                else -> {}
            }
        })
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        var notificationColor = 0
        val theme = ThemeEngine.attachAndApply(this)
        notificationColor = theme!!.accent

        this.notificationColor = notificationColor
        notificationManager!!.createNotificationChannel(
            NotificationChannel(
                C.NOTIFICATION_CHANNEL_AUDIO_PLAYER,
                getString(R.string.audio_player), NotificationManager.IMPORTANCE_LOW
            )
        )

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            getPackageName() + ":AudioPlayerWakeLock"
        )
        wakeLock!!.setReferenceCounted(false)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                    val cachedFile = cacheManager.getMediaFile(uri, true)
                    if (cachedFile == null) {
                        ClickableToast.show(R.string.cache_is_unavailable)
                        cleanup(true, true)
                    } else {
                        wakeLock!!.acquire()
                        if (cachedFile.exists()) {
                            initAndPlayAudio(cachedFile)
                        } else {
                            val chan = getPreferred(chanName, uri)
                            readFileTask =
                                ReadFileTask.createCachedMediaFile(this, chan, uri!!, cachedFile)
                            readFileTask!!.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
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

    private fun startForeground(builder: NotificationCompat.Builder) {
        startForeground(C.NOTIFICATION_ID_AUDIO_PLAYER, builder.build())
    }

    private fun cleanup(stopSelf: Boolean, notify: Boolean) {
        if (readFileTask != null) {
            readFileTask!!.cancel()
            readFileTask = null
        }
        audioFocus!!.release()
        if (mediaPlayer != null) {
            mediaPlayer!!.stop()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
        wakeLock!!.release()
        if (stopSelf) {
            // Ensure service was started foreground at least once
            startForeground(getPlaybackNotification(false))

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (notify) {
            notifyCancel()
        }
    }

    private fun togglePlayback() {
        val success: Boolean
        if (mediaPlayer!!.isPlaying()) {
            success = pause(true)
        } else {
            success = play(true)
        }
        startForeground(getPlaybackNotification(true))
        if (success) {
            notifyToggle()
        } else {
            ClickableToast.show(R.string.playback_error)
            cleanup(true, true)
        }
    }

    interface Callback {
        fun onTogglePlayback()
        fun onCancel()
    }

    inner class Binder : android.os.Binder() {
        fun registerCallback(callback: Callback) {
            callbacks.register(callback)
        }

        fun unregisterCallback(callback: Callback) {
            callbacks.unregister(callback)
        }

        fun togglePlayback() {
            if (mediaPlayer != null) {
                this@AudioPlayerService.togglePlayback()
            }
        }

        fun stop() {
            cleanup(true, true)
        }

        val isRunning: Boolean
            get() = mediaPlayer != null

        val isPlaying: Boolean
            get() = mediaPlayer != null && mediaPlayer!!.isPlaying()

        fun getFileName(): String? {
            return fileName
        }

        val position: Int
            get() = if (mediaPlayer != null) mediaPlayer!!.getCurrentPosition() else -1

        val duration: Int
            get() = if (mediaPlayer != null) mediaPlayer!!.getDuration() else -1

        fun seekTo(msec: Int) {
            if (mediaPlayer != null) {
                mediaPlayer!!.seekTo(msec)
            }
        }
    }

    override fun onBind(intent: Intent?): Binder? {
        return this.Binder()
    }

    override fun onCompletion(mp: MediaPlayer?) {
        pause(true)
        mediaPlayer!!.stop()
        mediaPlayer!!.release()
        initAndPlayAudio(audioFile!!)
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        ClickableToast.show(R.string.playback_error)
        if (audioFile != null) {
            audioFile!!.delete()
        }
        cleanup(true, true)
        return true
    }

    private fun pause(resetFocus: Boolean): Boolean {
        if (resetFocus) {
            audioFocus!!.release()
        }
        mediaPlayer!!.pause()
        wakeLock!!.acquire(15000)
        return true
    }

    private fun play(resetFocus: Boolean): Boolean {
        if (resetFocus && !audioFocus!!.acquire()) {
            return false
        }
        mediaPlayer!!.start()
        wakeLock!!.acquire()
        return true
    }

    private fun initAndPlayAudio(file: File) {
        audioFile = file
        pausedByTransientLossOfFocus = false
        mediaPlayer = MediaPlayer()
        mediaPlayer!!.setLooping(false)
        mediaPlayer!!.setOnCompletionListener(this)
        mediaPlayer!!.setOnErrorListener(this)
        try {
            mediaPlayer!!.setDataSource(file.getPath())
            mediaPlayer!!.prepare()
        } catch (e: Exception) {
            audioFile!!.delete()
            CacheManager.getInstance().handleDownloadedFile(audioFile!!, false)
            ClickableToast.show(R.string.playback_error)
            cleanup(true, true)
            return
        }
        play(true)
        startForeground(getPlaybackNotification(true))
    }

    private var progress = 0
    private var progressMax = 0
    private var lastUpdate: Long = 0

    private fun getPlaybackNotification(recreate: Boolean): NotificationCompat.Builder {
        var builder = this.builder
        if (builder == null || recreate) {
            builder = NotificationCompat.Builder(this, C.NOTIFICATION_CHANNEL_AUDIO_PLAYER)
            builder.setSmallIcon(R.drawable.ic_audiotrack_white_24dp)
            builder.setColor(notificationColor)
            val contentIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).setAction(C.ACTION_PLAYER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(contentIntent)
            val toggleIntent = PendingIntent.getForegroundService(
                this,
                0,
                obtainIntent(this, ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val playing = mediaPlayer != null && mediaPlayer!!.isPlaying()
            builder.addAction(
                0,
                getString(if (playing) R.string.pause else R.string.play), toggleIntent
            )
            val cancelIntent = PendingIntent.getForegroundService(
                this,
                0,
                obtainIntent(this, ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                0,
                getString(R.string.stop), cancelIntent
            )
            this.builder = builder
            builder.setContentTitle(getString(R.string.audio_playback))
            builder.setContentText(getString(R.string.file_name__format, fileName))
        }
        return builder
    }

    private fun getDownloadingNotification(
        recreate: Boolean,
        error: Boolean,
        uri: Uri?
    ): NotificationCompat.Builder {
        var builder = this.builder
        if (builder == null || recreate) {
            builder = NotificationCompat.Builder(this, C.NOTIFICATION_CHANNEL_AUDIO_PLAYER)
            builder.setSmallIcon(
                if (error)
                    android.R.drawable.stat_sys_download_done
                else
                    android.R.drawable.stat_sys_download
            )
            builder.setDeleteIntent(
                PendingIntent.getForegroundService(
                    this,
                    0,
                    obtainIntent(this, ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            if (error) {
                val retryIntent = PendingIntent.getForegroundService(
                    this,
                    0,
                    obtainIntent(this, ACTION_START).setData(uri)
                        .putExtra(EXTRA_CHAN_NAME, chanName)
                        .putExtra(EXTRA_FILE_NAME, fileName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    0,
                    getString(R.string.retry), retryIntent
                )
            } else {
                val cancelIntent = PendingIntent.getForegroundService(
                    this,
                    0,
                    obtainIntent(this, ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    0,
                    getString(android.R.string.cancel), cancelIntent
                )
            }
            builder.setColor(notificationColor)

            this.builder = builder
        }
        if (error) {
            builder.setContentTitle(getString(R.string.download_completed))
            builder.setContentText(
                getString(
                    R.string.success_number_not_loaded_number__format,
                    0,
                    1
                )
            )
        } else {
            builder.setContentTitle(getString(R.string.downloading_audio))
            builder.setContentText(getString(R.string.file_name__format, fileName))
            builder.setProgress(
                progressMax,
                progress,
                progressMax == 0 || progress > progressMax || progress < 0
            )
        }
        return builder
    }

    override fun onStartDownloading() {
        lastUpdate = 0L
        startForeground(getDownloadingNotification(true, false, null))
    }

    override fun onFinishDownloading(
        success: Boolean,
        uri: Uri,
        file: File,
        errorItem: ErrorItem?
    ) {
        wakeLock!!.acquire(15000)
        readFileTask = null
        if (success) {
            initAndPlayAudio(file)
        } else {
            cleanup(true, true)
            notificationManager!!.notify(
                C.NOTIFICATION_ID_AUDIO_PLAYER,
                getDownloadingNotification(true, true, uri).build()
            )
        }
    }

    override fun onUpdateProgress(progress: Long, progressMax: Long) {
        this.progress = progress.toInt()
        this.progressMax = progressMax.toInt()
        val t = SystemClock.elapsedRealtime()
        if (t - lastUpdate >= 1000L) {
            lastUpdate = t
            startForeground(getDownloadingNotification(false, false, null))
        }
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_TOGGLE = "toggle"

        private const val EXTRA_CHAN_NAME = "chanName"
        private const val EXTRA_FILE_NAME = "fileName"

        private fun obtainIntent(context: Context?, action: String?): Intent {
            return Intent(context, AudioPlayerService::class.java).setAction(action)
        }

        @JvmStatic
        fun start(context: Context, chanName: String?, uri: Uri?, fileName: String?) {
            context.startForegroundService(
                obtainIntent(context, ACTION_START).setData(uri)
                    .putExtra(EXTRA_CHAN_NAME, chanName).putExtra(EXTRA_FILE_NAME, fileName)
            )
        }
    }
}
