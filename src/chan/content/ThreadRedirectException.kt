package chan.content

import chan.annotation.Public
import com.mishiranu.dashchan.content.model.PostNumber

// TODO CHAN
// Remove this class after updating
// alphachan anonfm chuckdfwk diochan exach ponychan
// Added: 13.10.16 14:55
@Public
class ThreadRedirectException @Public constructor(private val boardName: String?,
		private val threadNumber: String?, private val postNumber: String?) : Exception() {
	init {
		PostNumber.validateThreadNumber(threadNumber, false)
	}

	@Public
	constructor(threadNumber: String?, postNumber: String?) : this(null, threadNumber, postNumber)

	@Throws(ExtensionException::class)
	fun obtainTarget(chanName: String?, boardName: String?): RedirectException.Target {
		return RedirectException.toThread(this.boardName ?: boardName,
				threadNumber, postNumber).obtainTarget(chanName)!!
	}
}
