package com.mishiranu.dashchan.content.service

import chan.util.StringUtils

import android.app.Notification
import android.app.Notification.ProgressStyle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Pair
import androidx.core.os.ParcelCompat
import chan.content.ApiException
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.ChanPerformer.SendPostData
import chan.util.CommonUtils.equals
import chan.util.StringUtils.formatHex
import chan.util.StringUtils.formatThreadTitle
import chan.util.StringUtils.nullIfEmpty
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.LocaleManager
import com.mishiranu.dashchan.content.LocaleManager.Companion.getInstance
import com.mishiranu.dashchan.content.NetworkObserver.Companion.getInstance
import com.mishiranu.dashchan.content.Preferences.favoriteOnReply
import com.mishiranu.dashchan.content.Preferences.getPassword
import com.mishiranu.dashchan.content.async.SendPostTask
import com.mishiranu.dashchan.content.async.SendPostTask.ProgressState
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PendingUserPost
import com.mishiranu.dashchan.content.model.PendingUserPost.SimilarComment
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.storage.DraftsStorage
import com.mishiranu.dashchan.content.storage.DraftsStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.DraftsStorage.PostDraft
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.StatisticsStorage
import com.mishiranu.dashchan.content.storage.StatisticsStorage.Companion.getInstance
import com.mishiranu.dashchan.ui.MainActivity
import com.mishiranu.dashchan.util.AndroidUtils.createHeadsUpNotificationChannel
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.Hasher.Companion.getInstanceSha256
import com.mishiranu.dashchan.util.WeakObservable
import com.mishiranu.dashchan.widget.ThemeEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

class PostingService : BaseService(), SendPostTask.Callback<PostingService.Key> {
    private val callbacks = HashMap<Key?, ArrayList<Callback>?>()
    private val globalCallbacks = WeakObservable<GlobalCallback>()
    private val callbackKeys = HashMap<Callback?, Key?>()
    private var taskState: TaskState? = null

    private var notificationManager: NotificationManager? = null
    private var notificationColor = 0
    private var wakeLock: WakeLock? = null

    private var notificationsWorker: Thread? = null
    private val notificationsQueue = LinkedBlockingQueue<NotificationData?>()

