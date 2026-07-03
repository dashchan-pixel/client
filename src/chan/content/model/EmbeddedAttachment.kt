package chan.content.model

import android.net.Uri
import chan.annotation.Public
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.net.EmbeddedType

@Public
class EmbeddedAttachment : Attachment {
	@Public
	enum class ContentType {
		@Public AUDIO,
		@Public VIDEO
	}

	internal val embedded: Post.Attachment.Embedded

	private constructor(embedded: Post.Attachment.Embedded) {
		this.embedded = embedded
	}

	@Public
	constructor(fileUri: Uri?, thumbnailUri: Uri?, embeddedType: String?, contentType: ContentType?,
			canDownload: Boolean, forcedName: String?) {
		val embeddedContentType = when (contentType) {
			ContentType.AUDIO -> Post.Attachment.Embedded.ContentType.AUDIO
			ContentType.VIDEO -> Post.Attachment.Embedded.ContentType.VIDEO
			else -> null
		}
		embedded = Post.Attachment.Embedded.createExternal(true, fileUri, thumbnailUri, embeddedType,
				embeddedContentType, canDownload, forcedName)!!
	}

	@Public
	fun getFileUri(): Uri? = embedded.fileUri

	@Public
	fun getThumbnailUri(): Uri? = embedded.thumbnailUri

	@Public
	fun getEmbeddedType(): String? = embedded.embeddedType

	@Public
	fun getContentType(): ContentType? = when (embedded.contentType) {
		Post.Attachment.Embedded.ContentType.AUDIO -> ContentType.AUDIO
		Post.Attachment.Embedded.ContentType.VIDEO -> ContentType.VIDEO
		else -> null
	}

	@Public
	fun isCanDownload(): Boolean = embedded.canDownload

	@Public
	fun getForcedName(): String? = embedded.forcedName

	companion object {
		@Public
		@JvmStatic
		fun obtain(data: String?): EmbeddedAttachment? {
			val embedded = EmbeddedType.extractAttachment(data)
			return if (embedded != null) EmbeddedAttachment(embedded) else null
		}
	}
}
