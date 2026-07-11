package chan.http

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Pair
import chan.annotation.Public
import chan.http.HttpClient.InterruptedHttpException
import chan.text.GroupParser.Companion.extractAttr
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.util.IOUtils.close
import com.mishiranu.dashchan.util.IOUtils.copyStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import kotlin.math.max

@Public
class HttpResponse internal constructor(
    val session: HttpSession?,
    private val validator: HttpValidator?,
    private var charsetName: String?
) {
    private enum class ExtractCharset {
        NONE, CHECK_HTML, FROM_HTML
    }

    private var extractCharset = ExtractCharset.NONE

    private var input: InputStream? = null
    private var bytes: ByteArray? = null
    private var string: String? = null

    init {
        val contentTypes = getHeaderFields().get("Content-Type")
        if (contentTypes != null && contentTypes.size == 1) {
            val contentType = contentTypes.get(0)
            if ("text/html" == contentType) {
                extractCharset = ExtractCharset.FROM_HTML
            }
        } else if (session != null) {
            val uri = session.currentRequestedUri
            val path = emptyIfNull(uri?.getPath()).lowercase()
            if (path.endsWith(".html")) {
                extractCharset = ExtractCharset.FROM_HTML
            } else {
                extractCharset = ExtractCharset.CHECK_HTML
            }
        }
    }

    @Public
    constructor(input: InputStream?) : this(null, null, null) {
        this.input = input
    }

    @Public
    constructor(bytes: ByteArray?) : this(null, null, null) {
        this.bytes = bytes
    }

    @Public
    fun setEncoding(charsetName: String?) {
        this.string = null
        this.charsetName = charsetName
        extractCharset = ExtractCharset.NONE
    }

    @Public
    @Throws(HttpException::class)
    fun getEncoding(): String? {
        if (extractCharset != ExtractCharset.NONE) {
            prepareOrGetInput()
        }
        return if (isEmpty(charsetName)) "ISO-8859-1" else charsetName
    }

    @Public
    @Throws(HttpException::class)
    fun checkResponseCode() {
        if (session != null) {
            session.checkResponseCode()
        }
    }

    @Public
    fun getResponseCode(): Int {
        return if (session != null) session.responseCode else HttpURLConnection.HTTP_OK
    }

    @Public
    fun getRequestedUri(): Uri? {
        if (session != null) {
            return session.getRequestedUris().get(0)
        } else {
            return null
        }
    }

    @Public
    fun getRequestedUris(): MutableList<Uri?> {
        if (session != null) {
            val uris = session.getRequestedUris()
            return ArrayList<Uri?>(uris)
        } else {
            return mutableListOf<Uri?>()
        }
    }

    @Public
    fun getRedirectedUri(): Uri? {
        if (session != null) {
            session.checkThread()
            return session.redirectedUri
        }
        return null
    }

    @Public
    fun setRedirectedUri(redirectedUri: Uri?) {
        session!!.checkThread()
        session.redirectedUri = redirectedUri
    }

    @Public
    fun getHeaderFields(): MutableMap<String?, MutableList<String>?> {
        return if (session != null) session.headerFields else mutableMapOf()
    }

    @Public
    fun getCookieValue(name: String?): String? {
        return if (session != null) session.getCookieValue(name) else null
    }

    val length: Long
        get() = if (session != null) session.length else -1

    @Public
    fun getValidator(): HttpValidator? {
        if (session != null) {
            session.checkThread()
            return validator
        } else {
            return null
        }
    }

    private class ConcatInputStream(head: ByteArrayInputStream?, val tail: InputStream?) :
        SequenceInputStream(
            head,
            tail
        )

    @Throws(HttpException::class)
    private fun prepareOrGetInput(): InputStream? {
        if (input == null && session != null) {
            session.checkThread()
            if (session.okResponse != null) {
                val input = session.client.open(this)
                // Set input to ensure client.open called only once
                this.input = input
                if (extractCharset != ExtractCharset.NONE) {
                    try {
                        val pair: Pair<InputStream?, String?> = extractCharsetFromHtml(
                            input,
                            extractCharset == ExtractCharset.CHECK_HTML
                        )
                        this.input = pair.first
                        if (pair.second != null) {
                            charsetName = pair.second
                        }
                    } catch (e: IOException) {
                        throw fail(e)
                    } finally {
                        extractCharset = ExtractCharset.NONE
                    }
                }
            }
        }
        return input
    }

    @Public
    @Throws(HttpException::class)
    fun open(): InputStream {
        val input = prepareOrGetInput()
        if (input != null) {
            return input
        }
        if (bytes != null) {
            return ByteArrayInputStream(bytes)
        }
        throw HttpException(ErrorItem.Type.EMPTY_RESPONSE, false, false)
    }

    @Public
    fun fail(exception: IOException?): HttpException {
        cleanupAndDisconnect()
        if (exception is InterruptedHttpException) {
            return exception.toHttp()
        } else {
            return HttpClient.Companion.transformIOException(exception!!)
        }
    }

    @Public
    @Throws(HttpException::class)
    fun readBytes(): ByteArray? {
        val input = prepareOrGetInput()
        if (input != null) {
            try {
                input.use { ignored ->
                    val output = ByteArrayOutputStream()
                    copyStream(input, output)
                    bytes = output.toByteArray()
                }
            } catch (e: IOException) {
                throw fail(e)
            } finally {
                this.input = null
            }
        }
        if (bytes != null) {
            return bytes
        }
        throw HttpException(ErrorItem.Type.EMPTY_RESPONSE, false, false)
    }

    @Public
    @Throws(HttpException::class)
    fun readString(): String? {
        readBytes()
        if (string == null && bytes != null) {
            try {
                string = kotlin.text.String(bytes!!, charset(getEncoding()!!))
            } catch (e: UnsupportedEncodingException) {
                throw HttpException(ErrorItem.Type.DOWNLOAD, false, false, e)
            }
        }
        return string
    }

    @Public
    @Throws(HttpException::class)
    fun readBitmap(): Bitmap? {
        readBytes()
        return if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size) else null
    }

    fun cleanupAndDisconnect() {
        if (session != null) {
            session.checkThread()
        }
        close(input)
        input = null
        if (session != null && session.okResponse != null) {
            session.disconnectAndClear()
        }
    }

    fun cleanupAndDisconnectIfEquals(input: InputStream?) {
        var equals = false
        var checkInput = this.input
        while (true) {
            if (checkInput === input) {
                equals = true
            } else if (checkInput is ConcatInputStream) {
                checkInput = checkInput.tail
                continue
            }
            break
        }
        if (equals) {
            this.input = null
            cleanupAndDisconnect()
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun extractCharsetFromHtml(
            input: InputStream,
            checkHtml: Boolean
        ): Pair<InputStream?, String?> {
            var checkHtml = checkHtml
            val output = ByteArrayOutputStream()
            val reader = InputStreamReader(object : InputStream() {
                @Throws(IOException::class)
                override fun read(): Int {
                    val result = input.read()
                    if (result >= 0) {
                        output.write(result)
                    }
                    return result
                }

                @Throws(IOException::class)
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val result = input.read(b, off, len)
                    if (result > 0) {
                        output.write(b, off, result)
                    }
                    return result
                }
            }, "ISO-8859-1")
            val builder = StringBuilder()
            if (checkHtml) {
                val minHtmlStart = "<!DOCTYPE html><html><head>"
                val buffer = CharArray(minHtmlStart.length)
                val count = reader.read(buffer)
                if (count >= 0) {
                    builder.append(buffer, 0, count)
                    val string = builder.toString().lowercase()
                    checkHtml = !string.contains("<!doctype html")
                }
            }
            var foundHeadClose = false
            if (!checkHtml) {
                val headClose = "</head>"
                val buffer = CharArray(1024)
                var count: Int
                while ((reader.read(buffer).also { count = it }) >= 0) {
                    for (i in 0..<count) {
                        buffer[i] = buffer[i].lowercaseChar()
                    }
                    val checkFrom = max(0, builder.length - headClose.length)
                    builder.append(buffer, 0, count)
                    val index = builder.indexOf(headClose, checkFrom)
                    if (index >= 0) {
                        builder.setLength(index + headClose.length)
                        foundHeadClose = true
                        break
                    }
                }
            }
            val headInput = ByteArrayInputStream(output.toByteArray())
            if (checkHtml) {
                return Pair<InputStream?, String?>(ConcatInputStream(headInput, input), null)
            }
            if (!foundHeadClose) {
                input.use { ignored ->
                    return Pair<InputStream?, String?>(headInput, null)
                }
            }
            var charsetName: String? = null
            var from = 0
            while (true) {
                val start = builder.indexOf("<meta", from)
                val end = builder.indexOf(">", start + 1)
                if (start < 0 || end <= start) {
                    break
                }
                val attrs = builder.substring(start + 5, end).lowercase()
                if ("content-type" == extractAttr(attrs, "http-equiv")) {
                    val contentType = extractAttr(attrs, "content")
                    charsetName = HttpClient.Companion.extractCharsetName(contentType)
                    break
                } else {
                    charsetName = extractAttr(attrs, "charset")
                    if (charsetName != null) {
                        break
                    }
                }
                from = end + 1
            }
            return Pair<InputStream?, String?>(ConcatInputStream(headInput, input), charsetName)
        }
    }
}
