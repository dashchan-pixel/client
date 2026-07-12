package com.mishiranu.dashchan.content.service

import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.ChanConfiguration
import chan.content.RedirectException
import chan.util.CommonUtils.equals
import chan.util.StringUtils.emptyIfNull
import com.mishiranu.dashchan.content.LocaleManager.Companion.getInstance
import com.mishiranu.dashchan.content.NetworkObserver
import com.mishiranu.dashchan.content.NetworkObserver.Companion.getInstance
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.Preferences.NotificationFeature
import com.mishiranu.dashchan.content.Preferences.isWatcherWifiOnly
import com.mishiranu.dashchan.content.Preferences.watcherNotifications
import com.mishiranu.dashchan.content.Preferences.watcherRefreshInterval
import com.mishiranu.dashchan.content.WatcherNotifications
import com.mishiranu.dashchan.content.WatcherNotifications.configure
import com.mishiranu.dashchan.content.async.ExecutorTask
import com.mishiranu.dashchan.content.async.ReadPostsTask
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.database.PagesDatabase
import com.mishiranu.dashchan.content.database.PagesDatabase.InsertResult.Reply
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PendingUserPost
import com.mishiranu.dashchan.content.service.WatcherService.Session.Callback.ConsumeReplies
import com.mishiranu.dashchan.content.storage.DraftsStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.FavoritesStorage.FavoriteItem
import com.mishiranu.dashchan.content.storage.StatisticsStorage.Companion.getInstance
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ConcurrentUtils.newThreadPool
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ThemeEngine
import java.lang.Long
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.Any
import kotlin.Boolean
import kotlin.Comparable
import kotlin.IndexOutOfBoundsException
import kotlin.Int
import kotlin.String
import kotlin.booleanArrayOf
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.Iterable
import kotlin.collections.MutableCollection
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList
import kotlin.collections.MutableSet
import kotlin.collections.minus
import kotlin.compareTo
import kotlin.math.max
import kotlin.math.min

class WatcherService : BaseService() {
    class Counter(
		val state: State?,
	    val running: Boolean,
	    val newCount: Int,
	    @JvmField val deleted: Boolean,
	    val error: Boolean
    ) {
        enum class State {
            ENABLED, UNAVAILABLE, DISABLED
        }

        companion object {
            val INITIAL: Counter = Counter(State.DISABLED, false, 0, false, false)
        }
    }

    interface Client {
        interface Callback {
            val isWatcherClientForeground: Boolean
            fun onWatcherUpdate(
                chanName: String?,
                boardName: String?,
                threadNumber: String?,
                counter: Counter?
            )
        }

        var callback: Callback?
        fun updateConfiguration(chanName: String?)
        fun notifyForeground()
        fun refreshAll(chanName: String?)
        fun isWatcherSupported(chan: Chan): Boolean
        fun getCounter(chanName: String, boardName: String?, threadNumber: String): Counter
        fun newSession(
            chanName: String,
            boardName: String?,
            threadNumber: String,
            callback: Session.Callback
        ): Session
    }

    interface Session {
        interface Callback {
            fun interface ConsumeReplies {
                fun consume()
            }

            fun onReadPostsSuccess(
                cacheState: PagesDatabase.Cache.State?,
                consumeReplies: ConsumeReplies?
            )

            fun onReadPostsRedirect(target: RedirectException.Target?)
            fun onReadPostsFail(errorItem: ErrorItem?)
        }

        fun refresh(reload: Boolean, checkInterval: Int): Boolean
        fun notifyExtracted()
        fun notifyEraseStarted()
        fun hasTask(): Boolean
        fun destroy()
    }

    private interface InternalSession : Session.Callback {
        val isUpdateBlocked: Boolean
        fun notifyRefreshStarted()
    }

