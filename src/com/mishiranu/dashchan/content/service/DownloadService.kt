package com.mishiranu.dashchan.content.service

import chan.util.StringUtils

import android.app.Notification
import android.app.Notification.ProgressStyle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaScannerConnection
import android.media.MediaScannerConnection.OnScanCompletedListener
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import android.util.Pair
import androidx.core.os.ParcelCompat
import chan.content.Chan.Companion.getPreferred
import chan.content.ChanManager.Fingerprints
import chan.util.CommonUtils.equals
import chan.util.DataFile
import chan.util.DataFile.Companion.obtain
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.getFileExtension
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.FileProvider.Companion.convertDownloadsLegacyFile
import com.mishiranu.dashchan.content.LocaleManager
import com.mishiranu.dashchan.content.LocaleManager.Companion.getInstance
import com.mishiranu.dashchan.content.NetworkObserver.Companion.getInstance
import com.mishiranu.dashchan.content.Preferences.downloadSubdirMode
import com.mishiranu.dashchan.content.Preferences.getDownloadUriTree
import com.mishiranu.dashchan.content.Preferences.isDownloadDetailName
import com.mishiranu.dashchan.content.Preferences.isDownloadOriginalName
import com.mishiranu.dashchan.content.Preferences.isNotifyDownloadComplete
import com.mishiranu.dashchan.content.async.ExecutorTask
import com.mishiranu.dashchan.content.async.ReadFileTask
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.FileHolder.Companion.obtain
import com.mishiranu.dashchan.content.storage.DraftsStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.FavoritesStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.StatisticsStorage.Companion.getInstance
import com.mishiranu.dashchan.ui.MainActivity
import com.mishiranu.dashchan.util.AndroidUtils.createHeadsUpNotificationChannel
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.IOUtils.close
import com.mishiranu.dashchan.util.IOUtils.copyStream
import com.mishiranu.dashchan.util.MimeTypes.forExtension
import com.mishiranu.dashchan.util.WeakObservable
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ThemeEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

class DownloadService : BaseService(), ReadFileTask.Callback {
    private var notificationManager: NotificationManager? = null
    private var notificationColor = 0
    private var wakeLock: WakeLock? = null

    private var notificationsWorker: Thread? = null
    private val notificationsQueue = LinkedBlockingQueue<NotificationData?>()
    private var isForegroundWorker = false

    private val callbacks = WeakObservable<Callback>()
    private val cachedDirectories = HashMap<String?, DataFile?>()

    private var primaryRequest: Request? = null
    private val directRequests = ArrayList<DirectRequest>()

    private val queuedTasks: LinkedHashMap<String?, TaskData> = LinkedHashMap<String?, TaskData>()
    private val successTasks: LinkedHashMap<String?, TaskData> = LinkedHashMap<String?, TaskData>()
    private val errorTasks: LinkedHashMap<String?, TaskData> = LinkedHashMap<String?, TaskData>()
    private var activeTask: Pair<TaskData?, ReadFileTask?>? = null

    private var builder: Notification.Builder? = null

    private var progress = 0
    private var progressMax = 0
    private var lastUpdate: Long = 0

