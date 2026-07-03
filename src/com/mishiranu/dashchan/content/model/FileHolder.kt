package com.mishiranu.dashchan.content.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import chan.util.DataFile
import chan.util.StringUtils
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.media.JpegData
import com.mishiranu.dashchan.media.PngData
import com.mishiranu.dashchan.media.WebViewDecoder
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.MimeTypes
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

abstract class FileHolder {
	abstract val name: String
	abstract val size: Int
	@Throws(IOException::class)
	abstract fun openInputStream(): InputStream
	@Throws(IOException::class)
	abstract fun openFileDescriptor(): ParcelFileDescriptor

	val extension: String?
		get() = StringUtils.getFileExtension(name)

	enum class ImageType { NOT_IMAGE, IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF, IMAGE_WEBP, IMAGE_BMP, IMAGE_SVG }

	private class ImageData(val type: ImageType, val jpegData: JpegData?, val pngData: PngData?,
			val width: Int, val height: Int) {
		val rotation: Int
			get() = jpegData?.exifData?.rotation ?: 0

		val gammaCorrectionForSkia: Float?
			get() = if (GraphicsUtils.SKIA_SUPPORTS_GAMMA_CORRECTION) null else pngData?.gammaCorrection

		val isRegionDecoderSupported: Boolean
			get() = jpegData == null || !jpegData.forbidRegionDecoder
	}

	@Volatile
	@Transient
	private var imageData: ImageData? = null

	private fun startsWithSignature(where: ByteArray?, what: IntArray): Boolean {
		if (where == null || what.size > where.size) {
			return false
		}
		for (i in what.indices) {
			if (what[i] >= 0 && what[i] != where[i].toInt() and 0xff) {
				return false
			}
		}
		return true
	}

	private fun obtainImageData(): ImageData {
		var type = ImageType.NOT_IMAGE
		var jpegData: JpegData? = null
		var pngData: PngData? = null
		var width = -1
		var height = -1
		val options = BitmapFactory.Options()
		options.inJustDecodeBounds = true
		readBitmapSimple(options)
		if (options.outWidth > 0 && options.outHeight > 0) {
			var signature: ByteArray? = null
			var success = false
			try {
				openInputStream().use { input ->
					signature = ByteArray(12)
					success = IOUtils.readExactlyCheck(input, signature, 0, signature!!.size)
				}
			} catch (e: IOException) {
				// Ignore
			}
			if (success) {
				type = when {
					startsWithSignature(signature, SIGNATURE_PNG) -> ImageType.IMAGE_PNG
					startsWithSignature(signature, SIGNATURE_JPEG) -> ImageType.IMAGE_JPEG
					startsWithSignature(signature, SIGNATURE_GIF) -> ImageType.IMAGE_GIF
					startsWithSignature(signature, SIGNATURE_WEBP) -> ImageType.IMAGE_WEBP
					startsWithSignature(signature, SIGNATURE_BMP) -> ImageType.IMAGE_BMP
					else -> ImageType.NOT_IMAGE
				}
				if (type != ImageType.NOT_IMAGE) {
					var swapDimensions = false
					if (type == ImageType.IMAGE_JPEG) {
						try {
							openInputStream().use { input -> jpegData = JpegData.extract(input) }
						} catch (e: IOException) {
							// Ignore
						}
						val exifData = jpegData?.exifData
						if (exifData != null) {
							swapDimensions = exifData.rotation % 180 != 0
						}
					} else if (type == ImageType.IMAGE_PNG) {
						try {
							openInputStream().use { input -> pngData = PngData.extract(input) }
						} catch (e: IOException) {
							// Ignore
						}
					}
					if (swapDimensions) {
						width = options.outHeight
						height = options.outWidth
					} else {
						width = options.outWidth
						height = options.outHeight
					}
				}
			}
		} else {
			try {
				openInputStream().use { input ->
					val parser = PARSER_FACTORY.newPullParser()
					parser.setInput(input, null)
					while (true) {
						val token = parser.next()
						if (token == XmlPullParser.END_DOCUMENT) {
							break
						}
						if (token == XmlPullParser.START_TAG && "svg" == parser.name) {
							val widthString = parser.getAttributeValue(null, "width")
							val heightString = parser.getAttributeValue(null, "height")
							if (widthString != null && heightString != null) {
								try {
									width = widthString.toInt()
									height = heightString.toInt()
								} catch (e: NumberFormatException) {
									width = -1
									height = -1
								}
							}
							type = ImageType.IMAGE_SVG
							break
						}
					}
				}
			} catch (e: IOException) {
				// Ignore
			} catch (e: XmlPullParserException) {
				// Ignore
			}
		}
		return ImageData(type, jpegData, pngData, width, height)
	}