    private class ThreadKey(
        val chanName: String,
        val boardName: String?,
        val threadNumber: String
    ) {
        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o is ThreadKey) {
                val threadKey = o
                return chanName == threadKey.chanName &&
                        equals(boardName, threadKey.boardName) &&
                        threadNumber == threadKey.threadNumber
            }
            return false
        }

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + chanName.hashCode()
            result = prime * result + (if (boardName != null) boardName.hashCode() else 0)
            result = prime * result + threadNumber.hashCode()
            return result
        }
    }

    class Binder internal constructor(internal val service: WatcherService) : android.os.Binder()

    override fun onBind(intent: Intent?): Binder? {
        return Binder(this)
    }

    class ViewModel : ServiceViewModel<Binder>(WatcherService::class.java), Client {
        private val sessions: HashSet<ViewModelSession> = HashSet<ViewModelSession>()

        override var callback: Client.Callback? = null
        private var chanName: String? = null

        internal val service: WatcherService?
            get() {
                val binder =
                    getBinder()
                return if (binder != null) binder.service else null
            }

        public override fun onConnected(binder: Binder) {
            binder.service.registerClient(this, chanName)
            for (session in sessions) {
                session.handleRegister(binder.service, false)
            }
            if (callback != null && !binder.service.watcherItems.isEmpty()) {
                for (threadKey in binder.service.workWatcherKeys) {
                    val counter = binder.service.getCounter(threadKey)
                    callback!!.onWatcherUpdate(
                        threadKey.chanName,
                        threadKey.boardName,
                        threadKey.threadNumber,
                        counter
                    )
                }
            }
        }

        public override fun onDisconnected(binder: Binder) {
            for (session in sessions) {
                session.handleUnregister(binder.service)
            }
            binder.service.unregisterClient(this)
        }

        override fun updateConfiguration(chanName: String?) {
            this.chanName = chanName
            val service = this.service
            if (service != null) {
                service.registerClient(this, chanName)
            }
        }

        override fun notifyForeground() {
            val service = this.service
            if (service != null) {
                service.startNextFinished(true)
            }
        }

        override fun refreshAll(chanName: String?) {
            val service = this.service
            if (service != null) {
                service.refreshAll(chanName, true, true)
            }
        }

        override fun isWatcherSupported(chan: Chan): Boolean {
            return Companion.isWatcherSupported(chan)
        }

        override fun getCounter(
            chanName: String,
            boardName: String?,
            threadNumber: String
        ): Counter {
            val service = this.service
            if (service != null) {
                val threadKey = ThreadKey(chanName, boardName, threadNumber)
                return service.getCounter(threadKey)
            } else {
                return Counter.Companion.INITIAL
            }
        }

        override fun newSession(
            chanName: String,
            boardName: String?,
            threadNumber: String,
            callback: Session.Callback
        ): Session {
            val threadKey = ThreadKey(chanName, boardName, threadNumber)
            val session = ViewModelSession(this, threadKey, callback)
            sessions.add(session)
            val service = this.service
            if (service != null) {
                session.handleRegister(service, true)
            }
            return session
        }

        internal fun destroySession(session: ViewModelSession) {
            if (sessions.remove(session)) {
                val service = this.service
                if (service != null) {
                    session.handleUnregister(service)
                }
            }
        }
    }

    private class ViewModelSession(
        private val viewModel: ViewModel,
        private val threadKey: ThreadKey,
        private val callback: Session.Callback
    ) : Session, InternalSession {
        private enum class Running {
            NONE, REFRESH, RELOAD
        }

        private var running = Running.NONE
        private var notifyExtracted = false
        private var notifyEraseStarted = false
        private var erasing = false

        override fun refresh(reload: Boolean, checkInterval: Int): Boolean {
            val service = viewModel.service
            if (service != null) {
                var shouldStart = checkInterval <= 0 || reload
                val hasTask = service.hasTask(threadKey)
                if (!shouldStart && !hasTask) {
                    val watcherItem = service.watcherItems.get(threadKey)
                    shouldStart = watcherItem != null && watcherItem
                        .checkInterval(SystemClock.elapsedRealtime(), checkInterval)
                }
                if (shouldStart) {
                    running = if (reload) Running.RELOAD else Running.REFRESH
                    if (!hasTask || reload) {
                        service.refreshForeground(threadKey, reload)
                    }
                    return true
                } else {
                    return false
                }
            } else {
                running = if (reload) Running.RELOAD else Running.REFRESH
                return true
            }
        }

        override fun notifyExtracted() {
            erasing = false
            val service = viewModel.service
            if (service != null) {
                service.notifyExtracted(threadKey)
            } else {
                notifyExtracted = true
            }
        }

        override fun notifyEraseStarted() {
            erasing = true
            val service = viewModel.service
            if (service != null) {
                service.cancelBlockedUpdate(threadKey)
            } else {
                notifyEraseStarted = true
            }
        }

        override fun hasTask(): Boolean {
            val service = viewModel.service
            if (service != null) {
                return service.hasTask(threadKey)
            } else {
                return running != Running.NONE
            }
        }

        override fun destroy() {
            viewModel.destroySession(this)
        }

        override val isUpdateBlocked: Boolean
            get() = erasing

        override fun notifyRefreshStarted() {
            if (running == Running.NONE) {
                running = Running.REFRESH
            }
        }

        override fun onReadPostsSuccess(
            cacheState: PagesDatabase.Cache.State?,
            consumeReplies: ConsumeReplies?
        ) {
            running = Running.NONE
            callback.onReadPostsSuccess(cacheState, consumeReplies)
        }

        override fun onReadPostsRedirect(target: RedirectException.Target?) {
            running = Running.NONE
            callback.onReadPostsRedirect(target)
        }

        override fun onReadPostsFail(errorItem: ErrorItem?) {
            running = Running.NONE
            callback.onReadPostsFail(errorItem)
        }

        fun handleRegister(service: WatcherService, immediate: Boolean) {
            service.registerSession(this, threadKey)
            if (immediate) {
                running = Running.NONE
                notifyExtracted = false
                notifyEraseStarted = false
            } else {
                if (running != Running.NONE) {
                    val reload = running == Running.RELOAD
                    if (!service.hasTask(threadKey) || reload) {
                        service.refreshForeground(threadKey, reload)
                    }
                }
                if (notifyExtracted) {
                    notifyExtracted = false
                    service.notifyExtracted(threadKey)
                }
                if (notifyEraseStarted) {
                    notifyEraseStarted = false
                    service.cancelBlockedUpdate(threadKey)
                }
            }
        }

        fun handleUnregister(service: WatcherService) {
            if (running != Running.NONE) {
                running = Running.NONE
                callback.onReadPostsFail(ErrorItem(ErrorItem.Type.UNKNOWN))
            }
            service.unregisterSession(this, threadKey)
        }
    }

    private class ConcurrentIterable<T>(private val provider: Provider<T?>?) : Iterable<T?> {
        fun interface Provider<T> {
            fun getValues(): Collection<T?>?
        }

        private val workList = ArrayList<T?>()
        internal var valuesOnce: MutableCollection<T?>? = null

        override fun iterator(): MutableIterator<T?> {
            val values: Collection<T?>?
            if (valuesOnce != null) {
                values = valuesOnce
                valuesOnce = null
            } else if (provider != null) {
                values = provider.getValues()
            } else {
                values = null
            }
            if (values == null || values.isEmpty()) {
                val result = EMPTY as MutableIterator<T?>
                return result
            } else {
                workList.clear()
                workList.addAll(values)
                val iterator = workList.iterator()
                return object : MutableIterator<T?> {
                    override fun hasNext(): Boolean {
                        val hasNext = iterator.hasNext()
                        if (!hasNext) {
                            workList.clear()
                        }
                        return hasNext
                    }

                    override fun next(): T? {
                        return iterator.next()
                    }

                    override fun remove() {
                        throw UnsupportedOperationException()
                    }
                }
            }
        }

        companion object {
            private val EMPTY: MutableIterator<*> = object : MutableIterator<Any?> {
                override fun hasNext(): Boolean {
                    return false
                }

                override fun next(): Any? {
                    throw IndexOutOfBoundsException()
                }

                override fun remove() {
                    throw UnsupportedOperationException()
                }
            }
        }
    }

    private class ResolveItemsTask(
        private val callback: Callback,
        private val threads: MutableSet<ThreadKey>
    ) : ExecutorTask<Void?, MutableList<ResolveItemsTask.Item?>?>() {
        fun interface Callback {
            fun onResolveItemsResult(items: MutableList<Item?>?)
        }

        class Item(
            val threadKey: ThreadKey?,
            val newCount: Int,
            val deleted: Boolean,
            val error: Boolean,
            val lastUpdate: Long
        )

        override fun run(): MutableList<Item?> {
            val items = ArrayList<Item?>(threads.size)
            for (threadKey in threads) {
                val watcherState: PagesDatabase.WatcherState = PagesDatabase.getInstance()
                    .getWatcherState(
                        PagesDatabase.ThreadKey(
                            threadKey.chanName,
                            threadKey.boardName, threadKey.threadNumber
                        )
                    )
                val now = SystemClock.elapsedRealtime()
                val lastUpdate = min(now, watcherState.time - System.currentTimeMillis() + now)
                items.add(
                    Item(
                        threadKey, watcherState.newCount,
                        watcherState.deleted, watcherState.error, lastUpdate
                    )
                )
            }
            return items
        }

        override fun onComplete(result: MutableList<Item?>?) {
            callback.onResolveItemsResult(result)
        }
    }

    private class WatcherTask(val task: ReadPostsTask, val worker: Worker) {
        init {
            worker.acquire()
        }

        fun cancel() {
            task.cancel()
            worker.release()
        }
    }

    enum class WatcherState {
        IDLE, ENQUEUED, UNAVAILABLE
    }

    private inner class WatcherItem(val threadKey: ThreadKey) : Comparable<WatcherItem>,
        ReadPostsTask.Callback {
        var resolved: Boolean = false
        var newCount: Int = 0
        var deleted: Boolean = false
        var error: Boolean = false
        var lastUpdate: Long = 0

        var task: WatcherTask? = null
        var state: WatcherState = WatcherState.IDLE

        fun cancel() {
            if (task != null) {
                task!!.cancel()
                task = null
            }
        }

        fun createAndExecuteTask(worker: Worker, reload: Boolean, notifyBeforeStart: Boolean) {
            cancel()
            val pendingUserPosts =
                PostingService.Companion.getPendingUserPosts(
                    threadKey.chanName,
                    threadKey.boardName, threadKey.threadNumber
                )
            val task = ReadPostsTask(
                this, get(threadKey.chanName),
                threadKey.boardName, threadKey.threadNumber, reload, pendingUserPosts
            )
            task.execute(worker.executor)
            if (notifyBeforeStart) {
                for (session in getSessionConcurrentIterable(threadKey)) {
                    session.notifyRefreshStarted()
                }
            }
            this.task = WatcherTask(task, worker)
            notifyWatcherUpdate(this)
        }

        fun checkInterval(now: Long, interval: Int): Boolean {
            return lastUpdate + interval - 1000 <= now
        }

        override fun compareTo(other: WatcherItem): Int {
            return lastUpdate.compareTo(other.lastUpdate)
        }

        override fun onPendingUserPostsConsumed(pendingUserPosts: Set<PendingUserPost>) {
            if (!pendingUserPosts.isEmpty()) {
                PostingService.Companion.consumePendingUserPosts(
                    threadKey.chanName, threadKey.boardName,
                    threadKey.threadNumber, pendingUserPosts
                )
            }
        }

        override fun onReadPostsSuccess(
            cacheState: PagesDatabase.Cache.State?,
            replies: List<Reply>?, newCount: Int?
        ) {
            if (newCount != null) {
                resolved = true
                this.newCount = newCount
            }
            deleted = false
            error = false
            onTaskFinished()
            val notify = if (replies!!.isEmpty()) null else booleanArrayOf(true)
            var consumeReplies: ConsumeReplies? = null
            if (notify == null) {
                consumeReplies = CONSUME_REPLIES_EMPTY
            }
            for (session in getSessionConcurrentIterable(threadKey)) {
                if (consumeReplies == null) {
                    consumeReplies = ConsumeReplies { notify!![0] = false }
                }
                session.onReadPostsSuccess(cacheState, consumeReplies)
            }
            if (notify != null && notify[0] && !replies.isEmpty()) {
                val notificationFeatures = watcherNotifications
                if (notificationFeatures.contains(NotificationFeature.ENABLED)) {
                    val favoriteItem = FavoritesStorage.getInstance()
                        .getFavorite(
                            threadKey.chanName,
                            threadKey.boardName,
                            threadKey.threadNumber
                        )
                    var title = if (favoriteItem != null) emptyIfNull(favoriteItem.title) else ""
                    if (title.trim { it <= ' ' }.isEmpty()) {
                        val chan = get(threadKey.chanName)
                        title = chan.configuration.getTitle() + " / " +
                                threadKey.boardName + " / " + threadKey.threadNumber
                    }
                    val important = notificationFeatures.contains(NotificationFeature.IMPORTANT)
                    val sound = notificationFeatures.contains(NotificationFeature.SOUND)
                    val vibration = notificationFeatures.contains(NotificationFeature.VIBRATION)
                    WatcherNotifications.notifyReplies(
                        this@WatcherService,
                        notificationColor,
                        important,
                        sound,
                        vibration,
                        title,
                        threadKey.chanName,
                        threadKey.boardName,
                        threadKey.threadNumber,
                        replies
                    )
                }
            }
        }

        override fun onReadPostsRedirect(target: RedirectException.Target?) {
            deleted = true
            error = false
            onTaskFinished()
            for (session in getSessionConcurrentIterable(threadKey)) {
                session.onReadPostsRedirect(target)
            }
        }

        override fun onReadPostsFail(errorItem: ErrorItem) {
            val notExists = errorItem.type == ErrorItem.Type.THREAD_NOT_EXISTS
            deleted = notExists
            error = !notExists
            onTaskFinished()
            for (session in getSessionConcurrentIterable(threadKey)) {
                session.onReadPostsFail(errorItem)
            }
        }

        fun onTaskFinished() {
            task!!.worker.release()
            task = null
            lastUpdate = SystemClock.elapsedRealtime()
            state = WatcherState.IDLE
            enqueuedWatcherItems.remove(this)
            if (deleted) {
                FavoritesStorage.getInstance().setWatcherEnabled(
                    threadKey.chanName,
                    threadKey.boardName, threadKey.threadNumber, false
                )
            }
            startNext()
            notifyWatcherUpdate(this)
        }
    }

    private val clients = HashMap<Client?, String?>()
    private val sessionsMap: HashMap<ThreadKey?, HashSet<InternalSession?>?> =
        HashMap<ThreadKey?, HashSet<InternalSession?>?>()
    private val watcherItems: HashMap<ThreadKey?, WatcherItem> = HashMap<ThreadKey?, WatcherItem>()
    private val enqueuedWatcherItems: ArrayList<WatcherItem?> = ArrayList<WatcherItem?>()

    private val workWatcherKeys: Iterable<ThreadKey> = ConcurrentIterable<ThreadKey>(
        ConcurrentIterable.Provider { watcherItems.keys })
    private val workClients: Iterable<Client> =
        ConcurrentIterable<Client>(ConcurrentIterable.Provider { clients.keys })
    private val workSessions: ConcurrentIterable<InternalSession> =
        ConcurrentIterable<InternalSession>(null)
    private val workPriorityChanNames = HashSet<String?>()

    private var notificationColor = 0
    private var resolveItemsTask: ResolveItemsTask? = null
    private var lastRefreshAll: kotlin.Long = 0

    override fun onCreate() {
        super.onCreate()

        configure(this)
        updateNotificationColor()
        addOnDestroyListener(ChanDatabase.getInstance().requireCookies())
        Preferences.PREFERENCES!!.register(preferencesListener)
        FavoritesStorage.getInstance().getObservable().register(favoritesObserver)
        for (favoriteItem in FavoritesStorage.getInstance().getThreads(null)) {
            val threadKey = WatcherService.ThreadKey(
                favoriteItem.chanName,
                favoriteItem.boardName, favoriteItem.threadNumber!!
            )
            addWatcherItem(threadKey, false)
        }
        resolveWatcherItems()
        refreshAll(null, false, true)
    }

    public override fun onDestroy() {
        super.onDestroy()

        for (watcherItem in watcherItems.values) {
            if (watcherItem.task != null) {
                watcherItem.cancel()
                for (session in getSessionConcurrentIterable(watcherItem.threadKey)) {
                    session.onReadPostsFail(ErrorItem(ErrorItem.Type.UNKNOWN))
                }
            }
        }
        if (resolveItemsTask != null) {
            resolveItemsTask!!.cancel()
            resolveItemsTask = null
        }
        Preferences.PREFERENCES!!.unregister(preferencesListener)
        FavoritesStorage.getInstance().getObservable().unregister(favoritesObserver)
        ConcurrentUtils.HANDLER.removeCallbacks(refreshAllRunnable)
    }

    private val favoritesObserver =
        FavoritesStorage.Observer { favoriteItem: FavoriteItem?, action: FavoritesStorage.Action? ->
            if (favoriteItem!!.threadNumber == null) {
                return@Observer
            }
            val threadKey = ThreadKey(
                favoriteItem.chanName,
                favoriteItem.boardName,
                favoriteItem.threadNumber
            )
            when (action) {
                FavoritesStorage.Action.ADD -> {
                    val watcherItem = addWatcherItem(threadKey, true)
                    notifyWatcherUpdate(watcherItem)
                    startNext()
                }

                FavoritesStorage.Action.REMOVE -> {
                    removeOrCancelWatcherItemIfNotNeeded(threadKey)
                }

                FavoritesStorage.Action.WATCHER_ENABLE -> {
                    val watcherItem = watcherItems.get(threadKey)
                    if (watcherItem!!.state != WatcherState.ENQUEUED) {
                        watcherItem.state = WatcherState.ENQUEUED
                        enqueuedWatcherItems.add(watcherItem)
                        Collections.sort<WatcherItem?>(enqueuedWatcherItems)
                    }
                    if (watcherItem != null) {
                        notifyWatcherUpdate(watcherItem)
                    }
                    startNext()
                }

                FavoritesStorage.Action.WATCHER_DISABLE -> {
                    val watcherItem = removeOrCancelWatcherItemIfNotNeeded(threadKey)
                    if (watcherItem != null) {
                        notifyWatcherUpdate(watcherItem)
                    }
                }
            }
        }

    private fun resolveWatcherItems() {
        if (resolveItemsTask == null) {
            var resolveThreads: HashSet<ThreadKey>? = null
            for (watcherItem in watcherItems.values) {
                if (!watcherItem.resolved) {
                    if (resolveThreads == null) {
                        resolveThreads = HashSet<ThreadKey>()
                    }
                    resolveThreads.add(watcherItem.threadKey)
                }
            }
            if (resolveThreads != null && !resolveThreads.isEmpty()) {
                resolveItemsTask =
                    ResolveItemsTask(ResolveItemsTask.Callback { items: MutableList<ResolveItemsTask.Item?>? ->
                        this.onResolveWatcherItemResult(items)
                    }, resolveThreads)
                resolveItemsTask!!.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
            }
        }
    }

    private fun onResolveWatcherItemResult(items: MutableList<ResolveItemsTask.Item>) {
        resolveItemsTask = null
        for (item in items) {
            val watcherItem = watcherItems.get(item.threadKey)
            if (watcherItem != null && !watcherItem.resolved) {
                watcherItem.resolved = true
                watcherItem.newCount = item.newCount
                watcherItem.deleted = item.deleted
                watcherItem.error = item.error
                watcherItem.lastUpdate = item.lastUpdate
                notifyWatcherUpdate(watcherItem)
            }
        }
        Collections.sort<WatcherItem?>(enqueuedWatcherItems)
        resolveWatcherItems()
        startNext()
    }

    private fun startNext() {
        val priorityChanNames = workPriorityChanNames
        priorityChanNames.clear()
        priorityChanNames.addAll(clients.values)
        val iterator = enqueuedWatcherItems.iterator()
        while (iterator.hasNext()) {
            val watcherItem = iterator.next()
            if (watcherItem!!.resolved) {
                if (watcherItem!!.state != WatcherState.ENQUEUED) {
                    iterator.remove()
                } else if (watcherItem!!.task == null) {
                    val chan = get(watcherItem!!.threadKey.chanName)
                    if (isWatcherSupported(chan) && !isBlocked(watcherItem!!.threadKey)) {
                        val sessions = sessionsMap.get(watcherItem!!.threadKey)
                        val worker: Worker?
                        if (sessions != null && !sessions.isEmpty()) {
                            worker = WORKER_FOREGROUND
                        } else if (isEnabled(watcherItem!!.threadKey)) {
                            val priority =
                                priorityChanNames.contains(watcherItem!!.threadKey.chanName)
                            worker = if (priority) WORKER_PRIORITY else WORKER_BACKGROUND
                        } else {
                            watcherItem!!.state = WatcherState.IDLE
                            iterator.remove()
                            worker = null
                        }
                        if (worker != null && worker.isAvailable) {
                            watcherItem!!.createAndExecuteTask(worker, false, true)
                        }
                    } else {
                        watcherItem!!.state = WatcherState.IDLE
                        iterator.remove()
                    }
                }
            }
        }
        startNextFinished(false)
    }

    private fun startNextFinished(forceForeground: Boolean) {
        ConcurrentUtils.HANDLER.removeCallbacks(refreshAllRunnable)
        if (enqueuedWatcherItems.isEmpty()) {
            var foreground = forceForeground
            if (!foreground) {
                for (client in workClients) {
                    val callback =
                        client.callback
                    if (!foreground && callback != null && callback.isWatcherClientForeground) {
                        // Consume iterator completely
                        foreground = true
                    }
                }
            }
            val interval = getRefreshInterval(foreground)
            if (interval > 0) {
                if (lastRefreshAll == 0L) {
                    lastRefreshAll = SystemClock.elapsedRealtime()
                }
                val time = max(0, lastRefreshAll + interval - SystemClock.elapsedRealtime())
                ConcurrentUtils.HANDLER.postDelayed(refreshAllRunnable, time)
            }
        }
    }

    private fun notifyWatcherUpdate(watcherItem: WatcherItem) {
        val threadKey = watcherItem.threadKey
        for (client in workClients) {
            val callback =
                client.callback
            if (callback != null) {
                callback.onWatcherUpdate(
                    threadKey.chanName, threadKey.boardName,
                    threadKey.threadNumber, getCounter(watcherItem)
                )
            }
        }
    }

    private fun isBlocked(threadKey: ThreadKey?): Boolean {
        var blocked = false
        for (session in getSessionConcurrentIterable(threadKey)) {
            if (!blocked && session.isUpdateBlocked) {
                // Consume iterator completely
                blocked = true
            }
        }
        return blocked
    }

    private fun isEnabled(threadKey: ThreadKey): Boolean {
        val favoriteItem = FavoritesStorage.getInstance()
            .getFavorite(threadKey.chanName, threadKey.boardName, threadKey.threadNumber)
        return favoriteItem != null && favoriteItem.watcherEnabled
    }

    private fun isNeeded(watcherItem: WatcherItem, enabledOnly: Boolean): Boolean {
        val threadKey = watcherItem.threadKey
        val sessions = sessionsMap.get(threadKey)
        if (sessions != null && !sessions.isEmpty()) {
            return true
        }
        val favoriteItem = FavoritesStorage.getInstance().getFavorite(
            threadKey.chanName,
            threadKey.boardName, threadKey.threadNumber
        )
        return favoriteItem != null && (!enabledOnly || favoriteItem.watcherEnabled)
    }

    private fun removeOrCancelWatcherItemIfNotNeeded(threadKey: ThreadKey?): WatcherItem? {
        val watcherItem = watcherItems.get(threadKey)
        if (watcherItem != null && !isNeeded(watcherItem, true)) {
            watcherItem.cancel()
            if (isNeeded(watcherItem, false)) {
                notifyWatcherUpdate(watcherItem)
            } else {
                watcherItems.remove(threadKey)
                enqueuedWatcherItems.remove(watcherItem)
            }
            startNext()
            return null
        } else {
            return watcherItem
        }
    }

    private fun addWatcherItem(threadKey: ThreadKey, resolve: Boolean): WatcherItem {
        var watcherItem = watcherItems.get(threadKey)
        if (watcherItem == null) {
            watcherItem = WatcherItem(threadKey)
            watcherItems.put(threadKey, watcherItem)
            if (resolve) {
                resolveWatcherItems()
            }
        }
        return watcherItem
    }

    private fun registerClient(client: Client?, chanName: String?) {
        val newClient = !clients.containsKey(client)
        val oldChanName = clients.put(client, chanName)
        if (newClient || !equals(oldChanName, chanName)) {
            startNext()
        }
    }

    private fun unregisterClient(client: Client?) {
        clients.remove(client)
    }

    private fun registerSession(session: InternalSession?, threadKey: ThreadKey) {
        var sessions = sessionsMap.get(threadKey)
        if (sessions == null) {
            sessions = HashSet<InternalSession?>(1)
            sessionsMap.put(threadKey, sessions)
        }
        sessions.add(session)
        addWatcherItem(threadKey, true)
    }

    private fun unregisterSession(session: InternalSession?, threadKey: ThreadKey?) {
        val sessions = sessionsMap.get(threadKey)
        if (sessions != null) {
            val removed = sessions.remove(session)
            if (sessions.isEmpty()) {
                sessionsMap.remove(threadKey)
            }
            if (removed) {
                removeOrCancelWatcherItemIfNotNeeded(threadKey)
            }
        }
    }

    private fun getSessionConcurrentIterable(threadKey: ThreadKey?): Iterable<InternalSession> {
        workSessions.valuesOnce = sessionsMap.get(threadKey)
        return workSessions
    }

    private val refreshAllRunnable = Runnable {
        lastRefreshAll = 0
        refreshAll(null, false, false)
    }

    private fun refreshAll(chanName: String?, forceNetwork: Boolean, forceNow: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val interval = getRefreshInterval(true)
        val unavailable =
            !forceNetwork && isWatcherWifiOnly && !NetworkObserver.getInstance().isWifiConnected()
        for (watcherItem in watcherItems.values) {
            if (chanName == null || chanName == watcherItem.threadKey.chanName) {
                val chan = get(watcherItem.threadKey.chanName)
                if (isWatcherSupported(chan) && !isBlocked(watcherItem.threadKey) &&
                    isEnabled(watcherItem.threadKey)
                ) {
                    if (unavailable) {
                        if (watcherItem.state == WatcherState.IDLE) {
                            watcherItem.state = WatcherState.UNAVAILABLE
                            notifyWatcherUpdate(watcherItem)
                        }
                    } else {
                        if (watcherItem.state != WatcherState.ENQUEUED &&
                            (forceNow || watcherItem.checkInterval(now, interval))
                        ) {
                            watcherItem.state = WatcherState.ENQUEUED
                            notifyWatcherUpdate(watcherItem)
                            enqueuedWatcherItems.add(watcherItem)
                        }
                    }
                }
            }
        }
        Collections.sort<WatcherItem?>(enqueuedWatcherItems)
        startNext()
    }

    private fun refreshForeground(threadKey: ThreadKey?, reload: Boolean) {
        val watcherItem = watcherItems.get(threadKey)
        if (watcherItem != null) {
            watcherItem.createAndExecuteTask(WORKER_FOREGROUND, reload, false)
        }
    }

    private fun notifyExtracted(threadKey: ThreadKey?) {
        val watcherItem = watcherItems.get(threadKey)
        if (watcherItem != null && watcherItem.newCount > 0) {
            watcherItem.newCount = 0
            notifyWatcherUpdate(watcherItem)
        }
    }

    private fun cancelBlockedUpdate(threadKey: ThreadKey?) {
        val watcherItem = watcherItems.get(threadKey)
        if (watcherItem != null && watcherItem.task != null) {
            watcherItem.cancel()
            notifyWatcherUpdate(watcherItem)
        }
    }

    private fun hasTask(threadKey: ThreadKey?): Boolean {
        val watcherItem = watcherItems.get(threadKey)
        return watcherItem != null && watcherItem.task != null
    }

    private fun getCounter(threadKey: ThreadKey?): Counter {
        val watcherItem = watcherItems.get(threadKey)
        return if (watcherItem != null) getCounter(watcherItem) else Counter.Companion.INITIAL
    }

    private fun getCounter(watcherItem: WatcherItem): Counter {
        val threadKey = watcherItem.threadKey
        val enabled = isEnabled(threadKey)
        val state = if (enabled) if (watcherItem.state == WatcherState.UNAVAILABLE)
            Counter.State.UNAVAILABLE
        else
            Counter.State.ENABLED else Counter.State.DISABLED
        return Counter(
            state, watcherItem.task != null,
            watcherItem.newCount, watcherItem.deleted, watcherItem.error
        )
    }

    private fun getRefreshInterval(foreground: Boolean): Int {
        val interval = watcherRefreshInterval * 1000
        if (!foreground) {
            val backgroundInterval = 10 * 60 * 1000
            return max(interval, backgroundInterval)
        } else {
            return interval
        }
    }

    private val preferencesListener = SharedPreferences.Listener { key: String? ->
        if (Preferences.KEY_WATCHER_REFRESH_INTERVAL == key) {
            ConcurrentUtils.HANDLER.removeCallbacks(refreshAllRunnable)
            startNext()
        } else if (Preferences.KEY_THEME == key) {
            updateNotificationColor()
        }
    }

    private fun updateNotificationColor() {
        val theme = ThemeEngine.attachAndApply(this)
        notificationColor = theme!!.accent
    }

    private class Worker(internal val executor: Executor, private val limit: Int) {
        private var count = 0

        constructor(executor: Executor) : this(executor, 0)

        constructor(name: String?, limit: Int) : this(
            newThreadPool(limit, limit, 0, name, null),
            limit
        )

        val isAvailable: Boolean
            get() = limit <= 0 || count < limit

        fun acquire() {
            count++
        }

        fun release() {
            count--
        }
    }

    companion object {
        @JvmStatic
        fun getClient(activity: ComponentActivity): Client {
            return ViewModelProvider(activity).get<ViewModel>(ViewModel::class.java)
        }

        private fun isWatcherSupported(chan: Chan): Boolean {
            return chan.name != null && !chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE)
        }

        private val CONSUME_REPLIES_EMPTY = ConsumeReplies {}

        private val WORKER_FOREGROUND = Worker(ConcurrentUtils.PARALLEL_EXECUTOR)
        private val WORKER_PRIORITY = Worker("WatcherPriority", 3)
        private val WORKER_BACKGROUND = Worker("WatcherBackground", 3)
    }
}
