package chan.text

import chan.annotation.Public
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

@Public
object JsonSerial {
    private val FACTORY = JsonFactory()

    @Public
    @JvmStatic
    @Throws(IOException::class, ParseException::class)
    fun reader(input: ByteArray): Reader =
        try {
            ReaderImpl(FACTORY.createParser(input))
        } catch (e: JsonProcessingException) {
            throw ParseException(e)
        }

    @Public
    @JvmStatic
    @Throws(IOException::class, ParseException::class)
    fun reader(input: InputStream): Reader =
        try {
            ReaderImpl(FACTORY.createParser(input))
        } catch (e: JsonProcessingException) {
            throw ParseException(e)
        }

    @Public
    @JvmStatic
    @Throws(IOException::class)
    fun writer(): Writer {
        val output = ByteArrayOutputStream()
        return WriterImpl(output, FACTORY.createGenerator(output))
    }

    @Public
    @JvmStatic
    @Throws(IOException::class)
    fun writer(output: OutputStream): Writer = WriterImpl(null, FACTORY.createGenerator(output))

    @Public
    enum class ValueType {
        @Public SCALAR,

        @Public OBJECT,

        @Public ARRAY,
    }

    @Public
    interface Reader : Closeable {
        @Public
        @Throws(IOException::class, ParseException::class)
        fun startObject()

        @Public
        @Throws(IOException::class, ParseException::class)
        fun startArray()

        @Public
        @Throws(IOException::class, ParseException::class)
        fun endStruct(): Boolean

        @Public
        @Throws(IOException::class, ParseException::class)
        fun nextName(): String?

        @Public
        @Throws(IOException::class, ParseException::class)
        fun valueType(): ValueType?

        @Public
        @Throws(IOException::class, ParseException::class)
        fun nextInt(): Int

        @Public
        @Throws(IOException::class, ParseException::class)
        fun nextLong(): Long

        @Public
        @Throws(IOException::class, ParseException::class)
        fun nextDouble(): Double

        @Public
        @Throws(IOException::class, ParseException::class)
        fun nextBoolean(): Boolean

        @Public
        @Throws(IOException::class, ParseException::class)
        fun nextString(): String?

        @Public
        @Throws(IOException::class, ParseException::class)
        fun skip()
    }

    @Public
    interface Writer : Closeable {
        @Public
        @Throws(IOException::class)
        fun startObject()

        @Public
        @Throws(IOException::class)
        fun endObject()

        @Public
        @Throws(IOException::class)
        fun startArray()

        @Public
        @Throws(IOException::class)
        fun endArray()

        @Public
        @Throws(IOException::class)
        fun name(name: String)

        @Public
        @Throws(IOException::class)
        fun value(value: Int)

        @Public
        @Throws(IOException::class)
        fun value(value: Long)

        @Public
        @Throws(IOException::class)
        fun value(value: Double)

        @Public
        @Throws(IOException::class)
        fun value(value: Boolean)

        @Public
        @Throws(IOException::class)
        fun value(value: String)

        @Public
        @Throws(IOException::class)
        fun flush()

        @Public
        @Throws(IOException::class)
        fun build(): ByteArray
    }

