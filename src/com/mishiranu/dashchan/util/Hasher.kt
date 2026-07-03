package com.mishiranu.dashchan.util

import chan.util.StringUtils
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class Hasher private constructor(algorithm: String) {
	private val digest: MessageDigest = try {
		MessageDigest.getInstance(algorithm)
	} catch (e: NoSuchAlgorithmException) {
		throw RuntimeException(e)
	}
	private val buffer = ByteArray(8192)

	@Throws(IOException::class)
	fun calculate(inputStream: InputStream): ByteArray {
		digest.reset()
		while (true) {
			val count = inputStream.read(buffer, 0, buffer.size)
			if (count < 0) {
				break
			}
			digest.update(buffer, 0, count)
		}
		return digest.digest()
	}

	fun calculate(bytes: ByteArray): ByteArray {
		digest.reset()
		return digest.digest(bytes)
	}

	fun calculate(string: String?): ByteArray = calculate(StringUtils.emptyIfNull(string).toByteArray())

	companion object {
		private val HASHER_SHA_256 = ThreadLocal.withInitial { Hasher("SHA-256") }

		@JvmStatic
		fun getInstanceSha256(): Hasher = HASHER_SHA_256.get()!!
	}
}
