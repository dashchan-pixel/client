package com.mishiranu.dashchan.content.async

import android.net.Uri
import chan.content.Chan
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import com.mishiranu.dashchan.content.Preferences
import org.json.JSONException
import org.json.JSONObject

class ReadFeedbackTask(
    private val callback: Callback,
) : HttpHolderTask<Unit, String?>(Chan.getFallback()) {
    fun interface Callback {
        fun onReadFeedbackComplete(feedbackUrl: String?)
    }

    override fun run(holder: HttpHolder): String? {
        val chan = Chan.getFallback()
        val urisToTry = ArrayList<String>()
        urisToTry.add(Preferences.uriUpdates)
        for (uriString in Preferences.uriUpdatesExtensions) {
            if (uriString != null) {
                urisToTry.add(uriString)
            }
        }

        for (uriString in urisToTry) {
            try {
                val targetUri = Uri.parse(uriString)
                val scheme = targetUri.scheme

                // Try directory strategy (data-v1.json / data.json) or file directly
                val lastSegment = targetUri.lastPathSegment
                val isDirectory = lastSegment == "data-v1.json" || lastSegment == "data.json"

                val baseUri =
                    if (isDirectory) {
                        val path = targetUri.path!!
                        val builder = targetUri.buildUpon()
                        builder.path(path.substring(0, path.lastIndexOf('/')))
                        builder.build()
                    } else {
                        targetUri
                    }

                if (isDirectory) {
                    for (fileName in arrayOf("data-v1.json", "data.json")) {
                        val uri =
                            chan.locator.setSchemeIfEmpty(
                                baseUri.buildUpon().appendPath(fileName).build(),
                                scheme,
                            )
                        val result = fetchAndParseFeedback(uri, holder)
                        if (result != null) {
                            return result
                        }
                    }
                } else {
                    val uri = chan.locator.setSchemeIfEmpty(targetUri, scheme)
                    val result = fetchAndParseFeedback(uri, holder)
                    if (result != null) {
                        return result
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    private fun fetchAndParseFeedback(
        uri: Uri?,
        holder: HttpHolder,
    ): String? {
        if (uri == null) return null
        try {
            val responseText = HttpRequest(uri, holder).perform()?.readString() ?: return null
            val jsonObject = JSONObject(responseText)
            val feedback = jsonObject.optString("feedback")
            if (feedback.isNotEmpty()) {
                return feedback
            }
        } catch (e: HttpException) {
            e.printStackTrace()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return null
    }

    override fun onComplete(result: String?) {
        callback.onReadFeedbackComplete(result)
    }
}