    private class ReaderImpl(
        private val parser: JsonParser,
    ) : Reader {
        private var currentName: String? = null

        init {
            try {
                nextTokenUnchecked()
            } catch (e: JsonProcessingException) {
                throw ParseException(e)
            }
        }

        @Throws(IOException::class, ParseException::class)
        private fun nextTokenUnchecked() {
            try {
                while (true) {
                    var token = parser.nextToken()
                    if (token == JsonToken.FIELD_NAME) {
                        val name = parser.currentName()
                        token = parser.nextToken()
                        if (token != JsonToken.VALUE_NULL) {
                            currentName = name
                            break
                        }
                    } else if (token != JsonToken.VALUE_NULL) {
                        break
                    }
                }
            } catch (e: JsonProcessingException) {
                throw ParseException(e)
            }
        }

        private fun checkToken(token: JsonToken): Boolean =
            if (currentName != null) {
                token == JsonToken.FIELD_NAME
            } else {
                parser.hasToken(token)
            }

        @Throws(ParseException::class)
        private fun throwIllegalState(): Nothing =
            throw ParseException(
                "Illegal state: " + (
                    if (currentName != null) {
                        JsonToken.FIELD_NAME
                    } else {
                        parser.currentToken()
                    }
                ),
            )

        @Throws(IOException::class, ParseException::class)
        override fun startObject() {
            if (checkToken(JsonToken.START_OBJECT)) {
                nextTokenUnchecked()
            } else {
                throwIllegalState()
            }
        }

        @Throws(IOException::class, ParseException::class)
        override fun startArray() {
            if (checkToken(JsonToken.START_ARRAY)) {
                nextTokenUnchecked()
            } else {
                throwIllegalState()
            }
        }

        @Throws(IOException::class, ParseException::class)
        override fun endStruct(): Boolean {
            val token = parser.currentToken() ?: throwIllegalState()
            if (token.isStructEnd) {
                nextTokenUnchecked()
                return true
            }
            return false
        }

        @Throws(IOException::class, ParseException::class)
        override fun nextName(): String? {
            try {
                if (checkToken(JsonToken.FIELD_NAME)) {
                    var name = currentName
                    if (name == null) {
                        name = parser.currentName()
                        nextTokenUnchecked()
                    } else {
                        currentName = null
                    }
                    return name
                } else {
                    throwIllegalState()
                }
            } catch (e: JsonProcessingException) {
                throw ParseException(e)
            }
        }

        @Throws(ParseException::class)
        override fun valueType(): ValueType {
            if (currentName != null) {
                throwIllegalState()
            }
            val token = parser.currentToken() ?: throwIllegalState()
            return when {
                token.isScalarValue -> ValueType.SCALAR
                token == JsonToken.START_OBJECT -> ValueType.OBJECT
                token == JsonToken.START_ARRAY -> ValueType.ARRAY
                else -> throwIllegalState()
            }
        }

        @Throws(IOException::class, ParseException::class)
        override fun nextInt(): Int {
            try {
                when {
                    checkToken(JsonToken.VALUE_NUMBER_INT) -> {
                        val value = parser.intValue
                        nextTokenUnchecked()
                        return value
                    }

                    checkToken(JsonToken.VALUE_NUMBER_FLOAT) -> {
                        val value = parser.floatValue.toInt()
                        nextTokenUnchecked()
                        return value
                    }

                    checkToken(JsonToken.VALUE_STRING) -> {
                        val value =
                            try {
                                parser.text.toInt()
                            } catch (e: NumberFormatException) {
                                throw ParseException(e)
                            }
                        nextTokenUnchecked()
                        return value
                    }

                    else -> {
                        throwIllegalState()
                    }
                }
            } catch (e: JsonProcessingException) {
                throw ParseException(e)
            }
        }

        @Throws(IOException::class, ParseException::class)
        override fun nextLong(): Long {
            try {
                when {
                    checkToken(JsonToken.VALUE_NUMBER_INT) -> {
                        val value = parser.longValue
                        nextTokenUnchecked()
                        return value
                    }

                    checkToken(JsonToken.VALUE_NUMBER_FLOAT) -> {
                        val value = parser.doubleValue.toLong()
                        nextTokenUnchecked()
                        return value
                    }

                    checkToken(JsonToken.VALUE_STRING) -> {
                        val value =
                            try {
                                parser.text.toLong()
                            } catch (e: NumberFormatException) {
                                throw ParseException(e)
                            }
                        nextTokenUnchecked()
                        return value
                    }

                    else -> {
                        throwIllegalState()
                    }
                }
            } catch (e: JsonProcessingException) {
                throw ParseException(e)
            }
        }

        @Throws(IOException::class, ParseException::class)
        override fun nextDouble(): Double {
            try {
                when {
                    checkToken(JsonToken.VALUE_NUMBER_INT) -> {
                        val value = parser.longValue.toDouble()
                        nextTokenUnchecked()
                        return value
                    }

                    checkToken(JsonToken.VALUE_NUMBER_FLOAT) -> {
                        val value = parser.doubleValue
                        nextTokenUnchecked()
                        return value
                    }

                    checkToken(JsonToken.VALUE_STRING) -> {
                        val value =
                            try {
                                parser.text.toDouble()
                            } catch (e: NumberFormatException) {
                                throw ParseException(e)
                            }
                        nextTokenUnchecked()
                        return value
                    }

                    else -> {
                        throwIllegalState()
                    }
                }
            } catch (e: JsonProcessingException) {
                throw ParseException(e)
            }
        }

        @Throws(IOException::class, ParseException::class)
        override fun nextBoolean(): Boolean {
            try {
                when {
                    checkToken(JsonToken.VALUE_TRUE) -> {
                        nextTokenUnchecked()
                        return true
                    }

                    checkToken(JsonToken.VALUE_FALSE) -> {
                        nextTokenUnchecked()
                        return false
                    }

                    checkToken(JsonToken.VALUE_NUMBER_INT) -> {
                        val value = parser.intValue
                        nextTokenUnchecked()
                        return value != 0
                    }

                    checkToken(JsonToken.VALUE_STRING) -> {
                        val textLower = parser.text.lowercase(Locale.US)
                        val value =
                            when (textLower) {
                                "true" -> {
                                    1
                                }

                                "false" -> {
                                    0
                                }

                                else -> {
                                    try {
                                        parser.text.toInt()
                                    } catch (e: NumberFormatException) {
                                        throw ParseException(e)
                                    }
                                }
                            }
                        nextTokenUnchecked()
                        return value != 0
                    }

                    else -> {
                        throwIllegalState()
                    }
                }
            } catch (e: JsonProcessingException) {
                throw ParseException(e)
            }
        }

        @Throws(IOException::class, ParseException::class)
        override fun nextString(): String? {
            try {
                if (checkToken(JsonToken.VALUE_STRING)) {
                    val value = parser.text
                    nextTokenUnchecked()
                    return value
                } else {
                    if (currentName == null) {
                        val token = parser.currentToken()
                        if (token != null && token.isScalarValue) {
                            val value = parser.text
                            nextTokenUnchecked()
                            return value
                        }
                    }
                    throwIllegalState()
                }
            } catch (e: JsonProcessingException) {
                throw ParseException(e)
            }
        }

        @Throws(IOException::class, ParseException::class)
        override fun skip() {
            try {
                if (currentName != null) {
                    throwIllegalState()
                }
                val token = parser.currentToken()
                if (token == null || token == JsonToken.FIELD_NAME || token.isStructEnd) {
                    throwIllegalState()
                } else if (token.isStructStart) {
                    parser.skipChildren()
                }
                nextTokenUnchecked()
            } catch (e: JsonProcessingException) {
                throw ParseException(e)
            }
        }

        @Throws(IOException::class)
        override fun close() {
            parser.close()
        }
    }

