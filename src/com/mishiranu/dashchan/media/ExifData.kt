package com.mishiranu.dashchan.media

import android.util.Pair
import chan.util.StringUtils
import com.mishiranu.dashchan.util.IOUtils
import java.util.Collections
import java.util.Locale
import kotlin.math.pow

class ExifData private constructor(private val exif: Map<String, String?>?) {
	@Volatile
	private var userMetadata: List<Pair<String, String>?>? = null

	fun getUserMetadata(): List<Pair<String, String>?> {
		var userMetadata = this.userMetadata
		if (userMetadata == null) {
			synchronized(this) {
				userMetadata = this.userMetadata
				if (userMetadata == null) {
					userMetadata = if (exif != null) {
						val list = ArrayList<Pair<String, String>?>()
						var addDivider = false
						addDivider = add(list, exif, KEY_DESCRIPTION, "Description") or addDivider
						addDivider = add(list, exif, KEY_MAKE, "Manufacturer") or addDivider
						addDivider = add(list, exif, KEY_MODEL, "Model") or addDivider
						addDivider = add(list, exif, KEY_SOFTWARE, "Software") or addDivider
						addDivider = add(list, exif, KEY_DATE_TIME, "Date") or addDivider
						val rotation = this.rotation
						if (rotation != 0) {
							list.add(Pair("Rotation", "$rotation°"))
							addDivider = true
						}
						if (addDivider) {
							list.add(null)
							addDivider = false
						}
						addDivider = add(list, exif, KEY_FOCAL_LENGTH, "Focal length") or addDivider
						addDivider = add(list, exif, KEY_SHUTTER_SPEED, "Shutter speed") or addDivider
						addDivider = add(list, exif, KEY_APERTURE, "Aperture") or addDivider
						addDivider = add(list, exif, KEY_ISO_SPEED, "ISO speed") or addDivider
						addDivider = add(list, exif, KEY_BRIGHTNESS, "Brightness") or addDivider
						if (addDivider) {
							list.add(null)
						}
						val geolocation = getGeolocation(true)
						if (geolocation != null) {
							list.add(Pair("Location", geolocation))
						}
						Collections.unmodifiableList(list)
					} else {
						emptyList()
					}
					this.userMetadata = userMetadata
				}
			}
		}
		return userMetadata!!
	}

	val rotation: Int
		get() {
			if (exif != null) {
				when (exif[KEY_ORIENTATION]) {
					"8" -> return 90
					"3" -> return 180
					"6" -> return 270
				}
			}
			return 0
		}

	private fun formatLocationValue(value: Double): String {
		var rest = value
		val degrees = rest.toInt()
		rest -= degrees
		rest *= 60
		val minutes = rest.toInt()
		rest -= minutes
		rest *= 60
		val seconds = rest.toInt()
		return "$degrees°$minutes'$seconds\""
	}

	fun getGeolocation(userReadable: Boolean): String? {
		if (exif != null) {
			val latitude = exif[KEY_LATITUDE]
			val longitude = exif[KEY_LONGITUDE]
			if (latitude != null && longitude != null) {
				var latitudeRef = exif[KEY_LATITUDE_REF]
				var longitudeRef = exif[KEY_LONGITUDE_REF]
				if (StringUtils.isEmptyOrWhitespace(latitudeRef)) {
					latitudeRef = "N"
				}
				if (StringUtils.isEmptyOrWhitespace(longitudeRef)) {
					longitudeRef = "E"
				}
				return if (userReadable) {
					val latitudeValue = latitude.toDouble()
					val longitudeValue = longitude.toDouble()
					formatLocationValue(latitudeValue) + latitudeRef + " " +
							formatLocationValue(longitudeValue) + longitudeRef
				} else {
					(if ("S" == latitudeRef) "-" else "") + latitude + "," +
							(if ("W" == longitudeRef) "-" else "") + longitude
				}
			}
		}
		return null
	}

	fun mergeTo(exifData: ExifData?): ExifData {
		return if (exifData?.exif == null || exifData.exif.isEmpty()) {
			this
		} else if (exif == null || exif.isEmpty()) {
			exifData
		} else {
			val exif = LinkedHashMap(exifData.exif)
			exif.putAll(this.exif)
			ExifData(Collections.unmodifiableMap(exif))
		}
	}

	private enum class Ifd { GENERAL, GPS }

