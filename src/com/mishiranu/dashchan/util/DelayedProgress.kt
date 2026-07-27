package com.mishiranu.dashchan.util

/**
 * Calls [show] only once work has been running for [DELAY_MS], and [hide] once the last of it is
 * done.
 *
 * For work that is usually instant but occasionally slow — a command
 * ([com.mishiranu.dashchan.content.CommandRunner]) is user JavaScript: most runs finish in a few
 * milliseconds, one that reaches the network takes seconds. Showing an indicator unconditionally
 * would flash one on every quick run, so the indicator is deferred and simply never appears when the
 * work beats the delay; [hide] is called only when [show] actually happened.
 *
 * Overlapping work is counted rather than stacked: several runs at once share one indicator (the
 * thread screen fires all of its auto-run commands together), and a chain of runs can hold it across
 * the whole chain by wrapping the chain in a single [start]/[finish] instead of each link, so the
 * indicator neither flickers between the links nor restarts its delay on each of them.
 *
 * Everything happens on the main thread, and the indicator is driven only from here, so a screen that
 * goes away with work still in flight — its callbacks finding it gone and never reaching [finish] —
 * has to [cancel].
 */
class DelayedProgress(
    private val show: () -> Unit,
    private val hide: () -> Unit,
) {
    private var running = 0
    private var shown = false

    /** Whether [show] has happened and [hide] has not, for an indicator shared with something else. */
    val isShowing: Boolean
        get() = shown

    private val showRunnable =
        Runnable {
            shown = true
            show()
        }

    /** Counts one unit of work as started. Must be paired with a [finish] or a [cancel]. */
    fun start() {
        running++
        if (running == 1) {
            ConcurrentUtils.HANDLER.postDelayed(showRunnable, DELAY_MS)
        }
    }

    /** Counts one unit of work as done, hiding the indicator once no work is left. */
    fun finish() {
        if (running > 0) {
            running--
            if (running == 0) {
                cancel()
            }
        }
    }

    /** Hides the indicator and forgets the outstanding work outright. */
    fun cancel() {
        running = 0
        ConcurrentUtils.HANDLER.removeCallbacks(showRunnable)
        if (shown) {
            shown = false
            hide()
        }
    }

    companion object {
        private const val DELAY_MS = 500L
    }
}