    private class WriterImpl(
        private val output: ByteArrayOutputStream?,
        private val generator: JsonGenerator,
    ) : Writer {
        @Throws(IOException::class)
        override fun startObject() {
            generator.writeStartObject()
        }

        @Throws(IOException::class)
        override fun endObject() {
            generator.writeEndObject()
        }

        @Throws(IOException::class)
        override fun startArray() {
            generator.writeStartArray()
        }

        @Throws(IOException::class)
        override fun endArray() {
            generator.writeEndArray()
        }

        @Throws(IOException::class)
        override fun name(name: String) {
            generator.writeFieldName(name)
        }

        @Throws(IOException::class)
        override fun value(value: Int) {
            generator.writeNumber(value)
        }

        @Throws(IOException::class)
        override fun value(value: Long) {
            generator.writeNumber(value)
        }

        @Throws(IOException::class)
        override fun value(value: Double) {
            generator.writeNumber(value)
        }

        @Throws(IOException::class)
        override fun value(value: Boolean) {
            generator.writeBoolean(value)
        }

        @Throws(IOException::class)
        override fun value(value: String) {
            generator.writeString(value)
        }

        @Throws(IOException::class)
        override fun flush() {
            generator.flush()
        }

        @Throws(IOException::class)
        override fun build(): ByteArray {
            flush()
            return output!!.toByteArray()
        }

        @Throws(IOException::class)
        override fun close() {
            generator.close()
        }
    }
}
