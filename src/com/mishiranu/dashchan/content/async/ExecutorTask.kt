package com.mishiranu.dashchan.content.async

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

abstract class ExecutorTask<Progress, Result> {
    private enum class Message {
        PROGRESS, RESULT
    }

    private class ProgressHolder<Progress>(
        val task: ExecutorTask<Progress, *>,
        val progress: Progress?
    ) {
        fun handle() {
            if (!task.isCancelled()) {
                task.onProgress(progress!!)
            }
        }
    }

    private class ResultHolder<Result>(val task: ExecutorTask<*, Result>, val result: Result?) {
        fun handle() {
            if (task.isCancelled()) {
                task.onCancel(result)
            } else {
                task.onComplete(result!!)
            }
        }
    }

    private val executed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    private class Worker<Result>(private val task: ExecutorTask<*, Result>) : Callable<Result?> {
        @Throws(Exception::class)
        override fun call(): Result? {
            task.started.set(true)
            var result: Result? = null
            var success = false
            try {
                result = task.run()
                success = true
            } catch (e: InterruptedException) {
                if (task.cancelled.get()) {
                    throw e
                } else {
                    throw IllegalStateException(e)
                }
            } finally {
                if (!success) {
                    task.cancelled.set(true)
                }
                task.postResult(result)
            }
            return result
        }
    }

    private val task: FutureTask<Result?> =
        object : FutureTask<Result?>(Worker(this)) {
            override fun done() {
                var result: Result? = null
                try {
                    result = get()
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    if (cause is InterruptedException) {
                        // Ignore
                    } else if (cause is RuntimeException) {
                        throw cause
                    } else if (cause is Error) {
                        throw cause
                    } else {
                        throw RuntimeException(cause)
                    }
                } catch (e: InterruptedException) {
                    // Ignore
                } catch (e: CancellationException) {
                }
                if (!started.get()) {
                    postResult(result)
                }
            }
        }

    private fun postResult(result: Result?) {
        HANDLER.obtainMessage(Message.RESULT.ordinal, ResultHolder(this, result))
            .sendToTarget()
    }

    fun execute(executor: Executor) {
        val executed = this.executed.getAndSet(true)
        check(!executed)
        onPrepare()
        executor.execute(task)
    }

    open fun cancel() {
        cancelled.set(true)
        task.cancel(true)
    }

    protected fun notifyProgress(progress: Progress?) {
        if (!isCancelled()) {
            HANDLER.obtainMessage(
                Message.PROGRESS.ordinal,
                ProgressHolder(this, progress)
            ).sendToTarget()
        }
    }

    protected fun isCancelled(): Boolean {
        return cancelled.get()
    }

    protected open fun onPrepare() {}

    @Throws(InterruptedException::class)
    protected abstract fun run(): Result?
    protected open fun onProgress(progress: Progress) {}
    protected open fun onCancel(result: Result?) {}
    protected open fun onComplete(result: Result) {}

    companion object {
        private val HANDLER =
            Handler(Looper.getMainLooper(), Handler.Callback { msg: android.os.Message? ->
                when (Message.entries[msg!!.what]) {
                    Message.PROGRESS -> {
                        val progressHolder: ProgressHolder<*> = msg.obj as ProgressHolder<*>
                        progressHolder.handle()
                        return@Callback true
                    }

                    Message.RESULT -> {
                        val resultHolder: ResultHolder<*> = msg.obj as ResultHolder<*>
                        resultHolder.handle()
                        return@Callback true
                    }

                    else -> {
                        return@Callback false
                    }
                }
            })
    }
}
