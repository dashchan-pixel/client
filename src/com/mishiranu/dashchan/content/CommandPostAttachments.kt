package com.mishiranu.dashchan.content

import android.net.Uri
import com.mishiranu.dashchan.content.model.Post
import org.json.JSONArray
import org.json.JSONObject

/**
 * The attachment half of a [CommandsStorage.UseIn.THREAD][com.mishiranu.dashchan.content.storage.CommandsStorage.UseIn.THREAD]
 * command: it turns a post's attached files into the `attachments` array of the post the script is
 * handed, and turns the array it returns back into the files the post shows.
 *
 * What a script sees is one object per attached file:
 *
 * ```
 * { id: 0, name: "sunset.jpg", size: 519342, width: 1280, height: 720, spoiler: false,
 *   url: "https://example.org/src/1234.jpg", thumbnail: "https://example.org/thumb/1234s.jpg" }
 * ```
 *
 * [id][Requested.id] is the file's position in the list as the run started; it is how a returned entry
 * says *which* file it is. Returning the array with entries dropped hides those files, returning it in
 * another order reorders them, and returning an entry with a changed field applies that change — so a
 * script that has decrypted a post's image points `url` and `thumbnail` at what it decrypted:
 *
 * ```
 * return { attachments: post.attachments.map(a => ({ ...a, url: decrypted(a.url) })) };
 * ```
 *
 * An entry with no `id` is a **new** file, which needs at least a `url` or a `thumbnail`. Unlike a
 * draft's attachment (see [CommandAttachments]) nothing is uploaded here, so an address is all there
 * is to it — either something the app can fetch, or a `data:` URL the script built itself, which the
 * thumbnail loader reads without touching the network.
 *
 * Only the *files* of a post are handed over and replaced. What the chan embedded in the post, and
 * what the app parsed out of its comment (a YouTube link, say), is not a file the script could point
 * elsewhere; those items stay as they are.
 *
 * An `id` that names no file fails the run rather than quietly hiding a file the post really has.
 * Nothing here is written to the post cache: a replacement lasts as long as the thread stays open,
 * and reading it again shows what the board sent until the command runs over it once more.
 */
object CommandPostAttachments {
    /**
     * One entry of the array a thread command returned for a post, read straight off the object the
     * script built.
     *
     * A field the script left out keeps whatever the file already had, so "absent" and "set to null"
     * are different answers and the entry has to stay able to tell them apart — hence the [has] pairs
     * and the nullable Booleans. `JSON.stringify` drops an `undefined` and keeps a `null`, which is
     * exactly that distinction as a script would write it.
     */
    class Requested internal constructor(
        private val item: JSONObject,
    ) {
        /** Position in the input list, or `null` for a file the script is adding. */
        val id: Int?
            get() = if (item.isNull(KEY_ID)) null else item.optInt(KEY_ID)

        val hasName: Boolean
            get() = item.has(KEY_NAME)

        /** The name the file is shown and downloaded under. */
        val name: String?
            get() = optString(KEY_NAME)

        val hasUrl: Boolean
            get() = item.has(KEY_URL)

        val url: String?
            get() = optString(KEY_URL)

        val hasThumbnail: Boolean
            get() = item.has(KEY_THUMBNAIL)

        val thumbnail: String?
            get() = optString(KEY_THUMBNAIL)

        val size: Int?
            get() = optInt(KEY_SIZE)

        val width: Int?
            get() = optInt(KEY_WIDTH)

        val height: Int?
            get() = optInt(KEY_HEIGHT)

        val spoiler: Boolean?
            get() = if (item.isNull(KEY_SPOILER)) null else item.optBoolean(KEY_SPOILER)

        private fun optString(key: String): String? = if (item.isNull(key)) null else item.optString(key)

        private fun optInt(key: String): Int? = if (item.isNull(key)) null else item.optInt(key)
    }