	companion object {
		private const val EXIF_TAG_DESCRIPTION = 0x010e
		private const val EXIF_TAG_MAKE = 0x010f
		private const val EXIF_TAG_MODEL = 0x0110
		private const val EXIF_TAG_ORIENTATION = 0x0112
		private const val EXIF_TAG_SOFTWARE = 0x0131
		private const val EXIF_TAG_DATE_TIME = 0x0132
		private const val EXIF_TAG_EXPOSURE_TIME = 0x829a
		private const val EXIF_TAG_F_NUMBER = 0x829d
		private const val EXIF_TAG_EXIF_IFD = 0x8769
		private const val EXIF_TAG_GPS_IFD = 0x8825
		private const val EXIF_TAG_ISO_SPEED = 0x8827
		private const val EXIF_TAG_SHUTTER_SPEED = 0x9201
		private const val EXIF_TAG_APERTURE = 0x9202
		private const val EXIF_TAG_BRIGHTNESS = 0x9203
		private const val EXIF_TAG_FOCAL_LENGTH = 0x920a

		private const val KEY_EXIF_OFFSET = "exifOffset"
		private const val KEY_GPS_OFFSET = "gpsOffset"

		private const val KEY_DESCRIPTION = "description"
		private const val KEY_MAKE = "make"
		private const val KEY_MODEL = "model"
		private const val KEY_ORIENTATION = "orientation"
		private const val KEY_SOFTWARE = "software"
		private const val KEY_DATE_TIME = "dateTime"
		private const val KEY_FOCAL_LENGTH = "focalLength"
		private const val KEY_SHUTTER_SPEED = "shutterSpeed"
		private const val KEY_APERTURE = "aperture"
		private const val KEY_ISO_SPEED = "isoSpeed"
		private const val KEY_BRIGHTNESS = "brightness"

		private const val KEY_LATITUDE = "latitude"
		private const val KEY_LATITUDE_REF = "latitudeRef"
		private const val KEY_LONGITUDE = "longitude"
		private const val KEY_LONGITUDE_REF = "longitudeRef"

		@JvmField
		val EMPTY = ExifData(null).apply { userMetadata = emptyList() }

		private fun add(userMetadata: MutableList<Pair<String, String>?>,
				exif: Map<String, String?>, key: String, title: String): Boolean {
			val value = exif[key]
			if (!value.isNullOrEmpty()) {
				userMetadata.add(Pair(title, value))
				return true
			}
			return false
		}

		private fun extractIfdString(exifBytes: ByteArray, offset: Int, format: Int): String? {
			if (format == 2 && offset >= 0 && exifBytes.size > offset &&
					exifBytes[offset].toInt() != 0x00) {
				for (i in offset until exifBytes.size) {
					if (exifBytes[i].toInt() == 0x00) {
						return String(exifBytes, offset, i - offset)
					}
				}
			}
			return null
		}

		private fun convertIfdRational(exifBytes: ByteArray, offset: Int, format: Int,
				littleEndian: Boolean): Double {
			if ((format == 5 || format == 10) && offset >= 0 && exifBytes.size >= offset + 8) {
				val numerator = IOUtils.bytesToInt(littleEndian, offset, 4, *exifBytes)
				val denominator = IOUtils.bytesToInt(littleEndian, offset + 4, 4, *exifBytes)
				return numerator.toDouble() / denominator
			}
			return Double.NaN
		}

		private fun convertIfdGpsString(exifBytes: ByteArray, offset: Int, format: Int,
				littleEndian: Boolean, count: Int): String? {
			if ((format == 5 || format == 10) && offset >= 0 && exifBytes.size >= offset + 8) {
				var value = 0.0
				val denominators = intArrayOf(1, 60, 3600)
				val realCount = count.coerceIn(1, 3)
				for (i in 0 until realCount) {
					val itValue = convertIfdRational(exifBytes, offset + 8 * i, format, littleEndian)
					if (itValue.isNaN()) {
						break
					}
					value += itValue / denominators[i]
				}
				return String.format(Locale.US, "%.7f", value)
			}
			return null
		}

		private fun formatDoubleSimple(value: Double): String {
			return StringUtils.stripTrailingZeros(String.format(Locale.US, "%.1f", value))
		}

		private fun extractIfd(exif: MutableMap<String, String?>, ifd: Ifd,
				exifBytes: ByteArray, offset: Int, littleEndian: Boolean): Boolean {
			if (offset >= 0 && exifBytes.size >= offset + 2) {
				val entries = IOUtils.bytesToInt(littleEndian, offset, 2, *exifBytes)
				if (exifBytes.size >= offset + 2 + 12 * entries) {
					var position = offset + 2
					for (i in 0 until entries) {
						val type = IOUtils.bytesToInt(littleEndian, position, 2, *exifBytes)
						val format = IOUtils.bytesToInt(littleEndian, position + 2, 2, *exifBytes)
						val count = IOUtils.bytesToInt(littleEndian, position + 4, 4, *exifBytes)
						val value = IOUtils.bytesToInt(littleEndian, position + 8, 4, *exifBytes)
						val valueShort = IOUtils.bytesToInt(littleEndian, position + 8, 2, *exifBytes)
						if (ifd == Ifd.GENERAL) {
							when (type) {
								EXIF_TAG_DESCRIPTION ->
									exif[KEY_DESCRIPTION] = extractIfdString(exifBytes, value, format)
								EXIF_TAG_MAKE ->
									exif[KEY_MAKE] = extractIfdString(exifBytes, value, format)
								EXIF_TAG_MODEL ->
									exif[KEY_MODEL] = extractIfdString(exifBytes, value, format)
								EXIF_TAG_ORIENTATION -> exif[KEY_ORIENTATION] = valueShort.toString()
								EXIF_TAG_SOFTWARE ->
									exif[KEY_SOFTWARE] = extractIfdString(exifBytes, value, format)
								EXIF_TAG_DATE_TIME ->
									exif[KEY_DATE_TIME] = extractIfdString(exifBytes, value, format)
								EXIF_TAG_EXPOSURE_TIME, EXIF_TAG_SHUTTER_SPEED -> {
									if (!exif.containsValue(KEY_SHUTTER_SPEED)) {
										var valueDouble = convertIfdRational(exifBytes, value,
												format, littleEndian)
										if (type == EXIF_TAG_SHUTTER_SPEED) {
											valueDouble = 2.0.pow(-valueDouble)
										}
										if (valueDouble > 0) {
											val exposureTime = if (valueDouble <= 0.5) {
												"1/" + (1 / valueDouble + 0.5).toInt()
											} else {
												formatDoubleSimple(valueDouble)
											}
											exif[KEY_SHUTTER_SPEED] = "$exposureTime sec."
										}
									}
								}
								EXIF_TAG_F_NUMBER, EXIF_TAG_APERTURE -> {
									if (!exif.containsKey(KEY_APERTURE)) {
										var valueDouble = convertIfdRational(exifBytes, value,
												format, littleEndian)
										if (type == EXIF_TAG_APERTURE) {
											valueDouble = 2.0.pow(valueDouble / 2)
										}
										if (valueDouble > 0) {
											exif[KEY_APERTURE] = "f/" + formatDoubleSimple(valueDouble)
										}
									}
								}
								EXIF_TAG_EXIF_IFD -> exif[KEY_EXIF_OFFSET] = value.toString()
								EXIF_TAG_GPS_IFD -> exif[KEY_GPS_OFFSET] = value.toString()
								EXIF_TAG_ISO_SPEED -> exif[KEY_ISO_SPEED] = valueShort.toString()
								EXIF_TAG_BRIGHTNESS -> {
									val valueDouble = convertIfdRational(exifBytes, value,
											format, littleEndian)
									exif[KEY_BRIGHTNESS] = formatDoubleSimple(valueDouble) + " EV"
								}
								EXIF_TAG_FOCAL_LENGTH -> {
									val valueDouble = convertIfdRational(exifBytes, value,
											format, littleEndian)
									exif[KEY_FOCAL_LENGTH] = formatDoubleSimple(valueDouble) + " mm"
								}
							}
						} else if (ifd == Ifd.GPS) {
							when (type) {
								0x0001 -> exif[KEY_LATITUDE_REF] = value.toChar().toString()
								0x0002 -> exif[KEY_LATITUDE] = convertIfdGpsString(exifBytes, value,
										format, littleEndian, count)
								0x0003 -> exif[KEY_LONGITUDE_REF] = value.toChar().toString()
								0x0004 -> exif[KEY_LONGITUDE] = convertIfdGpsString(exifBytes, value,
										format, littleEndian, count)
							}
						}
						position += 12
					}
					return true
				}
				return false
			}
			return false
		}

		private fun extractIfd(exif: MutableMap<String, String?>, ifd: Ifd, exifBytes: ByteArray,
				baseOffset: Int, offsetStringKey: String, littleEndian: Boolean): Boolean {
			val offsetString = exif[offsetStringKey]
			if (offsetString != null) {
				val offset = try {
					offsetString.toInt()
				} catch (e: NumberFormatException) {
					-1
				}
				if (offset >= 0) {
					return extractIfd(exif, ifd, exifBytes, baseOffset + offset, littleEndian)
				}
			}
			return false
		}

		@JvmStatic
		fun extract(exifBytes: ByteArray, offset: Int): ExifData? {
			if (exifBytes.size >= offset + 8) {
				val tiffHeader = IOUtils.bytesToInt(false, offset, 4, *exifBytes)
				val littleEndian: Boolean? = when (tiffHeader) {
					0x49492a00 -> true
					0x4d4d002a -> false
					else -> null
				}
				if (littleEndian != null) {
					val exif = LinkedHashMap<String, String?>()
					val ifdOffset = IOUtils.bytesToInt(littleEndian, offset + 4, 4, *exifBytes)
					extractIfd(exif, Ifd.GENERAL, exifBytes, offset + ifdOffset, littleEndian)
					extractIfd(exif, Ifd.GENERAL, exifBytes, offset, KEY_EXIF_OFFSET, littleEndian)
					extractIfd(exif, Ifd.GPS, exifBytes, offset, KEY_GPS_OFFSET, littleEndian)
					return ExifData(Collections.unmodifiableMap(exif))
				}
			}
			return null
		}
	}
}
