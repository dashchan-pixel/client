package chan.http

import android.os.Parcel
import android.os.Parcelable
import chan.annotation.Public
import chan.text.JsonSerial
import chan.text.ParseException
import java.io.IOException

@Public
class HttpValidator(
    val entityTag: String?,
    val lastModified: String?,
) : Parcelable {
    @Throws(IOException::class)
    fun serialize(writer: JsonSerial.Writer) {
        writer.startObject()
        entityTag?.let {
            writer.name("entityTag")
            writer.value(it)
        }
        lastModified?.let {
            writer.name("lastModified")
            writer.value(it)
        }
        writer.endObject()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeString(entityTag)
        dest.writeString(lastModified)
    }

    companion object {
        @JvmStatic
        fun obtain(headers: okhttp3.Headers): HttpValidator? {
            val eTag = headers["ETag"]
            val lastModified = headers["Last-Modified"]
            return if (eTag != null || lastModified != null) {
                HttpValidator(eTag, lastModified)
            } else {
                null
            }
        }

        @JvmStatic
        @Throws(IOException::class, ParseException::class)
        fun deserialize(reader: JsonSerial.Reader): HttpValidator {
            var entityTag: String? = null
            var lastModified: String? = null
            reader.startObject()
            while (!reader.endStruct()) {
                when (reader.nextName()) {
                    "entityTag" -> entityTag = reader.nextString()
                    "lastModified" -> lastModified = reader.nextString()
                    else -> reader.skip()
                }
            }
            return HttpValidator(entityTag, lastModified)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<HttpValidator> =
            object : Parcelable.Creator<HttpValidator> {
                override fun createFromParcel(source: Parcel): HttpValidator {
                    val entityTag = source.readString()
                    val lastModified = source.readString()
                    return HttpValidator(entityTag, lastModified)
                }

                override fun newArray(size: Int): Array<HttpValidator?> = arrayOfNulls(size)
            }
    }
}