    /** The outcome of turning what a script returned into the files a post shows. */
    sealed interface Result {
        /** [attachments] is the list to show in place of the post's own files, in order. */
        data class Success(
            val attachments: List<Post.Attachment.File>,
        ) : Result

        /** A reference could not be resolved. */
        data class Failure(
            val message: String,
        ) : Result
    }

    /** The `attachments` array of a post handed to a thread command, as JSON. */
    fun toJson(attachments: List<Post.Attachment.File>): JSONArray {
        val array = JSONArray()
        for ((index, attachment) in attachments.withIndex()) {
            val obj = JSONObject()
            obj.put(KEY_ID, index)
            obj.put(KEY_NAME, attachment.originalName.ifEmpty { null } ?: JSONObject.NULL)
            obj.put(KEY_SIZE, attachment.size)
            obj.put(KEY_WIDTH, attachment.width)
            obj.put(KEY_HEIGHT, attachment.height)
            obj.put(KEY_SPOILER, attachment.spoiler)
            obj.put(KEY_URL, attachment.fileUri?.toString() ?: JSONObject.NULL)
            obj.put(KEY_THUMBNAIL, attachment.thumbnailUri?.toString() ?: JSONObject.NULL)
            array.put(obj)
        }
        return array
    }

    /**
     * Reads the array a script returned for one post. A non-object entry is skipped rather than failing
     * the run — a `null` left in the array by a `map` that meant to drop an entry is the likely cause,
     * and dropping is what it was after.
     */
    fun parse(array: JSONArray): List<Requested> {
        val requested = ArrayList<Requested>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            requested.add(Requested(item))
        }
        return requested
    }

    /**
     * Turns [requested] into the files a post shows, against the [input] it was derived from. Plain and
     * synchronous, unlike the draft's [CommandAttachments.resolve]: an attachment here is an address,
     * so there are no bytes to go and get.
     */
    fun resolve(
        input: List<Post.Attachment.File>,
        requested: List<Requested>,
    ): Result {
        val attachments = ArrayList<Post.Attachment.File>(requested.size)
        for (item in requested) {
            val id = item.id
            val base =
                if (id != null) {
                    input.getOrNull(id)
                        // Dropping it instead would silently hide a file the post has.
                        ?: return Result.Failure("Attachment id $id does not exist")
                } else {
                    null
                }
            attachments.add(
                apply(base, item)
                    // Post.Attachment.File is nothing without an address to reach, and a script that
                    // added an entry carrying neither meant something it did not write.
                    ?: return Result.Failure("Attachment ${describe(item)} has no url or thumbnail"),
            )
        }
        return Result.Success(attachments)
    }

    /**
     * [base] with the fields [item] set applied, or a wholly new file when there is no [base]; the
     * fields the script left out are kept as they were. `null` when nothing is left to point at, which
     * only a new entry can manage — an existing file always has one of the two addresses.
     */
    private fun apply(
        base: Post.Attachment.File?,
        item: Requested,
    ): Post.Attachment.File? {
        val fileUri = if (item.hasUrl) parseUri(item.url) else base?.fileUri
        val thumbnailUri = if (item.hasThumbnail) parseUri(item.thumbnail) else base?.thumbnailUri
        return Post.Attachment.File.createExternal(
            fileUri,
            thumbnailUri,
            if (item.hasName) item.name else base?.originalName,
            item.size ?: base?.size ?: 0,
            item.width ?: base?.width ?: 0,
            item.height ?: base?.height ?: 0,
            item.spoiler ?: (base?.spoiler == true),
        )
    }

    private fun parseUri(value: String?): Uri? = if (value.isNullOrEmpty()) null else Uri.parse(value)

    /** How a new attachment is named in a failure message, before it has a name of its own. */
    private fun describe(item: Requested): String = "\"${item.name ?: "?"}\""

    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_SIZE = "size"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_SPOILER = "spoiler"
    private const val KEY_URL = "url"
    private const val KEY_THUMBNAIL = "thumbnail"
}
