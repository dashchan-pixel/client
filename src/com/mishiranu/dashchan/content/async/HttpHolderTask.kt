package com.mishiranu.dashchan.content.async

import chan.content.Chan
import chan.http.HttpHolder

abstract class HttpHolderTask<Progress, Result>(chan: Chan) : ExecutorTask<Progress, Result>() {
	private val holder = HttpHolder(chan)

	final override fun run(): Result {
		return holder.use().use { run(holder) }
	}

	protected abstract fun run(holder: HttpHolder): Result

	override fun cancel() {
		super.cancel()
		holder.interrupt()
	}
}
