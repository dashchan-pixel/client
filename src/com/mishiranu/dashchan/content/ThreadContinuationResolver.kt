package com.mishiranu.dashchan.content

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanLocator
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.content.RedirectException
import chan.content.ThreadRedirectException
import chan.http.HttpException
import chan.http.HttpHolder
import chan.util.StringUtils
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.content.database.PagesDatabase
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.widget.ThemeEngine

/**
 * Follows a favorite thread into its continuation (a "перекат"): once the thread has reached the
 * bump limit and one of its newest posts is nothing but a link to a newer thread of the same board,
 * fetches that thread's original post and, if its subject continues the current one, adds it to
 * favorites in place of the thread that ended.
 *
 * Driven from [com.mishiranu.dashchan.content.async.ReadPostsTask], so it covers both the background
 * watcher and a thread open in the UI, and inherits all of the watcher's throttling. The detection
 * itself lives in [ThreadContinuation]; this is the orchestration around it: gates, the one probe
 * request, the rejected-candidate memory, and applying the result.
 */
object ThreadContinuationResolver {
    /**
     * A thread is probed at most this often. Without it the watcher would re-probe the same dead
     * candidate on every refresh interval, forever.
     */
    private const val PROBE_INTERVAL_MS = 60 * 60 * 1000L

    private const val TAG = "ThreadContinuation"

    private const val EXTRA_TITLE = "com.mishiranu.dashchan.extra.CONTINUATION_TITLE"
    private const val EXTRA_WATCHER_ENABLED = "com.mishiranu.dashchan.extra.CONTINUATION_WATCHER_ENABLED"

    private val probeLock = Any()
    private val lastProbes = HashMap<String, Long>()
    private val rejectedCandidates = HashMap<String, MutableSet<String>>()

    /**
     * Called on the reading worker thread with the freshly fetched [posts] (in partial mode these
     * *are* the newest posts of the thread) and the task's still-usable [holder].
     *
     * Never throws: the caller is in the middle of a successful read and an optional feature must
     * not be able to turn it into a failure.
     */
    fun onReadPostsSuccess(
        chan: Chan,
        boardName: String?,
        threadNumber: String,
        posts: List<Post>,
        holder: HttpHolder,
    ) {
        try {
            resolve(chan, boardName, threadNumber, posts, holder)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to resolve a thread continuation", e)
        }
    }

    private fun resolve(
        chan: Chan,
        boardName: String?,
        threadNumber: String,
        posts: List<Post>,
        holder: HttpHolder,
    ) {
        // Gate 1 is also checked at the call site, so the disabled default costs literally nothing
        val mode = Preferences.favoriteContinuation
        if (mode == Preferences.FavoriteContinuationMode.DISABLED) {
            return
        }
        val chanName = chan.name ?: return
        // Gate 5: a local archive has no continuation to follow
        if (chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE)) {
            return
        }
        // The read this rides on has already been delivered; a cancelled task must not go on to
        // block on the main thread or issue another request
        if (holder.isInterrupted) {
            return
        }

        // Gates 2 and 3: a favorite thread whose continuation has not been resolved yet. Read on
        // the main thread, the only thread FavoritesStorage is ever mutated from.
        val unresolvedFavorite =
            ConcurrentUtils.mainGet {
                val favoriteItem =
                    FavoritesStorage.getInstance().getFavorite(chanName, boardName, threadNumber)
                favoriteItem != null && favoriteItem.successorThreadNumber == null
            }
        if (unresolvedFavorite != true) {
            return
        }

        val threadKey = PagesDatabase.ThreadKey(chanName, boardName, threadNumber)
        val originalPost = PagesDatabase.getInstance().getOriginalPost(threadKey) ?: return
        // Gate 4: a cyclical thread rolls its own oldest posts off and by definition never produces
        // a continuation, so a thread link inside one is ordinary cross-linking — and since it sits
        // permanently above the bump limit it would trip gate 6 on every single refresh, forever.
        // Sticky threads don't get continuations either. Mirrors PostItem.getBumpLimitReachedState,
        // which excludes both for the same reason.
        if (originalPost.isCyclical || originalPost.isSticky) {
            return
        }
        // Gate 6. Archived and closed threads stay allowed: a closed thread is the most likely place
        // for a continuation link to be sitting.
        if (!isBumpLimitReached(chan, boardName, threadKey, originalPost)) {
            return
        }

        val locator = chan.locator
        val safeLocator = locator.safe(false)
        val candidate =
            ThreadContinuation.findCandidate(posts.map { it.comment }, threadNumber) { link ->
                resolveThreadLink(locator, safeLocator, boardName, link)
            } ?: return