	private fun getImageData(): ImageData {
		var imageData = this.imageData
		if (imageData == null) {
			synchronized(this) {
				imageData = this.imageData
				if (imageData == null) {
					imageData = obtainImageData()
					this.imageData = imageData
				}
			}
		}
		return imageData!!
	}

	val imageType: ImageType
		get() = getImageData().type

	val isImage: Boolean
		get() = getImageData().type != ImageType.NOT_IMAGE

	val jpegData: JpegData?
		get() = getImageData().jpegData

	val pngData: PngData?
		get() = getImageData().pngData

	val imageRotation: Int
		get() = getImageData().rotation

	val imageGammaCorrectionForSkia: Float?
		get() = getImageData().gammaCorrectionForSkia

	val isImageRegionDecoderSupported: Boolean
		get() = getImageData().isRegionDecoderSupported

	val imageWidth: Int
		get() = getImageData().width

	val imageHeight: Int
		get() = getImageData().height

	fun readImageBitmap(maxSize: Int, mayUseRegionDecoder: Boolean,
			mayUseWebViewDecoder: Boolean): Bitmap? {
		val imageData = getImageData()
		if (imageData.type != ImageType.NOT_IMAGE) {
			val options = BitmapFactory.Options()
			options.inSampleSize = calculateInSampleSize(maxSize, imageData.width, imageData.height)
			var bitmap = readBitmapInternal(options, mayUseRegionDecoder, mayUseWebViewDecoder)
			if (bitmap != null) {
				val rotation = imageData.rotation
				bitmap = GraphicsUtils.applyRotation(bitmap, rotation)
				val gammaCorrection = imageData.gammaCorrectionForSkia
				if (gammaCorrection != null) {
					bitmap = GraphicsUtils.applyGammaCorrection(bitmap, gammaCorrection)
				}
			}
			return bitmap
		}
		return null
	}

	private fun readBitmapInternal(options: BitmapFactory.Options, mayUseRegionDecoder: Boolean,
			mayUseWebViewDecoder: Boolean): Bitmap? {
		val imageData = getImageData()
		if (imageData.type == ImageType.NOT_IMAGE) {
			return null
		}
		if (imageData.type != ImageType.IMAGE_SVG) {
			val bitmap = readBitmapSimple(options)
			if (bitmap != null) {
				return bitmap
			}
			if (mayUseRegionDecoder && imageData.isRegionDecoderSupported) {
				var decoder: BitmapRegionDecoder? = null
				try {
					openInputStream().use { input ->
						@Suppress("DEPRECATION")
						decoder = BitmapRegionDecoder.newInstance(input, false)
						return decoder!!.decodeRegion(Rect(0, 0, decoder!!.width, decoder!!.height), options)
					}
				} catch (e: IOException) {
					e.printStackTrace()
				} finally {
					decoder?.recycle()
				}
			}
		}
		if (mayUseWebViewDecoder) {
			return WebViewDecoder.loadBitmap(this, options)
		}
		return null
	}

	private fun readBitmapSimple(options: BitmapFactory.Options): Bitmap? {
		return try {
			openInputStream().use { input -> BitmapFactory.decodeStream(input, null, options) }
		} catch (e: IOException) {
			e.printStackTrace()
			null
		}
	}

	private class FileFileHolder(val file: File) : FileHolder() {
		override val name: String
			get() = file.name

		override val size: Int
			get() = file.length().toInt()

		@Throws(IOException::class)
		override fun openInputStream(): FileInputStream = FileInputStream(file)

		@Throws(IOException::class)
		override fun openFileDescriptor(): ParcelFileDescriptor {
			return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
					?: throw FileNotFoundException("File descriptor is null")
		}

		override fun equals(other: Any?): Boolean {
			if (other === this) {
				return true
			}
			return other is FileFileHolder && other.file == file
		}

		override fun hashCode(): Int = file.hashCode()
	}

	private class ContentFileHolder(uri: Uri, override val name: String, size: Int) : FileHolder() {
		private val uriString: String = uri.toString()
		override val size: Int

		init {
			var realSize = size
			try {
				openInputStream().use { input ->
					var newSize = 0
					val buffer = ByteArray(8192)
					while (true) {
						val count = input.read(buffer)
						if (count < 0) {
							break
						}
						newSize += count
					}
					realSize = newSize
				}
			} catch (e: IOException) {
				// Ignore
			}
			this.size = realSize
		}

