package com.mishiranu.dashchan.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import chan.util.StringUtils
import com.mishiranu.dashchan.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.UUID

object Logger {
	private val DIVIDER = "-".repeat(40)
	private val LOGGER_MESSAGE = UUID.randomUUID().toString()
	private const val MAX_FILES_COUNT = 20

	enum class Type { DEBUG, ERROR }

	@JvmStatic
	fun write(type: Type, tag: String, vararg data: Any?) {
		val builder = StringBuilder()
		if (data.isEmpty()) {
			builder.append("no arguments")
		} else {
			for (item in data) {
				if (builder.isNotEmpty()) {
					builder.append(' ')
				}
				when (item) {
					null -> builder.append("null")
					is CharSequence -> builder.append(item.toString()
							.replace("\n", "[LF]").replace("\r", "[CR]"))
					is Boolean -> builder.append(if (item) "true" else "false")
					is Char -> when (item) {
						'\n' -> builder.append("'[LF]'")
						'\r' -> builder.append("'[CR]'")
						else -> builder.append("'").append(item).append("'")
					}
					is Array<*> -> {
						var simpleName = item.javaClass.simpleName
						if (simpleName.endsWith("[]")) {
							simpleName = simpleName.substring(0, simpleName.length - 2)
						}
						builder.append(simpleName).append('[').append(item.size).append(']')
					}
					is Throwable -> {
						var t: Throwable? = item
						var j = 0
						while (t != null) {
							if (j > 0) {
								builder.append(", caused by ")
							}
							builder.append(t.javaClass.name).append(':').append(j)
							val message = t.message
							if (message != null) {
								builder.append(":\"").append(message).append('"')
							}
							t = t.cause
							j++
						}
					}
					is Bitmap -> {
						builder.append("Bitmap:")
						val recycled = item.isRecycled
						builder.append(if (recycled) "recycled" else "alive")
						if (!recycled) {
							builder.append(':').append(item.width).append('x').append(item.height)
						}
					}
					is File -> builder.append("File:\"").append(item.absolutePath)
							.append("\":").append(if (item.exists()) "exists" else "notexists")
							.append(':').append(item.length())
					else -> builder.append(item.toString())
				}
			}
		}
		val max = 1024
		var i = 0
		while (i < builder.length) {
			val part = builder.substring(i, minOf(builder.length, i + max))
			when (type) {
				Type.DEBUG -> Log.d(tag, part)
				Type.ERROR -> Log.e(tag, part)
			}
			i += max
		}
	}

	private fun interface Writer {
		@Throws(IOException::class)
		fun write(string: String)
	}

	@Throws(IOException::class)
	private fun writeTechnicalData(writer: Writer) {
		writer.write("Device: ")
		writer.write(StringUtils.emptyIfNull(Build.MANUFACTURER))
		writer.write(" ")
		writer.write(StringUtils.emptyIfNull(Build.DEVICE))
		writer.write(" (")
		writer.write(StringUtils.emptyIfNull(Build.MODEL))
		writer.write(")\n")
		writer.write("API: ")
		writer.write(Build.VERSION.SDK_INT.toString())
		writer.write(" (Android ")
		writer.write(StringUtils.emptyIfNull(Build.VERSION.RELEASE))
		writer.write(")\n")
		writer.write("Application: ")
		writer.write(BuildConfig.VERSION_CODE.toString())
		writer.write(" (")
		writer.write(BuildConfig.VERSION_NAME)
		writer.write(")\n")
	}

	@JvmStatic
	fun init(context: Context) {
		val cacheDirectory = context.externalCacheDir
		if (cacheDirectory != null) {
			val packageDirectory = cacheDirectory.parentFile
			if (packageDirectory != null) {
				initDirectories(packageDirectory)
			}
		}
	}

