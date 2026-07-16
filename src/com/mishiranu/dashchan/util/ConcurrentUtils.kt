package com.mishiranu.dashchan.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Display
import com.mishiranu.dashchan.content.MainApplication
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object ConcurrentUtils {
    @JvmField val HANDLER = Handler(Looper.getMainLooper())

    @JvmField val SEPARATE_EXECUTOR = Executor { command -> Thread(command).start() }

    @JvmField val PARALLEL_EXECUTOR: Executor = newThreadPool(1, 20, 3000, "ParallelExecutor", null)

    /**
     * Budget for a chunk of main-thread work: half a frame. Derived from the fastest mode the
     * display supports, not the mode it is in — Pixels switch modes dynamically, and budgeting
     * for the fastest one is safe at every rate. Callers chunk against this and re-post to
     * continue, so a smaller budget spreads the same work over more frames rather than dropping
     * any of it. A fixed 60 Hz value would hand out 8 ms, an entire frame at 120 Hz.
     */
    @JvmStatic
    val HALF_FRAME_TIME_MS: Long by lazy {
        val display =
            (
                MainApplication.getInstance().getSystemService(Context.DISPLAY_SERVICE)
                    as DisplayManager?
            )?.getDisplay(Display.DEFAULT_DISPLAY)
        val refreshRate =
            display
                ?.supportedModes
                ?.maxOfOrNull { it.refreshRate }
                ?.takeIf { it >= 1f }
                ?: 60f
        (1000f / refreshRate / 2f).toLong().coerceAtLeast(1L)
    }

    @JvmStatic
    fun newSingleThreadPool(
        lifeTimeMs: Int,
        componentName: String?,
        componentPart: String?,
    ): ExecutorService =
        newThreadPool(
            if (lifeTimeMs > 0) 0 else 1,
            1,
            lifeTimeMs.toLong(),
            componentName,
            componentPart,
        )

    @JvmStatic
    fun newThreadPool(
        from: Int,
        to: Int,
        lifeTimeMs: Long,
        componentName: String?,
        componentPart: String?,
    ): ExecutorService =
        if (to > from && to >= 2) {
            val executeState = ThreadLocal.withInitial { false }
            val queue =
                object : LinkedBlockingQueue<Runnable>() {
                    override fun offer(e: Runnable): Boolean = !executeState.get()!! && super.offer(e)
                }
            object : ThreadPoolExecutor(
                from,
                to,
                lifeTimeMs,
                TimeUnit.MILLISECONDS,
                queue,
                ComponentThreadFactory(componentName, componentPart),
            ) {
                init {
                    super.setRejectedExecutionHandler { runnable, _ ->
                        executeState.set(false)
                        if (!queue.offer(runnable)) {
                            throw RuntimeException()
                        }
                    }
                }

                override fun setRejectedExecutionHandler(handler: RejectedExecutionHandler): Unit = throw UnsupportedOperationException()

                override fun execute(command: Runnable) {
                    try {
                        executeState.set(true)
                        super.execute(command)
                    } finally {
                        executeState.set(false)
                    }
                }
            }
        } else {
            ThreadPoolExecutor(
                from,
                to,
                lifeTimeMs,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(),
                ComponentThreadFactory(componentName, componentPart),
            )
        }

    private class PriorityThread(
        private val runnable: Runnable,
        private val priority: Int,
    ) : Runnable {
        override fun run() {
            Process.setThreadPriority(priority)
            runnable.run()
        }
    }

    private class ComponentThreadFactory(
        private val name: String?,
        private val part: String?,
    ) : ThreadFactory {
        private val number = if (part != null) null else AtomicInteger()

        override fun newThread(r: Runnable): Thread {
            val thread = Thread(PriorityThread(r, Process.THREAD_PRIORITY_BACKGROUND))
            if (name != null) {
                thread.name = "$name #${part ?: number!!.incrementAndGet()}"
            }
            return thread
        }
    }

    @JvmStatic
    fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    @JvmStatic
    fun <T> mainGet(callable: Callable<T>?): T? {
        if (callable == null) {
            return null
        }
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Any>(2)
        val runnable =
            Runnable {
                try {
                    result[0] = callable.call()
                } catch (t: Throwable) {
                    result[1] = t
                }
                latch.countDown()
            }
        if (isMain()) {
            runnable.run()
        } else {
            HANDLER.post(runnable)
            var interrupted = false
            while (true) {
                try {
                    latch.await()
                    if (interrupted) {
                        Thread.currentThread().interrupt()
                    }
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
        }
        val error = result[1]
        if (error != null) {
            when (error) {
                is RuntimeException -> throw error
                is Error -> throw error
                else -> throw RuntimeException(error as Throwable)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T?
    }
}