        val key = makeKey(chanName, boardName, threadNumber)
        if (!allowProbe(key, candidate.threadNumber)) {
            return
        }
        // Local signals alone are not enough to mutate the user's favorites
        val subject = probeSubject(chan, chanName, boardName, candidate.threadNumber, holder) ?: return
        val act =
            when (ThreadContinuation.subjectRelation(originalPost.subject, subject)) {
                ThreadContinuation.SubjectRelation.MATCH -> true

                // The probe can neither confirm nor deny — no subject to compare, or a volume
                // number that continues under a title that drifted — so lean on how unambiguous
                // the link itself was
                ThreadContinuation.SubjectRelation.INCONCLUSIVE -> candidate.strongLink

                ThreadContinuation.SubjectRelation.MISMATCH -> false
            }
        if (!act) {
            reject(key, candidate.threadNumber)
            return
        }
        val newCount = PagesDatabase.getInstance().getWatcherState(threadKey).newCount
        apply(mode, threadKey, boardName, candidate.threadNumber, subject, newCount)
    }

    private fun isBumpLimitReached(
        chan: Chan,
        boardName: String?,
        threadKey: PagesDatabase.ThreadKey,
        originalPost: Post,
    ): Boolean {
        // The flag some extensions set directly
        if (originalPost.isBumpLimitReached) {
            return true
        }
        val bumpLimit = chan.configuration.getBumpLimitWithMode(boardName)
        // With no bump limit and no flag, bail out: guessing here is how you get false positives on
        // slow boards
        if (bumpLimit == ChanConfiguration.BUMP_LIMIT_INVALID) {
            return false
        }
        return PagesDatabase.getInstance().getPostsCount(threadKey) >= bumpLimit
    }

    /**
     * Classifies a link found in a comment: the thread number when it points at another thread of
     * the same board of this chan, null otherwise. Cross-board continuations are rejected — the
     * subject check could carry them, but that is a separate decision.
     */
    private fun resolveThreadLink(
        locator: ChanLocator,
        safeLocator: ChanLocator.Safe,
        boardName: String?,
        link: String,
    ): String? {
        // Uri.parse defers failures to its accessors, and isChanHostOrRelative calls one of them
        // without the ChanLocator.Safe wrapper the extension calls below get
        try {
            val uri = Uri.parse(link)
            // Many extensions match thread URIs by path alone, so an unrelated host would pass
            if (!locator.isChanHostOrRelative(uri) || !safeLocator.isThreadUri(uri)) {
                return null
            }
            // A relative link carries no board name and therefore means the current board
            val linkBoardName = safeLocator.getBoardName(uri)
            if (linkBoardName != null &&
                StringUtils.emptyIfNull(linkBoardName) != StringUtils.emptyIfNull(boardName)
            ) {
                return null
            }
            return StringUtils.nullIfEmpty(safeLocator.getThreadNumber(uri))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to classify a link found in a comment", e)
            return null
        }
    }

    /**
     * Reads the candidate's original post to learn its subject, synchronously, on the calling worker
     * thread and reusing the caller's [holder]. Deliberately not routed through
     * [PagesDatabase.insertNewPosts]: a rejected candidate must leave no trace in the cache.
     *
     * Returns null when the probe failed — an empty subject is a result, not a failure.
     */
    private fun probeSubject(
        chan: Chan,
        chanName: String,
        boardName: String?,
        threadNumber: String,
        holder: HttpHolder,
    ): String? =
        try {
            val result =
                chan.performer.safe().onReadPosts(
                    ChanPerformer.ReadPostsData(
                        chanName,
                        boardName,
                        threadNumber,
                        null,
                        false,
                        false,
                        holder,
                        null,
                    ),
                )
            result
                ?.posts
                ?.filterNotNull()
                ?.minOrNull()
                ?.subject
        } catch (e: ExtensionException) {
            logProbeFailure(e)
        } catch (e: HttpException) {
            logProbeFailure(e)
        } catch (e: InvalidResponseException) {
            logProbeFailure(e)
        } catch (e: RedirectException) {
            logProbeFailure(e)
        } catch (e: ThreadRedirectException) {
            logProbeFailure(e)
        }

    /**
     * A probe that failed says nothing about the candidate, so the caller records no rejection and
     * the hourly throttle alone decides when it may be tried again. Always null, so it reads as the
     * value of a catch block.
     */
    private fun logProbeFailure(e: Exception): String? {
        Log.w(TAG, "Failed to read a candidate continuation", e)
        return null
    }

    private fun apply(
        mode: Preferences.FavoriteContinuationMode,
        threadKey: PagesDatabase.ThreadKey,
        boardName: String?,
        successorThreadNumber: String,
        subject: String,
        newCount: Int,
    ) {
        val chanName = threadKey.chanName
        val threadNumber = threadKey.threadNumber
        val title = StringUtils.nullIfEmpty(subject.trim())
        ConcurrentUtils.HANDLER.post {
            val favoritesStorage = FavoritesStorage.getInstance()
            val predecessor =
                favoritesStorage.getFavorite(chanName, boardName, threadNumber) ?: return@post
            if (predecessor.successorThreadNumber != null) {
                return@post
            }
            val resolved =
                Resolved(
                    chanName,
                    boardName,
                    successorThreadNumber,
                    threadNumber,
                    title,
                    predecessor.watcherEnabled,
                )
            if (mode == Preferences.FavoriteContinuationMode.ENABLED) {
                val successor =
                    FavoritesStorage.FavoriteItem(chanName, boardName, successorThreadNumber)
                // title, not modifiedTitle: the user hasn't renamed anything
                successor.title = title
                // If you were watching thread N you want to watch N+1. Going through
                // add(chanName, ..., allowWatcherEnabled) would consult
                // Preferences.isWatcherWatchInitially, which is the wrong input here.
                successor.watcherEnabled = resolved.watcherEnabled
                favoritesStorage.add(successor)
            }
            favoritesStorage.setSuccessorThreadNumber(
                chanName,
                boardName,
                threadNumber,
                successorThreadNumber,
            )
            if (mode == Preferences.FavoriteContinuationMode.ENABLED &&
                Preferences.isFavoriteContinuationRemove &&
                newCount == 0
            ) {
                // Silently dropping unread posts would be worse than a stale favorite
                favoritesStorage.remove(chanName, boardName, threadNumber)
            }
            val context = MainApplication.getInstance()
            WatcherNotifications.notifyContinuation(
                context,
                ThemeEngine.attachAndApply(context).accent,
                resolved,
                mode == Preferences.FavoriteContinuationMode.NOTIFY,
            )
        }
    }

    private fun allowProbe(
        key: String,
        candidateThreadNumber: String,
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(probeLock) {
            if (rejectedCandidates[key]?.contains(candidateThreadNumber) == true) {
                return false
            }
            val lastProbe = lastProbes[key]
            if (lastProbe != null && now - lastProbe < PROBE_INTERVAL_MS) {
                return false
            }
            lastProbes[key] = now
            return true
        }
    }

    private fun reject(
        key: String,
        candidateThreadNumber: String,
    ) {
        synchronized(probeLock) {
            rejectedCandidates.getOrPut(key) { HashSet() }.add(candidateThreadNumber)
        }
    }

    private fun makeKey(
        chanName: String,
        boardName: String?,
        threadNumber: String,
    ): String = chanName + "/" + StringUtils.emptyIfNull(boardName) + "/" + threadNumber

    /**
     * The continuation a favorite thread was followed into, as the notification needs it: enough to
     * open the successor, to label it, and to add it to favorites the way the predecessor was.
     */
    class Resolved(
        @JvmField val chanName: String,
        @JvmField val boardName: String?,
        @JvmField val threadNumber: String,
        @JvmField val predecessorThreadNumber: String,
        /** The successor's subject, or null when it has none — as a favorite's title, verbatim. */
        @JvmField val title: String?,
        @JvmField val watcherEnabled: Boolean,
    )

    fun createAddPendingIntent(
        context: Context,
        tag: String?,
        resolved: Resolved,
    ): PendingIntent {
        // The tag doubles as the action so pending intents of different notifications don't merge
        val intent =
            Intent(context, Receiver::class.java)
                .setAction(tag)
                .putExtra(C.EXTRA_CHAN_NAME, resolved.chanName)
                .putExtra(C.EXTRA_BOARD_NAME, resolved.boardName)
                .putExtra(C.EXTRA_THREAD_NUMBER, resolved.threadNumber)
                .putExtra(EXTRA_TITLE, resolved.title)
                .putExtra(EXTRA_WATCHER_ENABLED, resolved.watcherEnabled)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Backs the "add to favorites" action of a notify-only continuation notification. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent?,
        ) {
            val chanName = intent?.getStringExtra(C.EXTRA_CHAN_NAME) ?: return
            val threadNumber = intent.getStringExtra(C.EXTRA_THREAD_NUMBER) ?: return
            val favoriteItem =
                FavoritesStorage.FavoriteItem(
                    chanName,
                    intent.getStringExtra(C.EXTRA_BOARD_NAME),
                    threadNumber,
                )
            favoriteItem.title = intent.getStringExtra(EXTRA_TITLE)
            favoriteItem.watcherEnabled = intent.getBooleanExtra(EXTRA_WATCHER_ENABLED, false)
            FavoritesStorage.getInstance().add(favoriteItem)
            WatcherNotifications.cancelContinuation(context, intent.action)
        }
    }
}