	private fun initDirectories(packageDirectory: File) {
		val logsDirectory = File(packageDirectory, "logs")
		var logcatThread: LogcatThread? = null
		if (logsDirectory.exists() && logsDirectory.isDirectory) {
			var files = logsDirectory.listFiles()
			if (files != null) {
				var deleted = false
				for (file in files) {
					if (file.length() == 0L) {
						file.delete()
						deleted = true
					}
				}
				if (deleted) {
					files = logsDirectory.listFiles()
				}
				if (files != null && files.size > MAX_FILES_COUNT) {
					files.sortWith(IOUtils.SORT_BY_DATE)
					for (i in 0 until files.size - MAX_FILES_COUNT + 1) {
						files[i].delete()
					}
				}
			}
			val logFile = File(logsDirectory, "log-" + System.currentTimeMillis() + ".txt")
			logcatThread = LogcatThread(logFile)
		}

		val errorsDirectory = File(packageDirectory, "errors")
		if (errorsDirectory.exists() || errorsDirectory.mkdirs()) {
			val finalLogcatThread = logcatThread
			val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
			Thread.setDefaultUncaughtExceptionHandler { thread, e ->
				finalLogcatThread?.stop()
				try {
					val errorFile = File(errorsDirectory, "error-" + System.currentTimeMillis() + ".txt")
					val stream = PrintStream(errorFile)
					writeTechnicalData { stream.print(it) }
					stream.println(DIVIDER)
					e.printStackTrace(stream)
				} catch (t: Throwable) {
					// Ignore any exceptions in default exception handler
				} finally {
					systemHandler?.uncaughtException(thread, e)
				}
			}
		}
	}

	private class LogcatThread(private val file: File) : Runnable {
		private val thread: Thread
		private val pidFilter = " " + android.os.Process.myPid() + " "
		private val startMessage = UUID.randomUUID().toString()
		private val stopMessage = UUID.randomUUID().toString()
		private var started = false

		init {
			Log.d("Logger", LOGGER_MESSAGE + startMessage)
			thread = Thread(this, "Logger")
			thread.start()
		}

		fun stop() {
			Log.d("Logger", LOGGER_MESSAGE + stopMessage)
			var interrupted = false
			while (true) {
				try {
					thread.join()
					break
				} catch (e: InterruptedException) {
					interrupted = true
				}
			}
			if (interrupted) {
				Thread.currentThread().interrupt()
			}
		}

		override fun run() {
			var process: Process? = null
			try {
				process = ProcessBuilder().command("logcat", "-v", "threadtime")
						.redirectErrorStream(true).start()
				IOUtils.close(process.outputStream)
				BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
					FileWriter(file).use { writer ->
						val buffer = CharArray(8 * 1024)
						while (true) {
							if (!handleInput(writer, buffer, reader.read(buffer))) {
								break
							}
						}
					}
				}
			} catch (e: IOException) {
				e.printStackTrace()
			} finally {
				process?.destroy()
			}
		}

		private val builder = StringBuilder()

		@Throws(IOException::class)
		private fun handleInput(writer: FileWriter, input: CharArray, count: Int): Boolean {
			if (count < 0) {
				return false
			}
			val builder = this.builder
			for (i in 0 until count) {
				val c = input[i]
				if (c == '\n') {
					if (builder.indexOf(stopMessage) >= 0) {
						return false
					}
					if (started) {
						if (handleLine(writer)) {
							writer.write("\n")
							writer.flush()
						}
					} else if (builder.indexOf(startMessage) >= 0) {
						started = true
						writeTechnicalData { writer.write(it) }
						writer.write(DIVIDER)
						writer.write("\n")
						writer.flush()
					}
					builder.setLength(0)
				} else {
					builder.append(c)
				}
			}
			return true
		}

		private val buffer = CharArray(8 * 1024)

		@Throws(IOException::class)
		private fun handleLine(writer: FileWriter): Boolean {
			val builder = this.builder
			if (builder.indexOf(LOGGER_MESSAGE) >= 0) {
				return false
			}
			val pidIndex = builder.indexOf(pidFilter)
			if (pidIndex < 0) {
				return false
			}
			val colonIndex = builder.indexOf(": ")
			if (colonIndex < pidIndex) {
				return false
			}
			val buffer = this.buffer
			val length = builder.length
			var i = 0
			while (i < length) {
				val end = minOf(i + buffer.size, length)
				builder.getChars(i, end, buffer, 0)
				writer.write(buffer, 0, end - i)
				i += buffer.size
			}
			return length > 0
		}
	}
}
