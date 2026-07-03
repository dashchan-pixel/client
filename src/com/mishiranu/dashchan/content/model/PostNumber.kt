package com.mishiranu.dashchan.content.model

import android.os.Parcel
import android.os.Parcelable
import chan.util.StringUtils

class PostNumber(@JvmField val major: Int, @JvmField val minor: Int) :
		Comparable<PostNumber>, Parcelable {
	override fun toString(): String {
		return if (minor != 0) "$major.$minor" else major.toString()
	}

	override fun compareTo(other: PostNumber): Int {
		val result = major.compareTo(other.major)
		return if (result != 0) result else minor.compareTo(other.minor)
	}

	override fun equals(other: Any?): Boolean {
		if (other === this) {
			return true
		}
		return other is PostNumber && major == other.major && minor == other.minor
	}

	override fun hashCode(): Int {
		val prime = 31
		var result = 1
		result = prime * result + major
		result = prime * result + minor
		return result
	}

	override fun describeContents(): Int = 0

	override fun writeToParcel(dest: Parcel, flags: Int) {
		dest.writeInt(major)
		dest.writeInt(minor)
	}

	companion object {
		@JvmStatic
		fun parseNullable(string: String?): PostNumber? {
			if (string == null) {
				return null
			}
			var majorString = string
			var minorString: String? = null
			var index = -1
			for (i in string.indices) {
				val c = string[i]
				if (c < '0' || c > '9') {
					if (c == '.' && index == -1) {
						index = i
					} else {
						return null
					}
				}
			}
			if (index >= 0) {
				minorString = string.substring(index + 1)
				majorString = string.substring(0, index)
			}
			return try {
				val major = majorString.toInt()
				val minor = minorString?.toInt() ?: 0
				if (major > 0 && minor >= 0) PostNumber(major, minor) else null
			} catch (e: NumberFormatException) {
				null
			}
		}

		@JvmStatic
		fun parseOrThrow(postNumber: String?): PostNumber {
			return parseNullable(postNumber) ?: throw IllegalArgumentException(
					"Post number is not valid: $postNumber. Post number must be a positive number " +
							"or a pair of positive numbers separated by dot.")
		}

		@JvmStatic
		fun validateThreadNumber(threadNumber: String?, allowEmpty: Boolean) {
			if (threadNumber.isNullOrEmpty()) {
				if (!allowEmpty) {
					throw IllegalArgumentException("Thread number is not defined")
				}
			} else {
				val escapedThreadNumber = StringUtils.escapeFile(threadNumber, false)
				if (threadNumber.length > 30 || escapedThreadNumber != threadNumber) {
					throw IllegalArgumentException("Thread number is not valid: $threadNumber. " +
							"Thread number is limited to 30 characters and must not contain " +
							"any characters from \":\\/*?|<>\".")
				}
			}
		}

		@JvmField
		val CREATOR = object : Parcelable.Creator<PostNumber> {
			override fun createFromParcel(source: Parcel): PostNumber {
				val major = source.readInt()
				val minor = source.readInt()
				return PostNumber(major, minor)
			}

			override fun newArray(size: Int): Array<PostNumber?> = arrayOfNulls(size)
		}
	}
}
