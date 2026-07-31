package com.mishiranu.dashchan.content

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import chan.content.Chan
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.service.AudioPlayerService
import com.mishiranu.dashchan.content.storage.DraftsStorage
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.widget.ClickableToast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Opens a file attached to a post draft in the viewers the app already has: the gallery for images and
 * videos, the audio player service for music, an external app for anything else.
 *
 * Those viewers all take a uri and read the file the cache maps it to, so a draft attachment is given
 * a [SCHEME] uri whose last path segment is the draft's own file name — which is what the gallery goes
 * by to tell an image from a video — and the stored file is linked into the media cache under the key
 * of that uri, the way a finished download is. Nothing then tries to fetch it: to every viewer the
 * file is simply already cached.
 */
object DraftAttachmentMedia {
    private const val SCHEME = "draft"

    fun isVideo(name: String?): Boolean = Chan.getFallback().locator.isVideoExtension(name)

    fun isAudio(name: String?): Boolean = Chan.getFallback().locator.isAudioExtension(name)

    /**
     * True for the kinds [open] has somewhere to send: a picture, a video, a track. Anything else is a
     * file the app can only hand to another app, which is not worth offering as a preview.
     */
    fun canOpen(name: String?): Boolean = Chan.getFallback().locator.isImageExtension(name) || isVideo(name) || isAudio(name)

    /**
     * Shows the attachment stored under [hash] with [name] as its file name, toasting the reason when
     * the file is gone or there is nothing to show it in.
     */
    fun open(
        context: Context,
        hash: String?,
        name: String?,
    ) {
        if (hash == null || name == null) {
            return
        }
        val uri = createUri(hash, name)
        val file = prepareCachedFile(hash, uri)
        if (file == null) {
            ClickableToast.show(R.string.cache_is_unavailable)
            return
        }
        val locator = Chan.getFallback().locator
        if (isAudio(name) && Preferences.isUsePlayer) {
            // No chan name: the file is the user's own, so there is no forum to credit it to.
            AudioPlayerService.start(context, null, uri, name)
        } else if (locator.isImageExtension(name) ||
            (isVideo(name) && NavigationUtils.isOpenableVideoPath(name))
        ) {
            NavigationUtils.openImageVideo(context, uri)
        } else {
            // A video or a track the built-in player is turned off for, or a type the app has no
            // viewer for.
            NavigationUtils.openFileExternal(context, file, name)
        }
    }

    /**
     * The uri a draft attachment is known by. Both parts matter: the hash keeps the cache key unique
     * across attachments that share a file name, and the file name is the last path segment because
     * that is where the gallery reads the extension from.
     */
    private fun createUri(
        hash: String,
        name: String,
    ): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .path("/" + hash + "/" + name.substringAfterLast('/'))
            .build()

    /**
     * Puts the stored draft file where the cache expects the content of [uri], so the viewers find it
     * without downloading anything, and returns it.
     *
     * A hard link rather than a copy: an attachment can be a video of tens of megabytes, and the two
     * names then share the same bytes. The link is a media cache entry like any other afterwards — it
     * may be cleaned up while the draft lives on, and the next open links it again.
     */
    private fun prepareCachedFile(
        hash: String,
        uri: Uri,
    ): File? {
        val draftFile = DraftsStorage.getInstance().getAttachmentDraftStoredFile(hash) ?: return null
        val cacheManager = CacheManager.getInstance()
        val cachedFile = cacheManager.getMediaFile(uri, true) ?: return null
        if (cachedFile.isFile && cachedFile.length() == draftFile.length()) {
            return cachedFile
        }
        cachedFile.delete()
        try {
            Os.link(draftFile.path, cachedFile.path)
        } catch (e: ErrnoException) {
            e.printStackTrace()
            if (!copyFile(draftFile, cachedFile)) {
                cachedFile.delete()
                return null
            }
        }
        // Registers the file with the cache the way a completed download does, so its size is
        // accounted for and it is cleaned up in turn.
        cacheManager.handleDownloadedFile(cachedFile, true)
        return cachedFile
    }

    private fun copyFile(
        from: File,
        to: File,
    ): Boolean =
        try {
            FileInputStream(from).use { input ->
                FileOutputStream(to).use { output ->
                    IOUtils.copyStream(input, output)
                }
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
}
