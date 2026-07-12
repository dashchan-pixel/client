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

import chan.util.StringUtils

import chan.annotation.Extendable
import chan.annotation.Public
import chan.util.StringUtils.getFileExtension
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.content.model.FileHolder.Companion.obtain
import com.mishiranu.dashchan.util.MimeTypes.forExtension
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.util.Random

@Extendable
open class MultipartEntity @Public constructor() : RequestEntity {
    private val parts = ArrayList<Part>()
    private val boundary: String

    private var charsetName = "UTF-8"

    @Public
    constructor(vararg alternation: String?) : this() {
        var i = 0
        while (i < alternation.size) {
            add(alternation[i]!!, alternation[i + 1])
            i += 2
        }
    }

    @Public
    fun setEncoding(charsetName: String) {
        this.charsetName = charsetName
    }

    override fun add(name: String, value: String?) {
        if (value != null) {
            parts.add(StringPart(name, value, charsetName))
        }
    }

    @Extendable
    open fun add(name: String, file: File) {
        add(name, FileHolderOpenable(obtain(file)), null)
    }

    fun add(name: String?, openable: Openable?, listener: OpenableOutputListener?) {
        if (name == null) {
            throw NullPointerException("Name is null")
        }
        parts.add(OpenablePart(name, openable!!, listener))
    }

    override fun getContentType(): String? {
        return "multipart/form-data; boundary=" + boundary
    }

    override fun getContentLength(): Long {
        try {
            var contentLength = 0L
            val boundaryLength = boundary.length
            val dashesLength: Int = BYTES_TWO_DASHES.size
            val lineLength: Int = BYTES_NEW_LINE.size
            for (part in parts) {
                contentLength += (dashesLength + boundaryLength + lineLength).toLong()
                contentLength += (39 + part.name!!.toByteArray(charset(charsetName)).size).toLong()
                val fileName = part.fileName
                if (fileName != null) {
                    contentLength += (13 + fileName.toByteArray(charset(charsetName)).size).toLong()
                }
                contentLength += lineLength.toLong()
                val contentType = part.contentType
                if (contentType != null) {
                    contentLength += (14 + contentType.length + lineLength).toLong()
                }
                contentLength += lineLength + part.contentLength + lineLength
            }
            contentLength += (dashesLength + boundaryLength + dashesLength + lineLength).toLong()
            return contentLength
        } catch (e: UnsupportedEncodingException) {
            throw RuntimeException(e)
        }
    }

    init {
        val builder = StringBuilder()
        for (i in 0..26) {
            builder.append('-')
        }
        for (i in 0..10) {
            builder.append(RANDOM.nextInt(10))
        }
        boundary = builder.toString()
    }

    @Throws(IOException::class)
    override fun write(output: OutputStream) {
        val boundary = this.boundary.toByteArray(charset("ISO-8859-1"))
        for (part in parts) {
            output.write(BYTES_TWO_DASHES)
            output.write(boundary)
            output.write(BYTES_NEW_LINE)
            output.write("Content-Disposition: form-data; name=\"".toByteArray())
            output.write(part.name!!.toByteArray(charset(charsetName)))
            output.write('"'.code)
            val fileName = part.fileName
            if (fileName != null) {
                output.write("; filename=\"".toByteArray())
                output.write(fileName.toByteArray(charset(charsetName)))
                output.write('"'.code)
            }
            output.write(BYTES_NEW_LINE)
            val contentType = part.contentType
            if (contentType != null) {
                output.write(("Content-Type: " + contentType).toByteArray(charset("ISO-8859-1")))
                output.write(BYTES_NEW_LINE)
            }
            output.write(BYTES_NEW_LINE)
            part.write(output)
            output.write(BYTES_NEW_LINE)
        }
        output.write(BYTES_TWO_DASHES)
        output.write(boundary)
        output.write(BYTES_TWO_DASHES)
        output.write(BYTES_NEW_LINE)
        output.flush()
    }

    override fun copy(): MultipartEntity {
        val entity = MultipartEntity()
        entity.setEncoding(charsetName)
        entity.parts.addAll(parts)
        return entity
    }

    private abstract class Part(val name: String?) {
        abstract val fileName: String?
        abstract val contentType: String?
        abstract val contentLength: Long

        @Throws(IOException::class)
        abstract fun write(output: OutputStream)
    }

    private class StringPart(name: String?, value: String, charset: String) : Part(name) {
        private val bytes: ByteArray

        init {
            try {
                bytes = value.toByteArray(charset(charset))
            } catch (e: UnsupportedEncodingException) {
                throw RuntimeException(e)
            }
        }

        override val fileName: String?
            get() = null

        override val contentType: String?
            get() = null

        override val contentLength: Long
            get() = bytes.size.toLong()

        @Throws(IOException::class)
        override fun write(output: OutputStream) {
            output.write(bytes)
        }
    }

    private class OpenablePart(
        name: String?,
        private val openable: Openable,
        private val listener: OpenableOutputListener?
    ) : Part(name) {
        override val fileName: String?
            get() = openable.fileName

        override val contentType: String?
            get() = openable.mimeType

        override val contentLength: Long
            get() = openable.size

        @Throws(IOException::class)
        override fun write(output: OutputStream) {
            val input = openable.openInputStream()
            try {
                var progress = 0L
                val progressMax = openable.size
                if (listener != null) {
                    listener.onOutputProgressChange(openable, 0L, progressMax)
                }
                val buffer = ByteArray(4096)
                var count: Int
                while ((input.read(buffer).also { count = it }) > 0) {
                    output.write(buffer, 0, count)
                    progress += count.toLong()
                    if (listener != null) {
                        listener.onOutputProgressChange(openable, progress, progressMax)
                    }
                }
            } finally {
                input.close()
            }
        }
    }

    interface Openable {
        val fileName: String?
        val mimeType: String?

        @Throws(IOException::class)
        fun openInputStream(): InputStream
        val size: Long
    }

    private class FileHolderOpenable(private val fileHolder: FileHolder) : Openable {
        override val fileName: String = fileHolder.name
        override val mimeType: String? = obtainMimeType(fileName)

        @Throws(IOException::class)
        override fun openInputStream(): InputStream {
            return fileHolder.openInputStream()
        }

        override val size: Long
            get() = fileHolder.size.toLong()
    }

    interface OpenableOutputListener {
        fun onOutputProgressChange(openable: Openable?, progress: Long, progressMax: Long)
    }

    companion object {
        private val RANDOM = Random(System.currentTimeMillis())

        private val BYTES_TWO_DASHES = byteArrayOf(0x2d, 0x2d)
        private val BYTES_NEW_LINE = byteArrayOf(0x0d, 0x0a)

        fun obtainMimeType(fileName: String?): String? {
            return forExtension(getFileExtension(fileName), "application/octet-stream")
        }
    }
}