		@Throws(IOException::class)
		override fun openInputStream(): InputStream {
			try {
				return MainApplication.getInstance().contentResolver.openInputStream(toUri())
						?: throw IOException("InputStream is empty")
			} catch (e: SecurityException) {
				throw IOException(e)
			} catch (e: IOException) {
				throw e
			} catch (e: Exception) {
				e.printStackTrace()
				throw IOException(e)
			}
		}

		@Throws(IOException::class)
		override fun openFileDescriptor(): ParcelFileDescriptor {
			try {
				return MainApplication.getInstance().contentResolver.openFileDescriptor(toUri(), "r")
						?: throw FileNotFoundException("File descriptor is null")
			} catch (e: SecurityException) {
				throw IOException(e)
			}
		}

		private fun toUri(): Uri = Uri.parse(uriString)

		override fun equals(other: Any?): Boolean {
			if (other === this) {
				return true
			}
			return other is ContentFileHolder && other.uriString == uriString
		}

		override fun hashCode(): Int = uriString.hashCode()
	}

	companion object {
		private val PARSER_FACTORY: XmlPullParserFactory = try {
			XmlPullParserFactory.newInstance()
		} catch (e: XmlPullParserException) {
			throw RuntimeException(e)
		}

		private val SIGNATURE_JPEG = intArrayOf(0xff, 0xd8, 0xff)
		private val SIGNATURE_PNG = intArrayOf(0x89, 'P'.code, 'N'.code, 'G'.code,
				'\r'.code, '\n'.code, 0x1a, '\n'.code)
		private val SIGNATURE_GIF = intArrayOf('G'.code, 'I'.code, 'F'.code, '8'.code, -1, 'a'.code)
		private val SIGNATURE_WEBP = intArrayOf('R'.code, 'I'.code, 'F'.code, 'F'.code,
				-1, -1, -1, -1, 'W'.code, 'E'.code, 'B'.code, 'P'.code)
		private val SIGNATURE_BMP = intArrayOf('B'.code, 'M'.code)

		@JvmStatic
		fun calculateInSampleSize(max: Int, width: Int, height: Int): Int {
			if (width > max || height > max) {
				val size = maxOf(width, height)
				val scale = (size + max - 1) / max
				var inSampleSize = 1
				while (scale > inSampleSize) {
					inSampleSize *= 2
				}
				return inSampleSize
			}
			return 1
		}

		private var fileNameStart = System.currentTimeMillis()

		@JvmStatic
		fun obtain(file: File): FileHolder = FileFileHolder(file)

		@JvmStatic
		fun obtain(uri: Uri): FileHolder? {
			val scheme = uri.scheme
			if ("file" == scheme) {
				val path = uri.path!!
				return FileFileHolder(File(path))
			} else if ("content" == scheme) {
				try {
					MainApplication.getInstance().contentResolver
							.query(uri, null, null, null, null).use { cursor ->
						if (cursor != null && cursor.moveToFirst()) {
							val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
							val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
							if (nameIndex >= 0 && sizeIndex >= 0) {
								var name: String? = cursor.getString(nameIndex)
								val size = cursor.getInt(sizeIndex)
								if (name.isNullOrEmpty()) {
									@Suppress("DEPRECATION")
									val column = MediaStore.MediaColumns.DATA
									val dataIndex = cursor.getColumnIndex(column)
									if (dataIndex >= 0) {
										val data = cursor.getString(dataIndex)
										if (data != null) {
											name = File(data).name
										}
									}
								}
								if (name.isNullOrEmpty()) {
									val mimeTypeIndex =
											cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
									if (mimeTypeIndex >= 0) {
										val mimeType = cursor.getString(mimeTypeIndex)
										if (mimeType != null) {
											val extension = MimeTypes.toExtension(mimeType)
											if (!extension.isNullOrEmpty()) {
												name = "${++fileNameStart}.$extension"
											}
										}
									}
								}
								if (!name.isNullOrEmpty()) {
									return ContentFileHolder(uri, name, size)
								}
							}
						}
					}
				} catch (e: SecurityException) {
					e.printStackTrace()
					return null
				}
			}
			return null
		}

		@JvmStatic
		fun obtain(file: DataFile): FileHolder? {
			val fileOrUri = file.getFileOrUri()
			return when {
				fileOrUri.first != null -> obtain(fileOrUri.first!!)
				fileOrUri.second != null -> obtain(fileOrUri.second!!)
				else -> null
			}
		}
	}
}
