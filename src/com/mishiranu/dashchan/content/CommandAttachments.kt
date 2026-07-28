package com.mishiranu.dashchan.content

import android.net.Uri
import android.util.Base64
import chan.content.Chan
import chan.http.HttpHolder
import chan.http.HttpRequest
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.content.storage.DraftsStorage
import com.mishiranu.dashchan.content.storage.DraftsStorage.AttachmentDraft
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.GraphicsUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The attachment half of a [CommandsStorage.UseIn.COMMENT][com.mishiranu.dashchan.content.storage.CommandsStorage.UseIn.COMMENT]
 * ("Draft") command: it turns the draft's attachments into the `attachments` array the script is
 * handed, and turns the array it returns back into attachments of the draft.
 *
 * What a script sees is one object per attached file:
 *
 * ```
 * { id: 0, name: "IMG_1.jpg", size: 519342, filename: "IMG_1.jpg", rating: null,
 *   spoiler: false, uniqueHash: false, removeMetadata: false, reencoding: null }
 * ```
 *
 * [id][Requested.id] is the file's position in the list as the run started; it is how a returned entry
 * says *which* file it is. Returning the array with entries dropped detaches those files, returning it
 * in another order reorders them, and returning an entry with a changed field applies that option —
 * `filename` being the name the file is sent under, or `null` to send it without one.
 *
 * An entry with no `id` is a **new** file, whose bytes come either from `data` (base64, with an
 * optional `data:…;base64,` prefix) or from `url`, which is downloaded here rather than in the script
 * so a body doesn't have to base64 an ArrayBuffer by hand:
 *
 * ```
 * attachments.push({ name: "out.png", url: "https://example.org/out.png", spoiler: true });
 * ```
 *
 * Every reference has to resolve: an `id` that names no file, an entry with neither `data` nor `url`,
 * a download that fails — each fails the run rather than quietly posting without the file.
 */
object CommandAttachments {
    /**
     * One entry of the array a Draft command returned, read straight off the object the script built.
     *
     * A field the script left out keeps whatever the attachment already had, so "absent" and "set to
     * null" are different answers and the entry has to stay able to tell them apart — hence the [has]
     * pairs and the nullable Booleans. `JSON.stringify` drops an `undefined` and keeps a `null`, which
     * is exactly that distinction as a script would write it.
     */
    class Requested internal constructor(
        private val item: JSONObject,
    ) {
        /** Position in the input list, or `null` for a file the script is adding. */
        val id: Int?
            get() = if (item.isNull(KEY_ID)) null else item.optInt(KEY_ID)

        val name: String?
            get() = optString(KEY_NAME)

        /** Base64 bytes of a new file. */
        val data: String?
            get() = optString(KEY_DATA)

        /** Address to download a new file from. */
        val url: String?
            get() = optString(KEY_URL)

        val hasFilename: Boolean
            get() = item.has(KEY_FILENAME)

        val filename: String?
            get() = optString(KEY_FILENAME)

        val hasRating: Boolean
            get() = item.has(KEY_RATING)

        val rating: String?
            get() = optString(KEY_RATING)

        val spoiler: Boolean?
            get() = optBoolean(KEY_SPOILER)

        val uniqueHash: Boolean?
            get() = optBoolean(KEY_UNIQUE_HASH)

        val removeMetadata: Boolean?
            get() = optBoolean(KEY_REMOVE_METADATA)

        val hasReencoding: Boolean
            get() = item.has(KEY_REENCODING)

        // Reencoding validates format, quality and reduce itself, so whatever a script puts here lands
        // on the nearest thing the re-encoder actually supports rather than being rejected.
        val reencoding: GraphicsUtils.Reencoding?
            get() {
                val obj = item.optJSONObject(KEY_REENCODING) ?: return null
                return GraphicsUtils.Reencoding(
                    obj.optString(KEY_REENCODING_FORMAT),
                    obj.optInt(KEY_REENCODING_QUALITY, 100),
                    obj.optInt(KEY_REENCODING_REDUCE, 1),
                )
            }

        private fun optString(key: String): String? = if (item.isNull(key)) null else item.optString(key)

        private fun optBoolean(key: String): Boolean? = if (item.isNull(key)) null else item.optBoolean(key)
    }

    /** The outcome of turning what a script returned into the draft's new attachment list. */
    sealed interface Result {
        /** [attachments] is the list to replace the draft's attachments with, in order. */
        data class Success(
            val attachments: List<AttachmentDraft>,
        ) : Result

        /** A reference could not be resolved or a new file could not be fetched. */
        data class Failure(
            val message: String,
        ) : Result
    }

