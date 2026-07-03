package com.mishiranu.dashchan.content.storage

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Pair
import android.util.SparseArray
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import org.json.JSONException
import org.json.JSONObject

class StorageManager private constructor() : Handler.Callback, Runnable {
	private val handler = Handler(Looper.getMainLooper(), this)
	private val queue = LinkedBlockingQueue<Enqueued<*>>()

	private var nextIdentifier = 1

	init {
		Thread(this, "StorageManagerWorker").start()
	}

	override fun run() {
		while (true) {
			val enqueued = try {
				queue.take()
			} catch (e: InterruptedException) {
				return
			}
			performSerialize(enqueued)
		}
	}

	private fun <Data> performSerialize(enqueued: Enqueued<Data>) {
		synchronized(enqueued.storage.lock) {
			val file = getFile(enqueued.storage)
			val backupFile = getBackupFile(enqueued.storage)
			if (file.exists()) {
				if (!backupFile.exists()) {
					if (!file.renameTo(backupFile)) {
						Logger.write(Logger.Type.ERROR, "Can't create backup of", file)
						return
					}
				} else {
					file.delete()
				}
			}
			var success = false
			var output: FileOutputStream? = null
			try {
				output = FileOutputStream(file)
				enqueued.storage.onWrite(enqueued.data, output)
				output.flush()
				output.fd.sync()
				success = true
			} catch (e: IOException) {
				e.printStackTrace()
			} finally {
				success = success and IOUtils.close(output)
				if (success) {
					backupFile.delete()
				} else if (file.exists() && !file.delete()) {
					Logger.write(Logger.Type.ERROR, "Can't delete partially written", file)
				}
			}
		}
	}

	private class Enqueued<Data>(val storage: Storage<Data>) {
		val data: Data = storage.onClone()
	}

	abstract class Storage<Data>(internal val name: String, internal val timeout: Int,
			internal val maxTimeout: Int) {
		internal var identifier = 0
		internal val lock = Any()

		protected fun startRead() {
			try {
				INSTANCE.open(this).use { input -> onRead(input) }
			} catch (e: FileNotFoundException) {
				// Ignore exception
			} catch (e: IOException) {
				e.printStackTrace()
			}
		}

		fun getFilesForBackup(): Pair<File, File> {
			val storage = INSTANCE.getFile(this)
			val restore = INSTANCE.getRestoreFile(this)
			return Pair(storage, restore)
		}

		fun serialize() {
			INSTANCE.serialize(this)
		}

		fun await(async: Boolean) {
			INSTANCE.await(this, async)
		}

		abstract fun onClone(): Data
		@Throws(IOException::class)
		abstract fun onRead(input: InputStream)
		@Throws(IOException::class)
		abstract fun onWrite(data: Data, output: OutputStream)
	}

	abstract class JsonOrgStorage<Data>(name: String, timeout: Int, maxTimeout: Int) :
			Storage<Data>(name, timeout, maxTimeout) {
		@Throws(IOException::class)
		final override fun onRead(input: InputStream) {
			val output = ByteArrayOutputStream()
			IOUtils.copyStream(input, output)
			try {
				onDeserialize(JSONObject(String(output.toByteArray(), Charsets.UTF_8)))
			} catch (e: JSONException) {
				// Ignore exception
			}
		}

		@Throws(IOException::class)
		final override fun onWrite(data: Data, output: OutputStream) {
			val jsonObject = try {
				onSerialize(data)
			} catch (e: JSONException) {
				throw RuntimeException(e)
			}
			if (jsonObject != null) {
				output.write(jsonObject.toString().toByteArray(Charsets.UTF_8))
			}
		}

		@Throws(JSONException::class)
		abstract fun onDeserialize(jsonObject: JSONObject)
		@Throws(JSONException::class)
		abstract fun onSerialize(data: Data): JSONObject?
	}

	private fun getDirectory(): File {
		val file = File(MainApplication.getInstance().filesDir, "storage")
		file.mkdirs()
		return file
	}

	private fun getFile(name: String): File = File(getDirectory(), "$name.json")

	private fun getFile(storage: Storage<*>): File = getFile(storage.name)

	private fun getBackupFile(storage: Storage<*>): File = getFile(storage.name + ".backup")

	private fun getRestoreFile(storage: Storage<*>): File = getFile(storage.name + ".restore")

	@Throws(IOException::class)
	private fun open(storage: Storage<*>): InputStream {
		val file = getFile(storage)
		val backupFile = getBackupFile(storage)
		if (backupFile.exists()) {
			backupFile.renameTo(file)
		}
		val restoreFile = getRestoreFile(storage)
		if (restoreFile.exists()) {
			restoreFile.renameTo(file)
		}
		return FileInputStream(file)
	}

	private val serializeTimes = SparseArray<Long>()

	private fun serialize(storage: Storage<*>) {
		if (storage.identifier == 0) {
			storage.identifier = nextIdentifier++
		}
		val timeObject = serializeTimes.get(storage.identifier)
		val timeout: Long
		if (timeObject == null) {
			serializeTimes.put(storage.identifier, SystemClock.elapsedRealtime())
			timeout = storage.timeout.toLong()
		} else {
			timeout = minOf(storage.timeout.toLong(),
					timeObject + storage.maxTimeout - SystemClock.elapsedRealtime())
		}
		handler.removeMessages(storage.identifier)
		if (timeout <= 0) {
			enqueueSerialize(storage)
			serializeTimes.remove(storage.identifier)
		} else {
			handler.sendMessageDelayed(handler.obtainMessage(storage.identifier, storage), timeout)
		}
	}

	fun await(storage: Storage<*>, async: Boolean) {
		if (handler.hasMessages(storage.identifier)) {
			serializeTimes.remove(storage.identifier)
			handler.removeMessages(storage.identifier)
			if (async) {
				enqueueSerialize(storage)
			} else {
				performSerialize(Enqueued(storage))
			}
		}
	}

	private fun enqueueSerialize(storage: Storage<*>) {
		queue.add(Enqueued(storage))
	}

	override fun handleMessage(msg: Message): Boolean {
		val storage = msg.obj as Storage<*>
		serializeTimes.remove(storage.identifier)
		enqueueSerialize(storage)
		return true
	}

	companion object {
		private val INSTANCE = StorageManager()

		@JvmStatic
		fun getInstance(): StorageManager = INSTANCE
	}
}
