package com.mishiranu.dashchan.content.async

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import java.lang.reflect.ParameterizedType

open class TaskViewModel<Task : ExecutorTask<*, *>, Result> : ViewModel() {
	private var task: Task? = null
	private val result = MutableLiveData<Result?>()

	fun hasTaskOrValue(): Boolean {
		return task != null || result.value != null
	}

	fun getTask(): Task? = task

	fun attach(task: Task) {
		result.value = null
		this.task?.cancel()
		this.task = task
	}

	fun observe(owner: LifecycleOwner, observer: Observer<in Result>) {
		result.observe(owner) { result ->
			if (result != null) {
				this.result.value = null
				observer.onChanged(result)
			}
		}
	}

	override fun onCleared() {
		task?.cancel()
		task = null
	}

	fun handleResult(result: Result) {
		task = null
		this.result.value = result
	}

	open class Proxy<Task : ExecutorTask<*, *>, Callback> :
			TaskViewModel<Task, CallbackProxy<Callback>>() {
		@JvmField val callback: Callback

		init {
			val type = javaClass.genericSuperclass as ParameterizedType
			@Suppress("UNCHECKED_CAST")
			val callbackClass = type.actualTypeArguments[1] as Class<Callback>
			callback = CallbackProxy.create(callbackClass, this::handleResult)
		}

		fun observe(owner: LifecycleOwner, callback: Callback) {
			observe(owner) { result -> result.invoke(callback) }
		}
	}
}