    /** The `attachments` array a Draft command is handed, as JSON. */
    fun toJson(attachments: List<AttachmentDraft>): String {
        val array = JSONArray()
        val draftsStorage = DraftsStorage.getInstance()
        for ((index, attachment) in attachments.withIndex()) {
            val obj = JSONObject()
            obj.put(KEY_ID, index)
            obj.put(KEY_NAME, attachment.name ?: JSONObject.NULL)
            obj.put(KEY_SIZE, draftsStorage.getAttachmentDraftFileHolder(attachment.hash)?.size ?: 0)
            obj.put(KEY_FILENAME, currentFilename(attachment) ?: JSONObject.NULL)
            obj.put(KEY_RATING, attachment.rating ?: JSONObject.NULL)
            obj.put(KEY_SPOILER, attachment.optionSpoiler)
            obj.put(KEY_UNIQUE_HASH, attachment.optionUniqueHash)
            obj.put(KEY_REMOVE_METADATA, attachment.optionRemoveMetadata)
            obj.put(KEY_REENCODING, reencodingToJson(attachment.reencoding) ?: JSONObject.NULL)
            array.put(obj)
        }
        return array.toString()
    }

    /**
     * Reads the array a script returned. A non-object entry is skipped rather than failing the run —
     * a `null` left in the array by a `map` that meant to drop an entry is the likely cause, and
     * dropping is what it was after.
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
     * Turns [requested] into the draft's new attachment list against the [input] it was derived from,
     * and delivers it to [callback] on the main thread. Safe to call from the main thread.
     *
     * New files need their bytes, which for a `url` means the network, so this is asynchronous — but it
     * stays synchronous (the callback runs before it returns) when the script only rearranged what was
     * already there, which is every run that adds no file. The bytes are fetched off the main thread and
     * only handed to [DraftsStorage] once back on it, so an attachment is stored from the same thread
     * as one the user picked.
     */
    fun resolve(
        input: List<AttachmentDraft>,
        requested: List<Requested>,
        callback: (Result) -> Unit,
    ) {
        val added = requested.filter { it.id == null }
        if (added.isEmpty()) {
            callback(build(input, requested, emptyMap()))
            return
        }
        ConcurrentUtils.PARALLEL_EXECUTOR.execute {
            val files = LinkedHashMap<Requested, File>()
            var failure: String? = null
            for (item in added) {
                try {
                    files[item] = fetch(item)
                } catch (e: Exception) {
                    e.printStackTrace()
                    failure = "Attachment ${describe(item)}: ${e.message ?: e.javaClass.simpleName}"
                    break
                }
            }
            val message = failure
            ConcurrentUtils.HANDLER.post {
                val result =
                    try {
                        if (message != null) Result.Failure(message) else store(input, requested, files)
                    } finally {
                        // The drafts store keeps its own copy, so the temporary is of no use either way.
                        files.values.forEach { it.delete() }
                    }
                callback(result)
            }
        }
    }

    /** Hands the fetched files to [DraftsStorage] and builds the list. Main thread. */
    private fun store(
        input: List<AttachmentDraft>,
        requested: List<Requested>,
        files: Map<Requested, File>,
    ): Result {
        val draftsStorage = DraftsStorage.getInstance()
        val hashes = HashMap<Requested, String>()
        for ((item, file) in files) {
            val hash = draftsStorage.store(FileHolder.obtain(file))
            hash ?: return Result.Failure("Attachment ${describe(item)} could not be stored")
            hashes[item] = hash
        }
        return build(input, requested, hashes)
    }

    private fun build(
        input: List<AttachmentDraft>,
        requested: List<Requested>,
        hashes: Map<Requested, String>,
    ): Result {
        val attachments = ArrayList<AttachmentDraft>(requested.size)
        for (item in requested) {
            val id = item.id
            val base =
                if (id != null) {
                    input.getOrNull(id)
                        // Dropping it instead would silently post without a file the user attached.
                        ?: return Result.Failure("Attachment id $id does not exist")
                } else {
                    // Unreachable in practice: a new file that could not be fetched has already failed
                    // the whole resolve, so the only way to be here without a hash is a bug.
                    val hash = hashes[item] ?: return Result.Failure("Attachment ${describe(item)} could not be added")
                    AttachmentDraft(hash, newFileName(item, hash), null, null, false, false, false, false, null, false)
                }
            attachments.add(apply(base, item))
        }
        return Result.Success(attachments)
    }