    class Key internal constructor(
        val chanName: String?,
        val boardName: String?,
        val threadNumber: String?
    ) {
        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o is Key) {
                val key = o
                return equals(key.chanName, chanName) &&
                        equals(key.boardName, boardName) &&
                        equals(key.threadNumber, threadNumber)
            }
            return false
        }

        override fun hashCode(): Int {
            var result = if (chanName != null) chanName.hashCode() else 0
            result = 31 * result + (if (boardName != null) boardName.hashCode() else 0)
            result = 31 * result + (if (threadNumber != null) threadNumber.hashCode() else 0)
            return result
        }
    }

    private class TaskState(
        val key: Key, val task: SendPostTask<Key>, context: Context?, chan: Chan,
        data: SendPostData
    ) {
        val builder: Notification.Builder
        val text: String

        internal var progressState = ProgressState.CONNECTING
        internal var attachmentIndex = 0
        internal var attachmentsCount = 0

        internal var progress: Long = 0
        internal var progressMax: Long = 0

        init {
            builder = Notification.Builder(context, C.NOTIFICATION_CHANNEL_POSTING)
            text = buildNotificationText(chan, data.boardName, data.threadNumber, null)
        }
    }

    private class NotificationData(
        val type: Type?,
        val taskState: TaskState,
        val syncLatch: CountDownLatch?
    ) {
        enum class Type {
            CREATE, UPDATE, CANCEL
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.getInstance().apply(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        var notificationColor = 0
        val theme = ThemeEngine.attachAndApply(this)
        notificationColor = theme!!.accent

        this.notificationColor = notificationColor
        notificationManager!!.createNotificationChannel(
            NotificationChannel(
                C.NOTIFICATION_CHANNEL_POSTING,
                getString(R.string.posting), NotificationManager.IMPORTANCE_LOW
            )
        )
        notificationManager!!.createNotificationChannel(
            createHeadsUpNotificationChannel(
                C.NOTIFICATION_CHANNEL_POSTING_COMPLETE,
                getString(R.string.sent_posts)
            )
        )

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            getPackageName() + ":PostingWakeLock"
        )
        wakeLock!!.setReferenceCounted(false)
        addOnDestroyListener(ChanDatabase.getInstance().requireCookies())
        notificationsWorker = Thread(notificationsRunnable, "PostingServiceNotificationThread")
        notificationsWorker!!.start()
    }

    public override fun onDestroy() {
        super.onDestroy()

        performFinish(null, true)
        wakeLock!!.release()
        // Ensure queue is empty
        refreshNotification(NotificationData.Type.CANCEL, null)
        notificationsWorker!!.interrupt()
        try {
            notificationsWorker!!.join()
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    private val notificationsRunnable = Runnable {
        var interrupted = false
        while (true) {
            var notificationData: NotificationData? = null
            if (!interrupted) {
                try {
                    notificationData = notificationsQueue.take()
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) {
                notificationData = notificationsQueue.poll()
            }
            if (notificationData == null) {
                return@Runnable
            }
            if (notificationData.type == NotificationData.Type.CANCEL) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                val taskState = notificationData.taskState
                val builder = taskState.builder
                if (notificationData.type == NotificationData.Type.CREATE) {
                    builder.setSmallIcon(android.R.drawable.stat_sys_upload)
                    val cancelIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(this, Receiver::class.java)
                            .setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(
                        Notification.Action.Builder(
                            null,
                            getString(android.R.string.cancel), cancelIntent
                        ).build()
                    )
                    builder.setColor(notificationColor)
                    this.startForegroundService(Intent(this, PostingService::class.java))
                }
                val progressMode = taskState.task.isProgressMode()
                when (taskState.progressState) {
                    ProgressState.CONNECTING -> {
                        if (progressMode) {
                            builder.setStyle(ProgressStyle().setProgressIndeterminate(true))
                        }
                        builder.setContentTitle(getString(R.string.sending__ellipsis))
                    }

                    ProgressState.SENDING -> {
                        if (progressMode) {
                            val progressStyle = ProgressStyle()
                            if (taskState.progressMax > 0) {
                                val max = 1000
                                val progress =
                                    (taskState.progress * max / taskState.progressMax).toInt()
                                progressStyle.setProgressSegments(
                                    mutableListOf<ProgressStyle.Segment?>(
                                        ProgressStyle.Segment(max)
                                    )
                                )
                                progressStyle.setProgress(progress)
                                builder.setShortCriticalText((100 * progress / max).toString() + "%")
                            } else {
                                progressStyle.setProgressIndeterminate(true)
                            }
                            builder.setStyle(progressStyle)
                            builder.setContentTitle(
                                getString(
                                    R.string.sending_number_of_number__ellipsis_format,
                                    taskState.attachmentIndex + 1, taskState.attachmentsCount
                                )
                            )
                        } else {
                            builder.setContentTitle(getString(R.string.sending__ellipsis))
                        }
                    }

                    ProgressState.PROCESSING -> {
                        if (progressMode) {
                            builder.setStyle(
                                ProgressStyle()
                                    .setProgressSegments(
                                        mutableListOf<ProgressStyle.Segment?>(
                                            ProgressStyle.Segment(1)
                                        )
                                    )
                                    .setProgress(1)
                            )
                        }
                        builder.setContentTitle(getString(R.string.processing_data__ellipsis))
                    }
                }
                builder.setContentText(taskState.text)
                startForeground(C.NOTIFICATION_ID_POSTING, builder.build())
            }
            if (notificationData.syncLatch != null) {
                notificationData.syncLatch.countDown()
            }
        }
    }

    override fun onBind(intent: Intent?): Binder? {
        return this.Binder()
    }

    interface Callback {
        fun onState(
            progressMode: Boolean, progressState: ProgressState,
            attachmentIndex: Int, attachmentsCount: Int
        )

        fun onProgress(progress: Long, progressMax: Long)
        fun onStop(success: Boolean)
    }

    fun interface GlobalCallback {
        fun onPostSent()
    }

    inner class Binder : android.os.Binder() {
        fun executeSendPost(chanName: String?, data: SendPostData): Boolean {
            if (taskState == null) {
                val key = PostingService.Key(chanName, data.boardName, data.threadNumber)
                this@PostingService.startForegroundService(
                    Intent(
                        this@PostingService,
                        PostingService::class.java
                    )
                )
                wakeLock!!.acquire()
                val chan = get(chanName)
                val task = SendPostTask(key, this@PostingService, chan, data)
                task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
                val taskState = TaskState(key, task, this@PostingService, chan, data)
                refreshNotification(NotificationData.Type.CREATE, taskState)
                this@PostingService.taskState = taskState
                val callbacks = this@PostingService.callbacks.get(key)
                if (callbacks != null) {
                    for (callback in callbacks) {
                        notifyInit(callback, taskState)
                    }
                }
                return true
            }
            return false
        }

        fun cancelSendPost(chanName: String?, boardName: String?, threadNumber: String?) {
            performFinish(PostingService.Key(chanName, boardName, threadNumber), true)
        }

        internal fun cancelCurrentSendPost() {
            performFinish(null, true)
        }

        fun register(
            callback: Callback,
            chanName: String?,
            boardName: String?,
            threadNumber: String?
        ) {
            val key = PostingService.Key(chanName, boardName, threadNumber)
            callbackKeys.put(callback, key)
            var callbacks = this@PostingService.callbacks.get(key)
            if (callbacks == null) {
                callbacks = ArrayList(1)
                this@PostingService.callbacks.put(key, callbacks)
            }
            callbacks.add(callback)
            if (taskState != null && taskState!!.key == key) {
                notifyInit(callback, taskState!!)
            }
        }

        fun unregister(callback: Callback?) {
            val key = callbackKeys.remove(callback)
            if (key != null) {
                val callbacks = this@PostingService.callbacks.get(key)
                callbacks!!.remove(callback)
                if (callbacks.isEmpty()) {
                    this@PostingService.callbacks.remove(key)
                }
            }
        }

        fun register(globalCallback: GlobalCallback) {
            globalCallbacks.register(globalCallback)
        }

        fun unregister(globalCallback: GlobalCallback) {
            globalCallbacks.unregister(globalCallback)
        }
    }

    private fun refreshNotification(type: NotificationData.Type?, taskState: TaskState?) {
        val syncLatch =
            if (type == NotificationData.Type.CREATE || type == NotificationData.Type.CANCEL)
                CountDownLatch(1)
            else
                null
        notificationsQueue.add(NotificationData(type, taskState!!, syncLatch))
        if (syncLatch != null) {
            try {
                syncLatch.await()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
        }
    }

    private fun notifyInit(callback: Callback, taskState: TaskState) {
        val progressMode = taskState.task.isProgressMode()
        callback.onState(
            progressMode, taskState.progressState, taskState.attachmentIndex,
            taskState.attachmentsCount
        )
        callback.onProgress(taskState.progress, taskState.progressMax)
    }

    private fun performFinish(key: Key?, cancel: Boolean): Boolean {
        val taskState = this.taskState
        if (taskState != null && (key == null || taskState.key == key)) {
            this.taskState = null
            if (cancel) {
                taskState.task.cancel()
            }
            refreshNotification(NotificationData.Type.CANCEL, taskState)
            wakeLock!!.release()
            if (cancel) {
                val callbacks = this.callbacks.get(key)
                if (callbacks != null) {
                    for (callback in callbacks) {
                        callback.onStop(false)
                    }
                }
            }
            return true
        }
        return false
    }

    override fun onSendPostChangeProgressState(
        key: Key, progressState: ProgressState,
        attachmentIndex: Int, attachmentsCount: Int
    ) {
        val taskState = this.taskState
        if (taskState != null && taskState.key == key) {
            taskState.progressState = progressState
            taskState.attachmentIndex = attachmentIndex
            taskState.attachmentsCount = attachmentsCount
            refreshNotification(NotificationData.Type.UPDATE, taskState)
            val callbacks = this.callbacks.get(key)
            if (callbacks != null) {
                val progressMode = taskState.task.isProgressMode()
                for (callback in callbacks) {
                    callback.onState(progressMode, progressState, attachmentIndex, attachmentsCount)
                }
            }
        }
    }

    override fun onSendPostChangeProgressValue(key: Key, progress: Long, progressMax: Long) {
        val taskState = this.taskState
        if (taskState != null && taskState.key == key) {
            taskState.progress = progress
            taskState.progressMax = progressMax
            refreshNotification(NotificationData.Type.UPDATE, taskState)
            val callbacks = this.callbacks.get(key)
            if (callbacks != null) {
                for (callback in callbacks) {
                    callback.onProgress(progress, progressMax)
                }
            }
        }
    }

    override fun onSendPostSuccess(
        key: Key, data: SendPostData,
        chanName: String?, threadNumber: String?, postNumber: PostNumber?
    ) {
        if (performFinish(key, false)) {
            val chan = get(chanName)
            val targetThreadNumber: String? = if (data.threadNumber != null)
                data.threadNumber
            else
                StringUtils.nullIfEmpty(threadNumber)
            val draftsStorage = DraftsStorage.getInstance()
            draftsStorage.removeCaptchaDraft()
            draftsStorage.removePostDraft(chanName, data.boardName, data.threadNumber)
            if (targetThreadNumber != null) {
                var password = getPassword(chan)
                if (equals(password, data.password)) {
                    password = null
                }
                draftsStorage.store(
                    PostDraft(
                        chanName,
                        data.boardName,
                        targetThreadNumber,
                        data.name,
                        data.email,
                        password,
                        data.optionSage,
                        data.optionOriginalPoster,
                        data.userIcon
                    )
                )
            }

            if (targetThreadNumber != null) {
                var comment = data.comment
                if (comment != null) {
                    val commentEditor = chan.markup.safe().obtainCommentEditor(data.boardName)
                    if (commentEditor != null) {
                        comment = commentEditor.removeTags(comment)
                    }
                }
                val arrayKey = PostingService.Key(chanName, data.boardName, targetThreadNumber)
                val newThread = data.threadNumber == null

                var pendingUserPost: PendingUserPost? = null
                if (postNumber != null) {
                    CommonDatabase.getInstance().posts.setFlags(
                        true, chanName!!, data.boardName,
                        targetThreadNumber!!, postNumber, PostItem.HideState.UNDEFINED, true
                    )
                } else if (newThread) {
                    pendingUserPost = PendingUserPost.NewThread.INSTANCE
                } else {
                    pendingUserPost = SimilarComment(comment, System.currentTimeMillis())
                }
                if (pendingUserPost != null) {
                    var pendingUserPosts: HashSet<PendingUserPost>? =
                        PENDING_USER_POST_MAP.get(arrayKey)
                    if (pendingUserPosts == null) {
                        pendingUserPosts = HashSet(1)
                        PENDING_USER_POST_MAP.put(arrayKey, pendingUserPosts)
                    }
                    pendingUserPosts.add(pendingUserPost)
                }

                val newPostData = NewPostData(arrayKey, postNumber, comment, newThread)
                var newPostDataList: ArrayList<NewPostData>? = NEW_POST_DATA_MAP.get(arrayKey)
                if (newPostDataList == null) {
                    newPostDataList = ArrayList(1)
                    NEW_POST_DATA_MAP.put(arrayKey, newPostDataList)
                }
                newPostDataList.add(newPostData)
                if (newThread) {
                    newThreadData = Pair<Key?, NewPostData?>(
                        PostingService.Key(chanName, data.boardName, null),
                        newPostData
                    )
                }

                // Importance, sound and vibration are governed by the channel itself.
                val builder = Notification.Builder(
                    this,
                    C.NOTIFICATION_CHANNEL_POSTING_COMPLETE
                )
                builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
                builder.setColor(notificationColor)
                builder.setContentTitle(getString(R.string.post_sent))
                builder.setContentText(
                    buildNotificationText(
                        chan,
                        data.boardName,
                        targetThreadNumber,
                        postNumber
                    )
                )
                val tag = newPostData.tag
                val intent = Intent(this, MainActivity::class.java).setAction(tag)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(C.EXTRA_CHAN_NAME, chanName)
                    .putExtra(C.EXTRA_BOARD_NAME, data.boardName)
                    .putExtra(C.EXTRA_THREAD_NUMBER, targetThreadNumber)
                    .putExtra(
                        C.EXTRA_POST_NUMBER,
                        if (postNumber != null) postNumber.toString() else null
                    )
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                notificationManager!!.notify(tag, 0, builder.build())
            }

            if (targetThreadNumber != null && favoriteOnReply!!.isEnabled(data.optionSage)) {
                // Add to favorites after processing the response to ensure watcher is not triggered too early
                FavoritesStorage.getInstance()
                    .add(chanName!!, data.boardName, targetThreadNumber, null, true)
            }
            StatisticsStorage.getInstance().incrementPostsSent(chanName!!, data.threadNumber == null)
            val callbacks = this.callbacks.get(key)
            if (callbacks != null) {
                for (callback in callbacks) {
                    callback.onStop(true)
                }
            }
            for (globalCallback in globalCallbacks) {
                globalCallback.onPostSent()
            }
        }
    }

    override fun onSendPostFail(
        key: Key, data: SendPostData, chanName: String?, errorItem: ErrorItem?,
        extra: ApiException.Extra?, captchaError: Boolean, keepCaptcha: Boolean
    ) {
        if (performFinish(key, false)) {
            val callbacks = this.callbacks.get(key)
            if (callbacks != null) {
                for (callback in callbacks) {
                    callback.onStop(false)
                }
            }
            startActivity(
                Intent(this, MainActivity::class.java).setAction(C.ACTION_POSTING)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(C.EXTRA_CHAN_NAME, chanName)
                    .putExtra(C.EXTRA_BOARD_NAME, data.boardName)
                    .putExtra(C.EXTRA_THREAD_NUMBER, data.threadNumber)
                    .putExtra(
                        C.EXTRA_FAIL_RESULT,
                        FailResult(errorItem!!, extra, captchaError, keepCaptcha)
                    )
            )
        }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = if (intent != null) intent.getAction() else null
            val cancel = ACTION_CANCEL == action
            val bindContext = context.getApplicationContext()
            if (cancel) {
                // Broadcast receivers can't bind to services
                val connection = arrayOf<ServiceConnection?>(null)
                connection[0] = object : ServiceConnection {
                    override fun onServiceConnected(
                        componentName: ComponentName?,
                        binder: IBinder?
                    ) {
                        val postingBinder = binder as Binder
                        if (cancel) {
                            postingBinder.cancelCurrentSendPost()
                        }
                        bindContext.unbindService(connection[0]!!)
                    }

                    override fun onServiceDisconnected(componentName: ComponentName?) {}
                }
                bindContext.bindService(
                    Intent(context, PostingService::class.java),
                    connection[0]!!,
                    BIND_AUTO_CREATE
                )
            }
        }
    }

    class FailResult(
		@JvmField val errorItem: ErrorItem,
	    @JvmField val extra: ApiException.Extra?,
	    @JvmField val captchaError: Boolean,
	    @JvmField val keepCaptcha: Boolean
    ) : Parcelable {
        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            errorItem.writeToParcel(dest, flags)
            dest.writeParcelable(extra, flags)
            dest.writeByte((if (captchaError) 1 else 0).toByte())
            dest.writeByte((if (keepCaptcha) 1 else 0).toByte())
        }

        companion object {
            val CREATOR: Parcelable.Creator<FailResult?> =
                object : Parcelable.Creator<FailResult?> {
                    override fun createFromParcel(`in`: Parcel): FailResult {
                        val errorItem = ErrorItem.CREATOR.createFromParcel(`in`)
                        val extra = ParcelCompat.readParcelable<ApiException.Extra?>(
                            `in`,
                            FailResult::class.java.getClassLoader(),
                            ApiException.Extra::class.java
                        )
                        val captchaError = `in`.readByte().toInt() != 0
                        val keepCaptcha = `in`.readByte().toInt() != 0
                        return FailResult(errorItem!!, extra, captchaError, keepCaptcha)
                    }

                    override fun newArray(size: Int): Array<FailResult?> {
                        return arrayOfNulls<FailResult>(size)
                    }
                }
        }
    }

    class NewPostData internal constructor(
        key: Key,
        postNumber: PostNumber?,
        comment: String?,
        newThread: Boolean
    ) {
        val key: Key?
        internal val tag: String

        init {
            this.key = key
            this.tag = "posting:" + formatHex(
                getInstanceSha256().calculate(
                    key.chanName + "/" +
                            key.boardName + "/" + key.threadNumber + "/" + postNumber + "/" + comment + "/" + newThread
                )
            )
        }
    }

    companion object {
        private const val ACTION_CANCEL = "cancel"

        fun buildNotificationText(
            chan: Chan, boardName: String?, threadNumber: String?,
            postNumber: PostNumber?
        ): String {
            val builder = StringBuilder(chan.configuration.getTitle()).append(", ")
            builder.append(
                StringUtils.formatThreadTitle(
                    chan.name!!,
                    boardName, if (threadNumber != null) threadNumber else "?"
                )
            )
            if (postNumber != null) {
                builder.append(", #").append(postNumber)
            }
            return builder.toString()
        }

        private val PENDING_USER_POST_MAP = HashMap<Key?, HashSet<PendingUserPost>?>()

        fun getPendingUserPosts(
            chanName: String?, boardName: String?,
            threadNumber: String?
        ): Set<PendingUserPost>? {
            return PENDING_USER_POST_MAP.get(PostingService.Key(chanName, boardName, threadNumber))
        }

        fun consumePendingUserPosts(
            chanName: String?, boardName: String?, threadNumber: String?,
            consumePendingUserPosts: Collection<PendingUserPost>
        ) {
            val key = PostingService.Key(chanName, boardName, threadNumber)
            val pendingUserPosts: HashSet<PendingUserPost>? = PENDING_USER_POST_MAP.remove(key)
            if (pendingUserPosts != null) {
                pendingUserPosts.removeAll(consumePendingUserPosts)
                if (!pendingUserPosts.isEmpty()) {
                    PENDING_USER_POST_MAP.put(key, pendingUserPosts)
                }
            }
        }

        private val NEW_POST_DATA_MAP = HashMap<Key?, ArrayList<NewPostData>?>()

        @JvmStatic
        fun consumeNewPostData(
            context: Context,
            chanName: String?,
            boardName: String?,
            threadNumber: String?
        ): Boolean {
            val newPostDataList: ArrayList<NewPostData>? = NEW_POST_DATA_MAP
                .remove(PostingService.Key(chanName, boardName, threadNumber))
            if (newPostDataList != null) {
                val notificationManager = context
                    .getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                for (newPostData in newPostDataList) {
                    notificationManager.cancel(newPostData.tag, 0)
                }
                return !newPostDataList.isEmpty()
            } else {
                return false
            }
        }

        private var newThreadData: Pair<Key?, NewPostData?>? = null

        fun consumeNewThreadData(
            context: Context,
            chanName: String?,
            boardName: String?
        ): NewPostData? {
            val newThreadData: Pair<Key?, NewPostData?>? = newThreadData
            if (newThreadData != null && newThreadData.first == PostingService.Key(
                    chanName,
                    boardName,
                    null
                )
            ) {
                clearNewThreadData()
                val notificationManager = context
                    .getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(newThreadData.second!!.tag, 0)
                return newThreadData.second
            }
            return null
        }

        @JvmStatic
        fun clearNewThreadData() {
            newThreadData = null
        }
    }
}
