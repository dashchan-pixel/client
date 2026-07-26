package com.mishiranu.dashchan.content.net

import android.net.Uri
import chan.content.Chan
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import org.json.JSONArray
import org.json.JSONException

/**
 * Lists and downloads repository files through the GitHub REST API.
 *
 * @param githubUri repository URI, e.g. `https://github.com/owner/repo`
 */
class GithubRepository(
    private val holder: HttpHolder,
    githubUri: Uri,
) {
    class Entry internal constructor(
        @JvmField val directory: Boolean,
        @JvmField val downloadUri: Uri?,
    )

    private val repositoryPath: String

    init {
        val path = githubUri.path
        require(!path.isNullOrEmpty()) { "Invalid GitHub URI: $githubUri" }
        repositoryPath = if (path.endsWith("/")) path.substring(0, path.length - 1) else path
    }

    /** Returns a map of file names to entries for the given repository directory. */
    @Throws(HttpException::class, JSONException::class)
    fun listFiles(path: String): Map<String, Entry> {
        val uri =
            Uri
                .parse("https://api.github.com")
                .buildUpon()
                .appendEncodedPath("repos$repositoryPath/contents/$path")
                .build()
        val response =
            HttpRequest(uri, holder)
                .addHeader("Accept", "application/vnd.github+json")
                .perform()!!
                .readString()
        val array = JSONArray(response)
        val files = LinkedHashMap<String, Entry>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val name = item.getString("name")
            val directory = "dir" == item.optString("type")
            // isNull() is already true for both an absent key and a JSON null, so in the
            // else branch the value exists and optString() can never fall back to null.
            val downloadUrl = if (item.isNull("download_url")) null else item.optString("download_url")
            files[name] = Entry(directory, if (downloadUrl != null) Uri.parse(downloadUrl) else null)
        }
        return files
    }

    @Throws(HttpException::class)
    fun readFile(entry: Entry): ByteArray? {
        val downloadUri = entry.downloadUri ?: return null
        return HttpRequest(downloadUri, holder).perform()!!.readBytes()
    }

    companion object {
        @JvmStatic
        fun createHolder(): HttpHolder = HttpHolder(Chan.getFallback())
    }
}
