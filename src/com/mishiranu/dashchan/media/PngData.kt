package com.mishiranu.dashchan.media

import com.mishiranu.dashchan.util.IOUtils
import java.io.IOException
import java.io.InputStream

class PngData private constructor(@JvmField val hasMetadata: Boolean,
		private val hasGammaCorrection: Boolean, private val gammaCorrectionValue: Float) {
	val gammaCorrection: Float?
		get() = if (hasGammaCorrection) gammaCorrectionValue else null

	companion object {
		@JvmStatic
		@Throws(IOException::class)
		fun extract(input: InputStream): PngData {
			var hasMetadata = false
			var hasGammaCorrection = false
			var ignoreGammaCorrection = false
			var gammaCorrection = 1f
			if (IOUtils.skipExactlyCheck(input, 8)) {
				val buffer = ByteArray(8)
				out@ while (true) {
					if (!IOUtils.readExactlyCheck(input, buffer, 0, 8)) {
						break
					}
					val size = IOUtils.bytesToInt(false, 0, 4, *buffer)
					val name = String(buffer, 4, 4)
					var handled = false
					when (name) {
						"tEXt", "zTXt", "iTXt", "tIME", "eXIf" -> hasMetadata = true
						"gAMA" -> {
							if (size == 4) {
								if (!IOUtils.readExactlyCheck(input, buffer, 0, 4)) {
									break@out
								}
								val value = IOUtils.bytesToInt(false, 0, 4, *buffer)
								// Same check as in libpng
								if (value in 16..625000000) {
									gammaCorrection = 100000f / 2.2f / value
									hasGammaCorrection =
											(gammaCorrection * 100 + 0.5f).toInt() != 100
								}
								handled = true
							}
						}
						"sRGB", "iCCP" -> ignoreGammaCorrection = true
						"IEND" -> break@out
					}
					if (!IOUtils.skipExactlyCheck(input, (if (handled) 0 else size) + 4)) {
						break
					}
				}
			}
			if (ignoreGammaCorrection) {
				hasGammaCorrection = false
				gammaCorrection = 1f
			}
			return PngData(hasMetadata, hasGammaCorrection, gammaCorrection)
		}
	}
}
