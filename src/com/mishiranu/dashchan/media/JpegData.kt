package com.mishiranu.dashchan.media

import com.mishiranu.dashchan.util.IOUtils
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

class JpegData private constructor(
    @JvmField val exifData: ExifData?,
    @JvmField val forbidRegionDecoder: Boolean,
) {
    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun extract(fileInput: InputStream): JpegData {
            var exifBytes: ByteArray? = null
            var sofBytes: ByteArray? = null
            val input: InputStream = BufferedInputStream(fileInput, 327680)
            val buffer = ByteArray(2)
            while (true) {
                var oneByte = input.read()
                if (oneByte == 0xff) {
                    oneByte = input.read()
                    if (oneByte and 0xe0 == 0xe0) {
                        // Application data (0xe0 for JFIF, 0xe1 for EXIF) or comment (0xfe)
                        if (!IOUtils.readExactlyCheck(input, buffer, 0, 2)) {
                            break
                        }
                        val size = IOUtils.bytesToInt(false, 0, 2, *buffer)
                        if (oneByte == 0xe1 && size > 14) {
                            val data = ByteArray(size - 8)
                            if (!IOUtils.readExactlyCheck(input, data, 0, 6)) {
                                break
                            }
                            val isExif = String(data).startsWith("Exif")
                            if (!IOUtils.readExactlyCheck(input, data, 0, data.size)) {
                                break
                            }
                            if (isExif) {
                                exifBytes = data
                            }
                        } else {
                            if (!IOUtils.skipExactlyCheck(input, size - 2)) {
                                break
                            }
                        }
                    } else if (oneByte == 0xc0 || oneByte == 0xc1 || oneByte == 0xc2) {
                        if (!IOUtils.readExactlyCheck(input, buffer, 0, 2)) {
                            break
                        }
                        val size = IOUtils.bytesToInt(false, 0, 2, *buffer) - 2
                        val data = ByteArray(size)
                        if (!IOUtils.readExactlyCheck(input, data, 0, size)) {
                            break
                        }
                        sofBytes = data
                    } else if (oneByte == 0xda) {
                        break
                    }
                }
                if (oneByte == -1) {
                    break
                }
            }
            val forbidRegionDecoder =
                sofBytes != null &&
                    sofBytes.size > 7 &&
                    sofBytes[5].toInt() and 0xff == 1 &&
                    sofBytes[7].toInt() and 0xff != 0x11
            val exifData = if (exifBytes != null) ExifData.extract(exifBytes, 0) else null
            return JpegData(
                if (exifData == null && exifBytes != null) ExifData.EMPTY else exifData,
                forbidRegionDecoder,
            )
        }
    }
}
