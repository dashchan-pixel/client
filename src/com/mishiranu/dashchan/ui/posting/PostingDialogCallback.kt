package com.mishiranu.dashchan.ui.posting

import android.util.Pair
import chan.content.ChanConfiguration

interface PostingDialogCallback {
	fun getAttachmentHolder(index: Int): AttachmentHolder?
	fun getAttachmentRatingItems(): List<Pair<String, String>>?
	fun getPostingConfiguration(): ChanConfiguration.Posting?
}