    /** [base] with the fields [item] set applied; the ones it left out are kept as they were. */
    private fun apply(
        base: AttachmentDraft,
        item: Requested,
    ): AttachmentDraft {
        val removeFileName: Boolean
        val customName: Boolean
        val newname: String?
        if (item.hasFilename) {
            val filename = item.filename
            // The three flags behind the one field: no name at all, the file's own name, or a custom
            // one. See PostingFragment, which sends `optionCustomName && !optionRemoveFileName`.
            removeFileName = filename == null
            customName = filename != null && filename != base.name
            newname = if (customName) filename else base.newname
        } else {
            removeFileName = base.optionRemoveFileName
            customName = base.optionCustomName
            newname = base.newname
        }
        return AttachmentDraft(
            base.hash,
            base.name,
            newname,
            if (item.hasRating) item.rating else base.rating,
            item.uniqueHash ?: base.optionUniqueHash,
            item.removeMetadata ?: base.optionRemoveMetadata,
            removeFileName,
            item.spoiler ?: base.optionSpoiler,
            if (item.hasReencoding) item.reencoding else base.reencoding,
            customName,
        )
    }

    /**
     * What to call a file the script added. A `url` it was downloaded from usually ends in a usable
     * name, and one is worth digging for: the extension is how the board decides whether it takes the
     * file at all, and the content hash left over as a last resort has none.
     */
    private fun newFileName(
        item: Requested,
        hash: String,
    ): String {
        val name = item.name
        if (!name.isNullOrEmpty()) {
            return name
        }
        val url = item.url
        val segment = if (url != null) Uri.parse(url).lastPathSegment else null
        return if (!segment.isNullOrEmpty()) segment else hash
    }

    /** The name an attachment is sent under, or `null` when its file name is dropped. */
    private fun currentFilename(attachment: AttachmentDraft): String? =
        when {
            attachment.optionRemoveFileName -> null
            attachment.optionCustomName -> attachment.newname
            else -> attachment.name
        }

    /**
     * Writes a new attachment's bytes to a temporary file, which is what [DraftsStorage.store] takes.
     * Runs off the main thread. Throws with a message meant for the user when the bytes can't be had.
     */
    private fun fetch(item: Requested): File {
        val data = item.data
        val url = item.url
        val bytes =
            when {
                data != null -> Base64.decode(stripDataUrlPrefix(data), Base64.DEFAULT)
                url != null -> download(url)
                else -> error("has no data or url")
            }
        require(bytes.isNotEmpty()) { "is empty" }
        val file =
            CacheManager.getInstance().getInternalCacheFile(TEMP_FILE_PREFIX + System.nanoTime())
                ?: error("could not be written")
        return try {
            FileOutputStream(file).use { it.write(bytes) }
            file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** Downloads a new attachment's bytes. Runs off the main thread. Mirrors [CommandLibraries]. */
    private fun download(url: String): ByteArray {
        val chan = Chan.getFallback()
        val uri = chan.locator.setSchemeIfEmpty(Uri.parse(url), null) ?: Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "address is not HTTP: $url" }
        val holder = HttpHolder(chan)
        return holder.use().use {
            HttpRequest(uri, holder).perform()?.readBytes() ?: error("empty response: $url")
        }
    }

    /** Drops the `data:image/png;base64,` a script that built a data URL would have left on. */
    private fun stripDataUrlPrefix(data: String): String {
        if (data.startsWith("data:")) {
            val comma = data.indexOf(',')
            if (comma >= 0) {
                return data.substring(comma + 1)
            }
        }
        return data
    }

    /** How a new attachment is named in a failure message, before it has a name of its own. */
    private fun describe(item: Requested): String = "\"${item.name ?: item.url ?: "?"}\""

    private fun reencodingToJson(reencoding: GraphicsUtils.Reencoding?): JSONObject? {
        reencoding ?: return null
        val obj = JSONObject()
        obj.put(KEY_REENCODING_FORMAT, reencoding.format)
        obj.put(KEY_REENCODING_QUALITY, reencoding.quality)
        obj.put(KEY_REENCODING_REDUCE, reencoding.reduce)
        return obj
    }

    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_SIZE = "size"
    private const val KEY_DATA = "data"
    private const val KEY_URL = "url"
    private const val KEY_FILENAME = "filename"
    private const val KEY_RATING = "rating"
    private const val KEY_SPOILER = "spoiler"
    private const val KEY_UNIQUE_HASH = "uniqueHash"
    private const val KEY_REMOVE_METADATA = "removeMetadata"
    private const val KEY_REENCODING = "reencoding"
    private const val KEY_REENCODING_FORMAT = "format"
    private const val KEY_REENCODING_QUALITY = "quality"
    private const val KEY_REENCODING_REDUCE = "reduce"

    private const val TEMP_FILE_PREFIX = "command_attachment_"
}
