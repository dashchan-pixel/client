package chan.content.model

import android.net.Uri
import chan.annotation.Public
import chan.content.ChanLocator
import chan.util.StringUtils

@Public
class Icon internal constructor(internal val uri: Uri?, title: String?) {
	private val title: String? = StringUtils.nullIfEmpty(title)

	@Public
	constructor(locator: ChanLocator, uri: Uri?, title: String?) :
			this(if (uri != null) locator.makeRelative(uri) else null, title)

	@Public
	fun getUri(locator: ChanLocator): Uri? = uri?.let { locator.convert(it) }

	@Public
	fun getTitle(): String? = title
}
