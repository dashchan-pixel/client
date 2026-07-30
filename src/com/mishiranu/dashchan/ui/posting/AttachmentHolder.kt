package com.mishiranu.dashchan.ui.posting

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.mishiranu.dashchan.util.GraphicsUtils

class AttachmentHolder(
    @JvmField val view: View,
    @JvmField val fileName: TextView,
    @JvmField val fileSize: TextView,
    @JvmField val imageView: ImageView,
    @JvmField val warningButton: View,
    @JvmField val ratingButton: View,
    /** Covers the preview above the controls strip: opens the file, and is gone without a preview. */
    @JvmField val previewButton: View,
    /** The play or audio icon over a preview that stands for something to play. */
    @JvmField val previewBadge: ImageView,
) {
    @JvmField var hash: String? = null

    @JvmField var name: String? = null

    @JvmField var newname: String? = null

    @JvmField var rating: String? = null

    @JvmField var optionUniqueHash = false

    @JvmField var optionRemoveMetadata = false

    @JvmField var optionRemoveFileName = false

    @JvmField var optionSpoiler = false

    @JvmField var optionCustomName = false

    @JvmField var reencoding: GraphicsUtils.Reencoding? = null
}
