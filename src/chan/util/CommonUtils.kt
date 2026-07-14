package chan.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import chan.annotation.Public
import com.mishiranu.dashchan.util.Logger
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Array

@Public
object CommonUtils {
    @Public
    @JvmStatic
    fun equals(
        first: Any?,
        second: Any?,
    ): Boolean = first == second

    @Public
    @JvmStatic
    fun sleepMaxRealtime(
        startRealtime: Long,
        interval: Long,
    ): Boolean {
        val time = interval - (SystemClock.elapsedRealtime() - startRealtime)
        if (time <= 0) {
            return false
        }
        return try {
            Thread.sleep(time)
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        }
    }

    @Public
    @JvmStatic
    @JvmOverloads
    fun optJsonString(
        jsonObject: JSONObject,
        name: String,
        fallback: String? = null,
    ): String? =
        try {
            getJsonString(jsonObject, name)
        } catch (e: JSONException) {
            fallback
        }

    @Public
    @JvmStatic
    @Throws(JSONException::class)
    fun getJsonString(
        jsonObject: JSONObject,
        name: String,
    ): String? =
        if (jsonObject.has(name) && jsonObject.isNull(name)) {
            null
        } else {
            jsonObject.getString(name)
        }

    @Public
    @JvmStatic
    fun restoreCloudFlareProtectedEmails(string: String): String {
        var index = 0
        var builder: StringBuilder? = null
        while (true) {
            index = builder?.indexOf("/cdn-cgi/l/email-protection", index)
                ?: string.indexOf("/cdn-cgi/l/email-protection")
            if (index < 0) {
                break
            }
            if (builder == null) {
                builder = StringBuilder(string)
            }
            val index1 = builder.lastIndexOf("<", index)
            val index2 = builder.lastIndexOf("<a", index)
            val index3 = builder.indexOf(">", index)
            val index4 = builder.indexOf("</a>", index)
            if (index1 == index2 && index3 > index) {
                var index5 = builder.indexOf("\"", index)
                if (index5 == -1) {
                    index5 = builder.indexOf("'", index)
                }
                if (index5 == -1) {
                    index5 = builder.indexOf(" ", index)
                }
                if (index5 == -1) {
                    break
                }
                val url = builder.substring(index, index5)
                var index6 = url.indexOf('#')
                var hash: String? = null
                var replaceTag = false
                if (index6 >= 0) {
                    hash = url.substring(index6 + 1)
                } else {
                    index6 = builder.indexOf("data-cfemail=", index1)
                    if (index6 in 0 until index3) {
                        index6 += 14
                        index5 = builder.indexOf("\"", index6)
                        if (index5 == -1) {
                            index5 = builder.indexOf("'", index6)
                        }
                        if (index5 == -1) {
                            index5 = builder.indexOf(" ", index6--)
                        }
                        if (index5 == -1) {
                            break
                        }
                        hash = builder.substring(index6, index5)
                        replaceTag = true
                    }
                }
                if (hash != null && hash.length % 2 == 0) {
                    val x = hash.substring(0, 2).toInt(16)
                    val email = StringBuilder(hash.length / 2 - 1)
                    var i = 2
                    while (i < hash.length) {
                        val b = hash.substring(i, i + 2).toInt(16)
                        email.append((b xor x).toChar())
                        i += 2
                    }
                    if (replaceTag) {
                        builder.replace(index1, index4 + 4, email.toString())
                        index = index1 + email.length
                    } else {
                        builder.replace(index, index5, "mailto:$email")
                        index = builder.indexOf(">", index) + 1
                    }
                    continue
                }
            }
            if (index4 == -1) {
                break
            }
            index = index4 + 4
        }
        if (builder != null) {
            while (true) {
                val start = builder.indexOf("<script data-cfhash")
                if (start >= 0) {
                    var end = builder.indexOf("</script>")
                    if (end > start) {
                        end += 9
                        builder.delete(start, end)
                        continue
                    }
                }
                break
            }
            return builder.toString()
        }
        return string
    }

    @Public
    @JvmStatic
    fun trimBitmap(
        bitmap: Bitmap?,
        backgroundColor: Int,
    ): Bitmap? {
        if (bitmap == null) {
            return null
        }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(maxOf(width, height))
        var actualLeft = 0
        var actualRight = width
        var actualTop = 0
        var actualBottom = height
        out@ for (i in 0 until width) {
            bitmap.getPixels(pixels, 0, 1, i, 0, 1, height)
            for (j in 0 until height) {
                if (pixels[j] != backgroundColor) {
                    actualLeft = i
                    break@out
                }
            }
        }
        out@ for (i in width - 1 downTo 0) {
            bitmap.getPixels(pixels, 0, 1, i, 0, 1, height)
            for (j in 0 until height) {
                if (pixels[j] != backgroundColor) {
                    actualRight = i + 1
                    break@out
                }
            }
        }
        out@ for (i in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, i, width, 1)
            for (j in 0 until width) {
                if (pixels[j] != backgroundColor) {
                    actualTop = i
                    break@out
                }
            }
        }
        out@ for (i in height - 1 downTo 0) {
            bitmap.getPixels(pixels, 0, width, 0, i, width, 1)
            for (j in 0 until width) {
                if (pixels[j] != backgroundColor) {
                    actualBottom = i + 1
                    break@out
                }
            }
        }
        if (actualLeft != 0 || actualTop != 0 || actualRight != width || actualBottom != height) {
            if (actualRight > actualLeft && actualBottom > actualTop) {
                val newBitmap =
                    Bitmap.createBitmap(
                        actualRight - actualLeft,
                        actualBottom - actualTop,
                        Bitmap.Config.ARGB_8888,
                    )
                Canvas(newBitmap).drawBitmap(bitmap, -actualLeft.toFloat(), -actualTop.toFloat(), null)
                return newBitmap
            }
            return null
        }
        return bitmap
    }

    @Public
    @JvmStatic
    fun writeLog(vararg data: Any?) {
        Logger.write(Logger.Type.DEBUG, "PublicApi", *data)
    }

    @JvmStatic
    fun <T> removeNullItems(
        array: kotlin.Array<T?>?,
        itemClass: Class<T>,
    ): kotlin.Array<T?>? {
        if (array == null) {
            return null
        }
        val nullItems = array.count { it == null }
        if (nullItems == array.size) {
            return null
        }
        if (nullItems > 0) {
            @Suppress("UNCHECKED_CAST")
            val newArray = Array.newInstance(itemClass, array.size - nullItems) as kotlin.Array<T?>
            var i = 0
            for (item in array) {
                if (item != null) {
                    newArray[i++] = item
                }
            }
            return newArray
        }
        return array
    }

    @JvmStatic
    fun <T> toArray(
        collection: Collection<T>?,
        itemClass: Class<T>,
    ): kotlin.Array<T>? {
        if (collection != null && collection.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val array = Array.newInstance(itemClass, collection.size) as kotlin.Array<T>
            for ((i, item) in collection.withIndex()) {
                array[i] = item
            }
            return array
        }
        return null
    }
}
