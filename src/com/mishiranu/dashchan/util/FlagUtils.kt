package com.mishiranu.dashchan.util

object FlagUtils {
	@JvmStatic
	fun get(flags: Int, flag: Int): Boolean = flags and flag == flag

	@JvmStatic
	fun set(flags: Int, flag: Int, value: Boolean): Int {
		return if (value) flags or flag else flags and flag.inv()
	}
}
