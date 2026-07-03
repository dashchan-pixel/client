package chan.content.model

import android.net.Uri
import chan.annotation.Public
import chan.content.ChanLocator
import chan.util.StringUtils

@Public
class FileAttachment @Public constructor() : Attachment {
	internal var fileUri: Uri? = null
		private set
	internal var thumbnailUri: Uri? = null
		private set
	private var originalName: String? = null

	private var size = 0
	private var width = 0
	private var height = 0
	private var spoiler = false

	@Public
	fun getFileUri(locator: ChanLocator): Uri? = locator.convert(locator.fixRelativeFileUri(fileUri))

	@Public
	fun setFileUri(locator: ChanLocator, fileUri: Uri?): FileAttachment {
		this.fileUri = if (fileUri != null) locator.makeRelative(fileUri) else null
		return this
	}

	@Public
	fun getThumbnailUri(locator: ChanLocator): Uri? {
		return locator.convert(locator.fixRelativeFileUri(thumbnailUri))
	}

	@Public
	fun setThumbnailUri(locator: ChanLocator, thumbnailUri: Uri?): FileAttachment {
		this.thumbnailUri = if (thumbnailUri != null) locator.makeRelative(thumbnailUri) else null
		return this
	}

	@Public
	fun getOriginalName(): String? = originalName

	@Public
	fun setOriginalName(originalName: String?): FileAttachment {
		this.originalName = StringUtils.nullIfEmpty(originalName)
		return this
	}

	@Public
	fun getSize(): Int = size

	@Public
	fun setSize(size: Int): FileAttachment {
		this.size = size
		return this
	}

	@Public
	fun getWidth(): Int = width

	@Public
	fun setWidth(width: Int): FileAttachment {
		this.width = width
		return this
	}

	@Public
	fun getHeight(): Int = height

	@Public
	fun setHeight(height: Int): FileAttachment {
		this.height = height
		return this
	}

	@Public
	fun isSpoiler(): Boolean = spoiler

	@Public
	fun setSpoiler(spoiler: Boolean): FileAttachment {
		this.spoiler = spoiler
		return this
	}
}