    private var activeTaskDataFile: DataFile? = null
    private var lastSuccessTaskDataFile: DataFile? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.getInstance().apply(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        notificationsWorker = Thread(notificationsRunnable, "DownloadServiceNotificationThread")
        notificationsWorker!!.start()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        var notificationColor = 0
        val theme = ThemeEngine.attachAndApply(this)
        notificationColor = theme!!.accent

        this.notificationColor = notificationColor
        notificationManager!!.createNotificationChannel(
            NotificationChannel(
                C.NOTIFICATION_CHANNEL_DOWNLOADING,
                getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW
            )
        )
        notificationManager!!.createNotificationChannel(
            createHeadsUpNotificationChannel(
                C.NOTIFICATION_CHANNEL_DOWNLOADING_COMPLETE,
                getString(R.string.completed_downloads)
            )
        )

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            getPackageName() + ":DownloadServiceWakeLock"
        )
        wakeLock!!.setReferenceCounted(false)
        addOnDestroyListener(ChanDatabase.getInstance().requireCookies())
    }

    public override fun onDestroy() {
        super.onDestroy()

        if (!errorTasks.isEmpty()) {
            // Preserve existing error tasks for "retry" notification button
            val file: File =
                savedDownloadRetryFile
            file.delete()
            val parcel = Parcel.obtain()
            try {
                FileOutputStream(file).use { output ->
                    parcel.writeTypedList<TaskData?>(ArrayList<TaskData?>(errorTasks.values))
                    val data = parcel.marshall()
                    copyStream(ByteArrayInputStream(data), output)
                }
            } catch (e: Exception) {
                file.delete()
            } finally {
                parcel.recycle()
            }
        }
        cleanup()
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

    private fun cleanupRequests() {
        if (primaryRequest != null) {
            primaryRequest!!.cleanup()
            primaryRequest = null
        }
        for (directRequest in directRequests) {
            directRequest.cleanup()
        }
        directRequests.clear()
    }

    internal fun cleanup() {
        if (activeTask != null) {
            activeTask!!.second!!.cancel()
            activeTask = null
        }
        cleanupRequests()
        for (taskData in queuedTasks.values) {
            if (taskData.input != null) {
                close(taskData.input)
            }
        }
        queuedTasks.clear()
        successTasks.clear()
        errorTasks.clear()
        notificationsQueue.clear()
        activeTaskDataFile = null
        lastSuccessTaskDataFile = null
        refreshNotification(NotificationUpdate.SYNC)
        startStopForeground(false, null)
        wakeLock!!.release()
        for (callback in callbacks) {
            callback.requestHandleRequest()
            callback.onCleanup()
        }
    }

    private fun hasStoragePermission(): Boolean {
        var external = primaryRequest != null
        if (!external) {
            for (directRequest in directRequests) {
                if (directRequest.target.isExternal) {
                    external = true
                    break
                }
            }
        }
        if (external) {
            return getDownloadUriTree(this) != null
        } else {
            return true
        }
    }

    private fun handleRequests() {
        if (primaryRequest != null || !directRequests.isEmpty()) {
            if (!hasStoragePermission()) {
                for (callback in callbacks) {
                    callback.requestPermission()
                }
            } else {
                handlePrimaryRequest()
                enqueueTasksFromRequests()
                startNextTask()
            }
        }
        for (callback in callbacks) {
            callback.requestHandleRequest()
        }
        refreshNotification(NotificationUpdate.SYNC)
    }

    private fun handlePrimaryRequest() {
        if (primaryRequest is ChoiceRequest) {
            val choiceRequest = primaryRequest as ChoiceRequest
            if (!choiceRequest.shouldHandle) {
                val directRequest = choiceRequest.complete(
                    null, isDownloadDetailName,
                    isDownloadOriginalName
                )
                primaryRequest = null
                handlePrimaryDirectRequest(directRequest)
            }
        }
    }

    private fun enqueueTasksFromRequests() {
        for (directRequest in directRequests) {
            if (directRequest.input != null) {
                check(directRequest.downloadItems.size == 1)
                val downloadItem = directRequest.downloadItems.get(0)
                enqueue(
                    TaskData(
                        downloadItem.chanName,
                        directRequest.overwrite,
                        directRequest.input,
                        directRequest.target,
                        directRequest.path,
                        downloadItem.name,
                        directRequest.allowWrite
                    )
                )
            } else {
                for (downloadItem in directRequest.downloadItems) {
                    enqueue(
                        TaskData(
                            downloadItem.chanName,
                            directRequest.overwrite,
                            downloadItem.uri,
                            downloadItem.checkSha256,
                            downloadItem.checkFingerprints,
                            directRequest.target,
                            directRequest.path,
                            downloadItem.name,
                            directRequest.allowWrite
                        )
                    )
                }
            }
        }
        directRequests.clear()
    }


    private fun enqueue(taskData: TaskData) {
        val key = taskData.key
        if (activeTask != null && activeTask!!.first!!.key == key) {
            activeTask!!.second!!.cancel()
            activeTask = null
        }
        val oldTaskData = queuedTasks.remove(key)
        if (oldTaskData != null && oldTaskData.input != null) {
            close(oldTaskData.input)
        }
        successTasks.remove(key)
        errorTasks.remove(key)
        queuedTasks.put(key, taskData)
    }

    private fun startNextTask() {
        if (activeTask == null && !queuedTasks.isEmpty()) {
            val iterator = queuedTasks.values.iterator()
            val taskData = iterator.next()
            iterator.remove()
            SINGLE_THREAD_EXECUTOR.execute(Runnable {
                val taskDataFile = getDataFile(taskData)
                activeTaskDataFile = taskDataFile
                if (taskData.input != null) {
                    var success = false
                    try {
                        taskData.input.use { input ->
                            taskDataFile.openOutputStream().use { output ->
                                copyStream(input, output)
                                success = true
                            }
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    val finalSuccess = success
                    ConcurrentUtils.HANDLER.post(Runnable {
                        onFinishDownloadingInternal(
                            finalSuccess, TaskData(
                                taskData.chanName,
                                taskData.overwrite,
                                null,
                                taskData.target,
                                taskData.path,
                                taskData.name,
                                taskData.allowWrite
                            )
                        )
                    })
                } else {
                    val chan = getPreferred(taskData.chanName, taskData.uri)
                    val readFileTask = ReadFileTask.createShared(
                        this, chan,
                        taskData.uri!!, taskDataFile, taskData.overwrite,
                        taskData.checkSha256, taskData.checkFingerprints
                    )
                    activeTask = Pair<TaskData?, ReadFileTask?>(taskData, readFileTask)
                    readFileTask.execute(SINGLE_THREAD_EXECUTOR)
                }
            })
        } else {
            cachedDirectories.clear()
        }
    }

    private fun getDataFile(taskData: TaskData): DataFile {
        val key: String = getTargetPathKey(taskData.target, emptyIfNull(taskData.path))
        var file = cachedDirectories.get(key)
        if (file == null) {
            file = obtain(taskData.target, taskData.path)
            if (file.exists()) {
                cachedDirectories.put(key, file)
            }
        }
        return file.getChild(taskData.name)
    }

    private class PrepareTask<T>(internal val innerTask: Task<T?>) : ExecutorTask<Void?, T?>() {
        interface Task<T> {
            fun cleanup()

            @Throws(InterruptedException::class)
            fun run(): T?

            fun onResult(result: T?)
        }

        override fun run(): T? {
            try {
                return innerTask.run()
            } catch (e: InterruptedException) {
                return null
            }
        }

        override fun onComplete(result: T?) {
            innerTask.onResult(result)
        }
    }

    private fun collectActiveKeys(): HashSet<String?> {
        val activeKeys = HashSet<String?>(queuedTasks.keys)
        if (activeTask != null) {
            activeKeys.add(activeTask!!.first!!.key)
        }
        return activeKeys
    }

    @Throws(InterruptedException::class)
    private fun createReplaceRequest(
        directRequest: DirectRequest,
        activeKeys: HashSet<String?>
    ): ReplaceRequest? {
        var queued = 0
        var exists = 0
        var lastExistingFile: DataFile? = null
        val keys = HashSet<String?>()
        val availableItems = ArrayList<DownloadItem>()
        val parent = obtain(directRequest.target, directRequest.path)
        val children = HashMap<String?, DataFile?>()
        val childrenList = parent.getChildren()
        if (childrenList != null) {
            for (file in childrenList) {
                children.put(file.getName()!!.lowercase(Locale.getDefault()), file)
            }
        }
        for (downloadItem in directRequest.downloadItems) {
            val key: String =
                getTargetPathKey(directRequest.target, directRequest.path, downloadItem.name)
            if (keys.contains(key) || activeKeys.contains(key)) {
                queued++
            } else {
                val file = children.get(downloadItem.name!!.lowercase(Locale.getDefault()))
                if (file != null) {
                    exists++
                    lastExistingFile = file
                } else {
                    keys.add(key)
                    availableItems.add(downloadItem)
                }
            }
            if (Thread.interrupted()) {
                throw InterruptedException()
            }
        }
        return if (availableItems.size == directRequest.downloadItems.size)
            null
        else
            ReplaceRequest(directRequest, availableItems, lastExistingFile, queued, exists)
    }

    private fun handlePrimaryDirectRequest(directRequest: DirectRequest) {
        val activeKeys = collectActiveKeys()
        val task = PrepareTask<ReplaceRequest?>(object : PrepareTask.Task<ReplaceRequest?> {
            override fun cleanup() {
                directRequest.cleanup()
            }

            @Throws(InterruptedException::class)
            override fun run(): ReplaceRequest? {
                return createReplaceRequest(directRequest, activeKeys)
            }

            override fun onResult(result: ReplaceRequest?) {
                primaryRequest = result
                if (result == null) {
                    directRequests.add(directRequest)
                }
                handleRequests()
            }
        })
        task.execute(ConcurrentUtils.SEPARATE_EXECUTOR)
        primaryRequest = PrepareRequest(task)
    }

    @Throws(InterruptedException::class)
    private fun createDirectRequestKeepAll(
        replaceRequest: ReplaceRequest,
        activeKeys: HashSet<String?>
    ): DirectRequest {
        val keys = HashSet<String?>()
        val target = replaceRequest.directRequest.target
        val path = replaceRequest.directRequest.path
        val downloadItems = replaceRequest.directRequest.downloadItems
        val finalItems = ArrayList<DownloadItem>(downloadItems.size)
        val parent = obtain(target, path)
        val children = HashSet<String?>()
        val childrenList = parent.getChildren()
        if (childrenList != null) {
            for (file in childrenList) {
                children.add(file.getName()!!.lowercase(Locale.getDefault()))
            }
        }
        for (downloadItem in downloadItems) {
            if (replaceRequest.availableItems.contains(downloadItem)) {
                keys.add(
                    getTargetPathKey(
                        target,
                        replaceRequest.directRequest.path,
                        downloadItem.name
                    )
                )
                finalItems.add(downloadItem)
            } else {
                val extension = getFileExtension(downloadItem.name)
                val dotExtension = if (isEmpty(extension)) "" else "." + extension
                val nameWithoutExtension = downloadItem.name!!.substring(
                    0,
                    downloadItem.name.length - dotExtension.length
                )
                var name: String?
                var key: String?
                var i = 0
                do {
                    val append = (if (i > 0) "-" + i else "") + dotExtension
                    name = nameWithoutExtension + append
                    key = getTargetPathKey(target, path, nameWithoutExtension + append)
                    i++
                } while (children.contains(name.lowercase(Locale.getDefault())) ||
                    keys.contains(key) || activeKeys.contains(key)
                )
                keys.add(key)
                finalItems.add(
                    DownloadItem(
                        downloadItem.chanName, downloadItem.uri, name,
                        downloadItem.checkSha256, downloadItem.checkFingerprints
                    )
                )
            }
            if (Thread.interrupted()) {
                throw InterruptedException()
            }
        }
        return DirectRequest(
            target, path, replaceRequest.directRequest.overwrite,
            finalItems, replaceRequest.directRequest.input, replaceRequest.directRequest.allowWrite
        )
    }

    private fun handlePrimaryReplaceKeepAllReplace(replaceRequest: ReplaceRequest) {
        val activeKeys = collectActiveKeys()
        val task = PrepareTask<DirectRequest?>(object : PrepareTask.Task<DirectRequest?> {
            override fun cleanup() {
                replaceRequest.cleanup()
            }

            @Throws(InterruptedException::class)
            override fun run(): DirectRequest {
                return createDirectRequestKeepAll(replaceRequest, activeKeys)
            }

            override fun onResult(result: DirectRequest?) {
                primaryRequest = null
                directRequests.add(result!!)
                handleRequests()
            }
        })
        task.execute(ConcurrentUtils.SEPARATE_EXECUTOR)
        primaryRequest = PrepareRequest(task)
    }

    interface Callback {
        fun requestHandleRequest() {}
        fun requestPermission() {}
        fun onFinishDownloading(
            success: Boolean,
            target: DataFile.Target?,
            path: String?,
            name: String?
        ) {
        }

        fun onCleanup() {}
    }

    enum class PermissionResult {
        SUCCESS, FAIL, CANCEL
    }

    inner class Binder : android.os.Binder() {
        fun register(callback: Callback) {
            callbacks.register(callback)
        }

        fun unregister(callback: Callback) {
            callbacks.unregister(callback)
        }

        fun notifyReadyToHandleRequests() {
            handleRequests()
        }

        fun resolve(choiceRequest: ChoiceRequest, directRequest: DirectRequest?) {
            if (primaryRequest === choiceRequest) {
                primaryRequest = null
                if (directRequest != null) {
                    handlePrimaryDirectRequest(directRequest)
                } else {
                    choiceRequest.cleanup()
                }
                handleRequests()
            }
        }

        fun resolve(replaceRequest: ReplaceRequest, action: ReplaceRequest.Action?) {
            if (primaryRequest === replaceRequest) {
                primaryRequest = null
                if (action != null) {
                    when (action) {
                        ReplaceRequest.Action.REPLACE -> {
                            directRequests.add(replaceRequest.directRequest)
                        }

                        ReplaceRequest.Action.KEEP_ALL -> {
                            handlePrimaryReplaceKeepAllReplace(replaceRequest)
                        }

                        ReplaceRequest.Action.SKIP -> {
                            if (!replaceRequest.availableItems.isEmpty()) {
                                directRequests.add(
                                    DirectRequest(
                                        replaceRequest.directRequest.target,
                                        replaceRequest.directRequest.path,
                                        replaceRequest.directRequest.overwrite,
                                        replaceRequest.availableItems,
                                        replaceRequest.directRequest.input,
                                        replaceRequest.directRequest.allowWrite
                                    )
                                )
                            } else {
                                // Request with input should contain only 1 download item.
                                // Cleanup the request to ensure input stream is closed.
                                replaceRequest.cleanup()
                            }
                        }
                    }
                } else {
                    replaceRequest.cleanup()
                }
                handleRequests()
            }
        }

        fun cancel(prepareRequest: PrepareRequest) {
            if (primaryRequest === prepareRequest) {
                primaryRequest = null
                prepareRequest.cleanup()
                handleRequests()
            }
        }

        fun getPrimaryRequest(): Request? {
            return if (hasStoragePermission()) primaryRequest else null
        }

        fun onPermissionResult(result: PermissionResult?) {
            if (result != PermissionResult.SUCCESS) {
                if (result == PermissionResult.FAIL) {
                    ClickableToast.show(R.string.no_access_to_memory)
                }
                cleanupRequests()
            }
            handleRequests()
        }

        internal fun cancelAll() {
            cleanup()
        }

        internal fun retry() {
            if (activeTask == null && queuedTasks.isEmpty()) {
                successTasks.clear()
                val errorTasks: ArrayList<TaskData> =
                    ArrayList<TaskData>(this@DownloadService.errorTasks.values)
                this@DownloadService.errorTasks.clear()
                if (errorTasks.isEmpty()) {
                    val file: File =
                        savedDownloadRetryFile
                    if (file.exists()) {
                        val parcel = Parcel.obtain()
                        val output = ByteArrayOutputStream()
                        try {
                            FileInputStream(file).use { input ->
                                copyStream(input, output)
                                val data = output.toByteArray()
                                parcel.unmarshall(data, 0, data.size)
                                parcel.setDataPosition(0)
                                errorTasks.addAll(parcel.createTypedArrayList<TaskData?>(TaskData.CREATOR)!!)
                            }
                        } catch (e: Exception) {
                            // Ignore
                        } finally {
                            parcel.recycle()
                            file.delete()
                        }
                    }
                }
                for (taskData in errorTasks) {
                    if (taskData.uri != null) {
                        enqueue(taskData)
                    }
                }
                startNextTask()
            }
        }

        internal fun open(file: DataFile, allowWrite: Boolean) {
            refreshNotification(NotificationUpdate.SYNC)
            val extension = getFileExtension(file.getName())
            val type = forExtension(extension, "image/jpeg")
            if (file.exists()) {
                val callback = DownloadService.ScanCallback { uri: Uri? ->
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            (if (allowWrite) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                                )
                                .setDataAndType(uri, type)
                        )
                    } catch (e: ActivityNotFoundException) {
                        ClickableToast.show(R.string.unknown_address)
                    }
                }
                val fileOrUri = file.getFileOrUri()
                if (fileOrUri.first != null) {
                    scanFileLegacy(fileOrUri.first!!, Pair<String?, ScanCallback?>(type, callback))
                } else if (fileOrUri.second != null) {
                    callback.onComplete(fileOrUri.second)
                }
            }
        }

        private var accumulateState: Boolean? = null

        fun accumulate(): Accumulate {
            check(accumulateState == null)
            accumulateState = false
            return Accumulate {
                val accumulateState = this.accumulateState!!
                this.accumulateState = null
                if (accumulateState) {
                    handleRequests()
                }
            }
        }

        private fun handleRequestsOrAccumulate() {
            if (accumulateState != null) {
                accumulateState = true
            } else {
                handleRequests()
            }
        }

        fun downloadDirect(
            target: DataFile.Target, path: String?, overwrite: Boolean,
            downloadItems: MutableList<DownloadItem>
        ) {
            directRequests.add(DirectRequest(target, path, overwrite, downloadItems, null, false))
            handleRequestsOrAccumulate()
        }

        fun downloadDirect(
            target: DataFile.Target,
            path: String?,
            name: String?,
            input: InputStream?
        ) {
            directRequests.add(
                DirectRequest(
                    target,
                    path,
                    true,
                    mutableListOf<DownloadItem>(DownloadItem(null, null, name, null, null)),
                    input,
                    false
                )
            )
            handleRequestsOrAccumulate()
        }

        fun downloadStorage(
            uri: Uri?, fileName: String, originalName: String?,
            chanName: String?, boardName: String?, threadNumber: String?, threadTitle: String?
        ) {
            downloadStorage(
                RequestItem(uri, fileName, originalName),
                chanName, boardName, threadNumber, threadTitle
            )
        }

        fun downloadStorage(
            requestItem: RequestItem?,
            chanName: String?, boardName: String?, threadNumber: String?, threadTitle: String?
        ) {
            downloadStorage(
                mutableListOf<RequestItem>(requestItem!!), false,
                chanName, boardName, threadNumber, threadTitle
            )
        }

        fun downloadStorage(
            requestItems: MutableList<RequestItem>, multiple: Boolean,
            chanName: String?, boardName: String?, threadNumber: String?, threadTitle: String?
        ) {
            var modifyingAllowed = false
            var hasOriginalNames = false
            for (requestItem in requestItems) {
                if (isFileNameModifyingAllowed(chanName, requestItem.uri)) {
                    modifyingAllowed = true
                }
                if (!isEmpty(requestItem.originalName)) {
                    hasOriginalNames = true
                }
            }
            val allowDetailName = modifyingAllowed
            val allowOriginalName = modifyingAllowed && hasOriginalNames
            primaryRequest = UriRequest(
                downloadSubdirMode!!.isEnabled(multiple), requestItems,
                allowDetailName, allowOriginalName, chanName, boardName, threadNumber, threadTitle
            )
            handleRequestsOrAccumulate()
        }

        fun downloadStorage(
            input: InputStream?, chanName: String?, boardName: String?, threadNumber: String?,
            threadTitle: String?, fileName: String, allowDialog: Boolean, allowWrite: Boolean
        ) {
            primaryRequest = StreamRequest(
                downloadSubdirMode!!.isEnabled(false) && allowDialog,
                allowWrite, input, fileName, chanName, boardName, threadNumber, threadTitle
            )
            handleRequestsOrAccumulate()
        }
    }

    override fun onBind(intent: Intent?): Binder? {
        return this.Binder()
    }

    private class NotificationData(
        val type: Type?,
        val allowHeadsUp: Boolean,
        val queuedTasks: Int,
        val successTasks: Int,
        val errorTasks: Int,
        val allowRetry: Boolean,
        val hasNotFromCache: Boolean,
        val lastSuccessFile: DataFile?,
        val allowWrite: Boolean,
        val activeName: String?,
        val progress: Int,
        val progressMax: Int,
        val updateImageOnly: Boolean,
        val syncLatch: CountDownLatch?
    ) {
        enum class Type(val iconResId: Int) {
            PROGRESS(android.R.drawable.stat_sys_download),
            RESULT(android.R.drawable.stat_sys_download_done),
            REQUEST(android.R.drawable.stat_sys_warning)
        }

        companion object {
            fun updateData(
                type: Type,
                allowHeadsUp: Boolean,
                queuedTasks: Int,
                successTasks: Int,
                errorTasks: Int,
                allowRetry: Boolean,
                hasExternal: Boolean,
                lastSuccessFile: DataFile?,
                allowWrite: Boolean,
                activeName: String?,
                progress: Int,
                progressMax: Int
            ): NotificationData {
                return NotificationData(
                    type, allowHeadsUp, queuedTasks, successTasks, errorTasks,
                    allowRetry, hasExternal, lastSuccessFile, allowWrite,
                    activeName, progress, progressMax, false, null
                )
            }

            fun updateImageOnly(lastSuccessFile: DataFile?, allowWrite: Boolean): NotificationData {
                return DownloadService.NotificationData(
                    null, false, 0, 0, 0, false, false,
                    lastSuccessFile, allowWrite, null, 0, 0, true, null
                )
            }

            fun sync(syncLatch: CountDownLatch?): NotificationData {
                return DownloadService.NotificationData(
                    null, false, 0, 0, 0, false, false,
                    null, false, null, 0, 0, false, syncLatch
                )
            }
        }
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
            if (notificationData.syncLatch != null) {
                notificationData.syncLatch.countDown()
            } else if (notificationData.updateImageOnly) {
                if (builder != null) {
                    setBuilderImage(notificationData.lastSuccessFile!!)
                }
            } else {
                refreshNotificationFromThread(notificationData)
            }
        }
    }

    private class TaskData(
        val chanName: String?,
        val finishedFromCache: Boolean,
        val overwrite: Boolean,
        val input: InputStream?,
        val uri: Uri?,
        val checkSha256: ByteArray?,
        val checkFingerprints: Fingerprints?,
        val target: DataFile.Target,
        val path: String?,
        val name: String?,
        val allowWrite: Boolean
    ) : Parcelable {
        constructor(
            chanName: String?,
            overwrite: Boolean,
            input: InputStream?,
            target: DataFile.Target,
            path: String?,
            name: String?,
            allowWrite: Boolean
        ) : this(chanName, true, overwrite, input, null, null, null, target, path, name, allowWrite)

        constructor(
            chanName: String?, overwrite: Boolean,
            from: Uri?, checkSha256: ByteArray?, checkFingerprints: Fingerprints?,
            target: DataFile.Target, path: String?, name: String?, allowWrite: Boolean
        ) : this(
            chanName, false, overwrite, null, from, checkSha256, checkFingerprints,
            target, path, name, allowWrite
        )

        fun newFinishedFromCache(finishedFromCache: Boolean): TaskData {
            return if (this.finishedFromCache == finishedFromCache) this else TaskData(
                chanName,
                finishedFromCache,
                overwrite,
                input,
                uri,
                checkSha256,
                checkFingerprints,
                target,
                path,
                name,
                allowWrite
            )
        }

        val key: String
            get() = getTargetPathKey(
                target,
                path,
                name
            )

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeString(chanName)
            dest.writeByte((if (finishedFromCache) 1 else 0).toByte())
            dest.writeByte((if (checkFingerprints != null) 1 else 0).toByte())
            if (checkFingerprints != null) {
                checkFingerprints.writeToParcel(dest, flags)
            }
            dest.writeByte((if (overwrite) 1 else 0).toByte())
            dest.writeParcelable(uri, flags)
            dest.writeByteArray(checkSha256)
            dest.writeString(target.name)
            dest.writeString(path)
            dest.writeString(name)
            dest.writeByte((if (allowWrite) 1 else 0).toByte())
        }

        companion object {
            val CREATOR: Parcelable.Creator<TaskData?> = object : Parcelable.Creator<TaskData?> {
                override fun createFromParcel(source: Parcel): TaskData {
                    val chanName = source.readString()
                    val finishedFromCache = source.readByte().toInt() != 0
                    val overwrite = source.readByte().toInt() != 0
                    val uri = ParcelCompat.readParcelable<Uri?>(
                        source,
                        javaClass.getClassLoader(),
                        Uri::class.java
                    )
                    val checkSha256 = source.createByteArray()
                    val checkFingerprints = if (source.readByte().toInt() != 0)
                        Fingerprints.CREATOR.createFromParcel(source)
                    else
                        null
                    val target = DataFile.Target.valueOf(source.readString()!!)
                    val path = source.readString()
                    val name = source.readString()
                    val allowWrite = source.readByte().toInt() != 0
                    return TaskData(
                        chanName, finishedFromCache, overwrite, null, uri,
                        checkSha256, checkFingerprints, target, path, name, allowWrite
                    )
                }

                override fun newArray(size: Int): Array<TaskData?> {
                    return arrayOfNulls<TaskData>(size)
                }
            }
        }
    }

    private fun startStopForeground(foreground: Boolean, notification: Notification?) {
        synchronized(this) {
            if (foreground) {
                if (!isForegroundWorker) {
                    isForegroundWorker = true
                    this.startForegroundService(Intent(this, DownloadService::class.java))
                }
                startForeground(C.NOTIFICATION_ID_DOWNLOADING, notification)
            } else {
                if (isForegroundWorker) {
                    isForegroundWorker = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private var oldNotificationDataType: NotificationData.Type? = null

    private fun setBuilderImage(file: DataFile) {
        if (file.exists()) {
            val fileHolder = obtain(file)
            if (fileHolder != null) {
                val metrics = getResources().getDisplayMetrics()
                val size = max(metrics.widthPixels, metrics.heightPixels)
                builder!!.setLargeIcon(fileHolder.readImageBitmap(size / 4, false, false))
            }
        }
    }

    private fun refreshNotificationFromThread(notificationData: NotificationData) {
        if (builder == null || notificationData.type != oldNotificationDataType) {
            oldNotificationDataType = notificationData.type
            notificationManager!!.cancel(C.NOTIFICATION_ID_DOWNLOADING)
            builder = Notification.Builder(this, C.NOTIFICATION_CHANNEL_DOWNLOADING)
            builder!!.setDeleteIntent(
                PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent(this, Receiver::class.java)
                        .setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            builder!!.setSmallIcon(notificationData.type.iconResId)
            builder!!.setColor(notificationColor)
            if (notificationData.lastSuccessFile != null) {
                setBuilderImage(notificationData.lastSuccessFile)
            }
            when (notificationData.type) {
                NotificationData.Type.PROGRESS, NotificationData.Type.REQUEST -> {
                    builder!!.addAction(
                        Notification.Action.Builder(
                            null,
                            getString(android.R.string.cancel), PendingIntent.getBroadcast(
                                this, 0,
                                Intent(this, Receiver::class.java).setAction(ACTION_CANCEL),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                }

                NotificationData.Type.RESULT -> {
                    if (notificationData.allowRetry) {
                        builder!!.addAction(
                            Notification.Action.Builder(
                                null,
                                getString(R.string.retry), PendingIntent.getBroadcast(
                                    this, 0,
                                    Intent(this, Receiver::class.java).setAction(ACTION_RETRY),
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                            ).build()
                        )
                    }
                }
            }
            if (notificationData.type == NotificationData.Type.REQUEST) {
                builder!!.setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else if (notificationData.lastSuccessFile != null) {
                builder!!.setContentIntent(
                    PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(this, Receiver::class.java)
                            .putExtra(
                                EXTRA_FILE_TARGET,
                                notificationData.lastSuccessFile.target.name
                            )
                            .putExtra(
                                EXTRA_FILE_PATH,
                                notificationData.lastSuccessFile.getRelativePath()
                            )
                            .putExtra(EXTRA_ALLOW_WRITE, notificationData.allowWrite)
                            .setAction(ACTION_OPEN),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }
        val contentTitle: String?
        val contentText: String?
        val headsUp: Boolean
        val foreground: Boolean
        when (notificationData.type) {
            NotificationData.Type.PROGRESS -> {
                var ready = notificationData.errorTasks + notificationData.successTasks
                val total = ready + notificationData.queuedTasks + 1
                ready++
                contentTitle =
                    getString(R.string.downloading_number_of_number__format, ready, total)
                contentText = getString(R.string.file_name__format, notificationData.activeName)
                headsUp = false
                foreground = true
                val progressStyle = ProgressStyle()
                val indeterminate =
                    notificationData.progressMax == 0 || notificationData.progress > notificationData.progressMax || notificationData.progress < 0
                if (indeterminate) {
                    progressStyle.setProgressIndeterminate(true)
                } else {
                    progressStyle.setProgressSegments(
                        mutableListOf<ProgressStyle.Segment?>(
                            ProgressStyle.Segment(notificationData.progressMax)
                        )
                    )
                    progressStyle.setProgress(notificationData.progress)
                    builder!!.setShortCriticalText(
                        (100 * notificationData.progress
                                / notificationData.progressMax).toString() + "%"
                    )
                }
                builder!!.setStyle(progressStyle)
            }

            NotificationData.Type.RESULT -> {
                contentTitle = getString(
                    if (notificationData.hasNotFromCache)
                        R.string.download_completed
                    else
                        R.string.save_completed
                )
                contentText = getString(
                    R.string.success_number_not_loaded_number__format,
                    notificationData.successTasks, notificationData.errorTasks
                )
                headsUp = notificationData.allowHeadsUp
                foreground = false
            }

            NotificationData.Type.REQUEST -> {
                contentTitle = getString(R.string.pending_downloading)
                contentText = getString(R.string.confirmation_is_required)
                headsUp = false
                foreground = true
            }

            else -> {
                throw IllegalStateException()
            }
        }
        builder!!.setContentTitle(contentTitle)
        builder!!.setContentText(contentText)
        // Importance, sound and vibration are governed by the channels themselves.
        builder!!.setChannelId(
            if (headsUp && isNotifyDownloadComplete)
                C.NOTIFICATION_CHANNEL_DOWNLOADING_COMPLETE
            else
                C.NOTIFICATION_CHANNEL_DOWNLOADING
        )

        startStopForeground(foreground, if (foreground) builder!!.build() else null)
        if (!foreground) {
            // Await notification removed so it could be dismissed by user
            for (i in 0..9) {
                val notifications = notificationManager!!.getActiveNotifications()
                if (notifications == null) {
                    break
                }
                var found = false
                for (notification in notifications) {
                    if (notification.getId() == C.NOTIFICATION_ID_DOWNLOADING) {
                        found = true
                        break
                    }
                }
                if (!found) {
                    break
                }
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    return
                }
            }

            notificationManager!!.notify(C.NOTIFICATION_ID_DOWNLOADING, builder!!.build())
        }
    }

    private enum class NotificationUpdate {
        NORMAL, HEADS_UP, SYNC
    }

    private fun refreshNotification(notificationUpdate: NotificationUpdate?) {
        val hasActiveTask = activeTask != null
        val hasResults = !queuedTasks.isEmpty() || !successTasks.isEmpty() || !errorTasks.isEmpty()
        val hasRequests = primaryRequest != null || !directRequests.isEmpty()
        val needForegroundOrNotification = hasActiveTask || hasResults || hasRequests
        if (needForegroundOrNotification) {
            var allowRetry = false
            var hasNotFromCache = false
            var lastSuccessFileTaskData: TaskData? = null
            if (hasActiveTask || !hasRequests) {
                for (taskData in successTasks.values) {
                    if (!taskData.finishedFromCache) {
                        hasNotFromCache = true
                    }
                    if (taskData.target.isExternal) {
                        lastSuccessFileTaskData = taskData
                    }
                }
                for (taskData in errorTasks.values) {
                    if (!taskData.finishedFromCache) {
                        hasNotFromCache = true
                    }
                    if (taskData.uri != null) {
                        allowRetry = true
                    }
                }
            }

            val allowWrite = lastSuccessFileTaskData != null && lastSuccessFileTaskData.allowWrite

            val type = if (hasActiveTask) NotificationData.Type.PROGRESS else if (hasRequests)
                NotificationData.Type.REQUEST
            else
                NotificationData.Type.RESULT

            val allowHeadsUp = type == NotificationData.Type.RESULT &&
                    notificationUpdate == NotificationUpdate.HEADS_UP
            val activeName = if (hasActiveTask) activeTask!!.second!!.getFileName() else null

            notificationsQueue.add(
                NotificationData.Companion.updateData(
                    type,
                    allowHeadsUp,
                    queuedTasks.size,
                    successTasks.size,
                    errorTasks.size,
                    allowRetry,
                    hasNotFromCache,
                    lastSuccessTaskDataFile,
                    allowWrite,
                    activeName,
                    progress,
                    progressMax
                )
            )
        }
        if (hasActiveTask) {
            wakeLock!!.acquire()
        } else {
            wakeLock!!.acquire(15000)
        }
        if (notificationUpdate == NotificationUpdate.SYNC || !needForegroundOrNotification) {
            val syncLatch = CountDownLatch(1)
            notificationsQueue.add(NotificationData.Companion.sync(syncLatch))
            try {
                syncLatch.await()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
        }
        if (!needForegroundOrNotification) {
            startStopForeground(false, null)
        }
    }

    override fun onStartDownloading() {
        progress = 0
        progressMax = 0
        refreshNotification(NotificationUpdate.NORMAL)
    }

    override fun onFinishDownloading(
        success: Boolean,
        uri: Uri,
        file: DataFile,
        errorItem: ErrorItem?
    ) {
        val taskData =
            activeTask!!.first!!.newFinishedFromCache(activeTask!!.second!!.isDownloadingFromCache())
        activeTask = null
        onFinishDownloadingInternal(success, taskData)
    }

    private fun onFinishDownloadingInternal(success: Boolean, taskData: TaskData) {
        val file = activeTaskDataFile!!.getFileOrUri().first

        if (success) {
            if (file != null) {
                scanFileLegacy(file, null)
            }
        }

        for (callback in callbacks) {
            callback.onFinishDownloading(success, taskData.target, taskData.path, taskData.name)
        }
        if (success) {
            lastSuccessTaskDataFile = activeTaskDataFile
            successTasks.put(taskData.key, taskData)
        } else {
            errorTasks.put(taskData.key, taskData)
        }
        if (!queuedTasks.isEmpty()) {
            if (success && taskData.target.isExternal) {
                // Update image explicitly, because task type won't be changed
                notificationsQueue.add(
                    NotificationData.Companion.updateImageOnly(
                        lastSuccessTaskDataFile,
                        taskData.allowWrite
                    )
                )
            }
            startNextTask()
        } else {
            cachedDirectories.clear()
            activeTaskDataFile = null
            refreshNotification(NotificationUpdate.HEADS_UP)
        }
    }


    override fun onUpdateProgress(progress: Long, progressMax: Long) {
        this.progress = (progress / 1000).toInt()
        this.progressMax = (progressMax / 1000).toInt()
        val t = SystemClock.elapsedRealtime()
        if (t - lastUpdate >= 500L) {
            lastUpdate = t
            refreshNotification(NotificationUpdate.NORMAL)
        }
    }

    private fun interface ScanCallback {
        fun onComplete(uri: Uri?)
    }

    private fun scanFileLegacy(file: File, callback: Pair<String?, ScanCallback?>?) {
        val fileArray = arrayOf<String?>(file.getAbsolutePath())
        val listener: OnScanCompletedListener?
        if (callback != null) {
            val handled = booleanArrayOf(false)
            listener = OnScanCompletedListener { f: String?, uri: Uri? ->
                synchronized(handled) {
                    if (!handled[0]) {
                        handled[0] = true
                        callback.second!!.onComplete(uri)
                    }
                }
            }
            ConcurrentUtils.HANDLER.postDelayed(Runnable {
                synchronized(handled) {
                    if (!handled[0]) {
                        handled[0] = true
                        callback.second!!.onComplete(
                            convertDownloadsLegacyFile(
                                file,
                                callback.first
                            )
                        )
                    }
                }
            }, 1000)
        } else {
            listener = null
        }
        MediaScannerConnection.scanFile(this, fileArray, null, listener)
    }

    fun interface Accumulate : Closeable {
        override fun close()
    }

    interface Request {
        fun cleanup() {}
    }

    abstract class ChoiceRequest protected constructor(
		internal val shouldHandle: Boolean,
	    val allowWrite: Boolean,
	    @JvmField val chanName: String?,
	    @JvmField val boardName: String?,
	    @JvmField val threadNumber: String?,
	    @JvmField val threadTitle: String?
    ) : Request {
        @JvmField
        var state: Any? = null

        abstract fun allowDetailName(): Boolean
        abstract fun allowOriginalName(): Boolean
        abstract fun complete(
            path: String?,
            detailName: Boolean,
            originalName: Boolean
        ): DirectRequest
    }

    class DirectRequest internal constructor(
        val target: DataFile.Target,
        val path: String?,
        val overwrite: Boolean,
        val downloadItems: MutableList<DownloadItem>,
        val input: InputStream?,
        val allowWrite: Boolean
    ) {
        internal fun cleanup() {
            if (input != null) {
                close(input)
            }
        }
    }

    class ReplaceRequest internal constructor(
		internal val directRequest: DirectRequest,
	    internal val availableItems: MutableList<DownloadItem>,
	    @JvmField val lastExistingFile: DataFile?,
	    @JvmField val queued: Int,
	    @JvmField val exists: Int
    ) : Request {
        enum class Action {
            REPLACE, KEEP_ALL, SKIP
        }

        @JvmField
        var state: Any? = null

        override fun cleanup() {
            directRequest.cleanup()
        }
    }

    internal class PrepareRequest(internal val task: PrepareTask<*>) : Request {
        override fun cleanup() {
            task.innerTask.cleanup()
            task.cancel()
        }
    }

    private class UriRequest(
        shouldHandle: Boolean, val items: MutableList<RequestItem>,
        val allowDetailName: Boolean, val allowOriginalName: Boolean,
        chanName: String?, boardName: String?, threadNumber: String?, threadTitle: String?
    ) : ChoiceRequest(shouldHandle, false, chanName, boardName, threadNumber, threadTitle) {
        override fun allowDetailName(): Boolean {
            return allowDetailName
        }

        override fun allowOriginalName(): Boolean {
            return allowOriginalName
        }

        override fun complete(
            path: String?,
            detailName: Boolean,
            originalName: Boolean
        ): DirectRequest {
            val downloadItems: MutableList<DownloadItem> = ArrayList<DownloadItem>(items.size)
            for (requestItem in items) {
                downloadItems.add(
                    DownloadItem(
                        chanName, requestItem.uri, Companion.getDesiredFileName(
                            requestItem.uri,
                            requestItem.fileName,
                            (if (originalName) requestItem.originalName else null)!!,
                            detailName,
                            chanName,
                            boardName,
                            threadNumber
                        ), null, null
                    )
                )
            }
            return DirectRequest(
                DataFile.Target.DOWNLOADS,
                path,
                true,
                downloadItems,
                null,
                allowWrite
            )
        }
    }

    private class StreamRequest(
        shouldHandle: Boolean, allowWrite: Boolean, val input: InputStream?, val fileName: String,
        chanName: String?, boardName: String?, threadNumber: String?, threadTitle: String?
    ) : ChoiceRequest(shouldHandle, allowWrite, chanName, boardName, threadNumber, threadTitle) {
        override fun allowDetailName(): Boolean {
            return true
        }

        override fun allowOriginalName(): Boolean {
            return false
        }

        override fun complete(
            path: String?,
            detailName: Boolean,
            originalName: Boolean
        ): DirectRequest {
            val fileName = if (detailName) getFileNameWithChanBoardThreadData(
                this.fileName,
                chanName, boardName, threadNumber
            ) else this.fileName
            val downloadItem = DownloadItem(chanName, null, fileName, null, null)
            return DirectRequest(
                DataFile.Target.DOWNLOADS, path, true,
                mutableListOf<DownloadItem>(downloadItem), input, allowWrite
            )
        }

        override fun cleanup() {
            if (input != null) {
                close(input)
            }
        }
    }

    class RequestItem(val uri: Uri?, val fileName: String, val originalName: String?)

    class DownloadItem(
        val chanName: String?, val uri: Uri?, val name: String?,
        val checkSha256: ByteArray?, val checkFingerprints: Fingerprints?
    ) : Parcelable {
        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeString(chanName)
            dest.writeString(if (uri != null) uri.toString() else null)
            dest.writeString(name)
            dest.writeByteArray(checkSha256)
            dest.writeByte((if (checkFingerprints != null) 1 else 0).toByte())
            if (checkFingerprints != null) {
                checkFingerprints.writeToParcel(dest, flags)
            }
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o is DownloadItem) {
                val co = o
                return (equals(uri, co.uri)) &&
                        equals(name, co.name)
            }
            return false
        }

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + (if (uri != null) uri.hashCode() else 0)
            result = prime * result + (if (name != null) name.hashCode() else 0)
            return result
        }

        companion object {
            val CREATOR: Parcelable.Creator<DownloadItem?> =
                object : Parcelable.Creator<DownloadItem?> {
                    override fun newArray(size: Int): Array<DownloadItem?> {
                        return arrayOfNulls<DownloadItem>(size)
                    }

                    override fun createFromParcel(source: Parcel): DownloadItem {
                        val chanName = source.readString()
                        val uriString = source.readString()
                        val name = source.readString()
                        val checkSha256 = source.createByteArray()
                        val checkFingerprints = if (source.readByte().toInt() != 0)
                            Fingerprints.CREATOR.createFromParcel(source)
                        else
                            null
                        return DownloadItem(
                            chanName, if (uriString != null) Uri.parse(uriString) else null, name,
                            checkSha256, checkFingerprints
                        )
                    }
                }
        }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = if (intent != null) intent.getAction() else null
            val cancel = ACTION_CANCEL == action
            val retry = ACTION_RETRY == action
            val open = ACTION_OPEN == action
            val bindContext = context.getApplicationContext()
            if (cancel || retry || open) {
                val targetString = intent!!.getStringExtra(EXTRA_FILE_TARGET)
                val path = intent.getStringExtra(EXTRA_FILE_PATH)
                val allowWrite = intent.getBooleanExtra(EXTRA_ALLOW_WRITE, false)
                val file = if (targetString != null && path != null)
                    obtain(DataFile.Target.valueOf(targetString), path)
                else
                    null
                // Broadcast receivers can't bind to services
                val connection = arrayOf<ServiceConnection?>(null)
                connection[0] = object : ServiceConnection {
                    override fun onServiceConnected(
                        componentName: ComponentName?,
                        binder: IBinder?
                    ) {
                        val downloadBinder = binder as Binder
                        if (cancel) {
                            downloadBinder.cancelAll()
                        } else if (retry) {
                            downloadBinder.retry()
                        } else if (open) {
                            downloadBinder.open(file!!, allowWrite)
                        }
                        bindContext.unbindService(connection[0]!!)
                    }

                    override fun onServiceDisconnected(componentName: ComponentName?) {}
                }
                bindContext.bindService(
                    Intent(context, DownloadService::class.java),
                    connection[0]!!,
                    BIND_AUTO_CREATE
                )
            }
        }
    }

    companion object {
        private val SINGLE_THREAD_EXECUTOR: Executor = Executors.newSingleThreadExecutor()

        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_RETRY = "retry"
        private const val ACTION_OPEN = "open"

        private const val EXTRA_FILE_TARGET = "fileTarget"
        private const val EXTRA_FILE_PATH = "filePath"
        private const val EXTRA_ALLOW_WRITE = "allowWrite"

        private val savedDownloadRetryFile: File
            get() = CacheManager.getInstance().getInternalCacheFile("saved-download-retry")

        private fun getTargetPathKey(target: DataFile.Target?, path: String): String {
            return target.toString() + ":" + path.lowercase(Locale.getDefault())
        }

        private fun getTargetPathKey(
            target: DataFile.Target?,
            path: String?,
            name: String?
        ): String {
            val fullPath: String =
                (if (!StringUtils.isEmpty(path)) path + "/" + name else name)!!
            return getTargetPathKey(target, fullPath)
        }

        private fun getFileNameWithChanBoardThreadData(
            fileName: String,
            chanName: String?, boardName: String?, threadNumber: String?
        ): String {
            var fileName = fileName
            val extension = getFileExtension(fileName)
            fileName = fileName.substring(0, fileName.length - extension!!.length - 1)
            val builder = StringBuilder()
            builder.append(fileName)
            if (chanName != null) {
                builder.append('-').append(chanName)
            }
            if (boardName != null) {
                builder.append('-').append(boardName)
            }
            if (threadNumber != null) {
                builder.append('-').append(threadNumber)
            }
            return builder.append('.').append(extension).toString()
        }

        private fun isFileNameModifyingAllowed(chanName: String?, uri: Uri?): Boolean {
            val chan = getPreferred(chanName, uri)
            return chanName != null && chan.locator.safe(false).isAttachmentUri(uri)
        }

        private fun getDesiredFileName(
            uri: Uri?, fileName: String, originalName: String, detailName: Boolean,
            chanName: String?, boardName: String?, threadNumber: String?
        ): String {
            var fileName = fileName
            if (isFileNameModifyingAllowed(chanName, uri)) {
                if (!isEmpty(originalName) && isDownloadOriginalName) {
                    fileName = originalName
                }
                if (detailName) {
                    fileName = getFileNameWithChanBoardThreadData(
                        fileName,
                        chanName,
                        boardName,
                        threadNumber
                    )
                }
            }
            return fileName
        }
    }
}
