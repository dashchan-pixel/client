/*
 * Copyright 2014-2017 Fukurou Mishiranu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package chan.http

import chan.util.StringUtils.getFileExtension
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.content.model.FileHolder.ImageType
import com.mishiranu.dashchan.util.GraphicsUtils.Reencoding
import com.mishiranu.dashchan.util.GraphicsUtils.SkipRange
import com.mishiranu.dashchan.util.GraphicsUtils.transformImageForPosting
import com.mishiranu.dashchan.util.IOUtils.skipExactlyCheck
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Random
import kotlin.math.min

class ChanFileOpenable(
    fileHolder: FileHolder,
    fileName: String?,
    uniqueHash: Boolean,
    removeMetadata: Boolean,
    removeFileName: Boolean,
    reencoding: Reencoding?,
) : MultipartEntity.Openable {
    private val fileHolder: FileHolder
    override val fileName: String?
    override val mimeType: String?
    val imageWidth: Int
    val imageHeight: Int

    private val randomBytes: Int
    private val skipRanges: ArrayList<SkipRange>?
    private val decodedBytes: ByteArray?
    private val realSize: Long

    init {
        var currentFileName = fileName
        this.fileHolder = fileHolder
        if (currentFileName == null) {
            currentFileName = fileHolder.name
        }
        if (removeFileName) {
            val extension = getFileExtension(currentFileName)
            val time = System.currentTimeMillis()
            if (extension != null && extension.matches("[a-z0-9]{1,10}".toRegex())) {
                currentFileName = time.toString() + "." + extension
            } else {
                when (fileHolder.imageType) {
                    ImageType.IMAGE_JPEG -> {
                        currentFileName = time.toString() + ".jpeg"
                    }

                    ImageType.IMAGE_PNG -> {
                        currentFileName = time.toString() + ".png"
                    }

                    ImageType.IMAGE_GIF -> {
                        currentFileName = time.toString() + ".gif"
                    }

                    ImageType.IMAGE_WEBP -> {
                        currentFileName = time.toString() + ".webp"
                    }

                    ImageType.IMAGE_BMP -> {
                        currentFileName = time.toString() + ".bmp"
                    }

                    ImageType.IMAGE_SVG -> {
                        currentFileName = time.toString() + ".svg"
                    }

                    else -> {
                        currentFileName = time.toString()
                    }
                }
            }
        }
        randomBytes = if (uniqueHash) 6 else 0
        val transformationData =
            transformImageForPosting(
                fileHolder,
                currentFileName,
                removeMetadata,
                reencoding,
            )
        if (transformationData != null) {
            skipRanges = transformationData.skipRanges
            decodedBytes = transformationData.decodedBytes
            if (transformationData.newFileName != null) {
                currentFileName = transformationData.newFileName
            }
            imageWidth =
                if (transformationData.newWidth > 0) transformationData.newWidth else fileHolder.imageWidth
            imageHeight =
                if (transformationData.newHeight > 0) transformationData.newHeight else fileHolder.imageHeight
        } else {
            skipRanges = null
            decodedBytes = null
            imageWidth = fileHolder.imageWidth
            imageHeight = fileHolder.imageHeight
        }
        this.fileName = currentFileName
        mimeType = MultipartEntity.obtainMimeType(currentFileName)
        realSize = (if (decodedBytes != null) decodedBytes.size else fileHolder.size).toLong()
    }

    @Throws(IOException::class)
    override fun openInputStream(): InputStream = ChanFileInputStream()

    override val size: Long
        get() {
            var totalSkip = 0L
            if (skipRanges != null) {
                for (skipRange in skipRanges) {
                    totalSkip += skipRange.count.toLong()
                }
            }
            return realSize + randomBytes - totalSkip
        }

    private inner class ChanFileInputStream : InputStream() {
        private val inputStream: InputStream

        private var position: Long = 0
        private var skipIndex = 0
        private var randomBytesLeft: Int

        private var tempBuffer: ByteArray? = null

        init {
            inputStream =
                if (decodedBytes != null) ByteArrayInputStream(decodedBytes) else fileHolder.openInputStream()
            randomBytesLeft = randomBytes
        }

        fun ensureTempBuffer(): ByteArray {
            var tempBuffer = this.tempBuffer
            if (tempBuffer == null) {
                tempBuffer = ByteArray(4096)
                this.tempBuffer = tempBuffer
            }
            return tempBuffer
        }

        @Throws(IOException::class)
        override fun read(): Int {
            val tempBuffer = ensureTempBuffer()
            val result = read(tempBuffer, 0, 1)
            if (result == 1) {
                return tempBuffer[0].toInt()
            }
            return -1
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)

        @Throws(IOException::class)
        override fun read(
            buffer: ByteArray,
            byteOffset: Int,
            byteCount: Int,
        ): Int {
            var totalRead = 0
            while (byteCount > totalRead) {
                val result = readAndSkip(buffer, byteOffset + totalRead, byteCount - totalRead)
                if (result < 0) {
                    if (randomBytesLeft > 0) {
                        val randomBytesCount = min(byteCount - totalRead, randomBytesLeft)
                        for (i in 0..<randomBytesCount) {
                            buffer[byteOffset + totalRead + i] =
                                (RANDOM.nextInt(0x49) + 0x30).toByte()
                        }
                        randomBytesLeft -= randomBytesCount
                        return totalRead + randomBytesCount
                    }
                    return if (totalRead > 0) totalRead else -1
                }
                totalRead += result
            }
            return totalRead
        }

        @Throws(IOException::class)
        fun readAndSkip(
            buffer: ByteArray?,
            byteOffset: Int,
            byteCount: Int,
        ): Int {
            val skipRange =
                if (skipRanges != null && skipIndex < skipRanges.size) {
                    skipRanges[skipIndex]
                } else {
                    null
                }
            val canRead = if (skipRange != null) skipRange.start - position else byteCount.toLong()
            if (canRead > 0) {
                val count =
                    inputStream.read(
                        buffer,
                        byteOffset,
                        if (canRead >= byteCount) byteCount else canRead.toInt(),
                    )
                if (count > 0) {
                    position += count.toLong()
                }
                return count
            }
            skipIndex++
            if (skipRange!!.count > 0) {
                position += skipRange.count.toLong()
                if (!skipExactlyCheck(inputStream, skipRange.count)) {
                    throw IOException()
                }
            }
            return 0
        }

        @Throws(IOException::class)
        override fun close() {
            inputStream.close()
        }
    }

    companion object {
        private val RANDOM = Random(System.currentTimeMillis())
    }
}
