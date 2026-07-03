package com.mishiranu.dashchan.ui.gallery

import android.view.View
import android.widget.FrameLayout
import com.mishiranu.dashchan.content.ImageLoader
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.graphics.DecoderDrawable
import com.mishiranu.dashchan.graphics.SimpleBitmapDrawable
import com.mishiranu.dashchan.media.AnimatedImageDecoder
import com.mishiranu.dashchan.media.JpegData
import com.mishiranu.dashchan.widget.CircularProgressBar
import com.mishiranu.dashchan.widget.PhotoView
import com.mishiranu.dashchan.widget.ViewFactory

class PagerInstance(@JvmField val galleryInstance: GalleryInstance, @JvmField val callback: Callback) {
	enum class LoadState { PREVIEW_OR_LOADING, COMPLETE, ERROR }

	class MediaSummary(@JvmField var width: Int, @JvmField var height: Int, @JvmField var size: Long) {
		constructor(galleryItem: GalleryItem) :
				this(galleryItem.width, galleryItem.height, galleryItem.size.toLong())

		fun updateDimensions(width: Int, height: Int): Boolean {
			if (width > 0 && height > 0 && (this.width != width || this.height != height)) {
				this.width = width
				this.height = height
				return true
			}
			return false
		}

		fun updateSize(size: Long): Boolean {
			if (size > 0 && this.size != size) {
				this.size = size
				return true
			}
			return false
		}
	}

	@JvmField var scrollingLeft = false

	@JvmField var leftHolder: ViewHolder? = null
	@JvmField var currentHolder: ViewHolder? = null
	@JvmField var rightHolder: ViewHolder? = null

	class ViewHolder {
		@JvmField var galleryItem: GalleryItem? = null
		@JvmField var mediaSummary: MediaSummary? = null
		@JvmField var photoView: PhotoView? = null
		@JvmField var surfaceParent: FrameLayout? = null
		@JvmField var progressBar: CircularProgressBar? = null
		@JvmField var playButton: View? = null
		@JvmField var errorHolder: ViewFactory.ErrorHolder? = null

		@JvmField var simpleBitmapDrawable: SimpleBitmapDrawable? = null
		@JvmField var decoderDrawable: DecoderDrawable? = null
		@JvmField var animatedImageDecoder: AnimatedImageDecoder? = null
		@JvmField var jpegData: JpegData? = null
		@JvmField var photoViewThumbnail = false
		@JvmField var thumbnailTarget: ImageLoader.Target? = null

		@JvmField var loadState = LoadState.PREVIEW_OR_LOADING
		@JvmField var decodeBitmapTask: Any? = null

		fun recyclePhotoView() {
			photoView!!.recycle()
			simpleBitmapDrawable?.let {
				it.recycle()
				simpleBitmapDrawable = null
			}
			decoderDrawable?.let {
				it.recycle()
				decoderDrawable = null
			}
			animatedImageDecoder?.let {
				it.recycle()
				animatedImageDecoder = null
			}
			jpegData = null
			photoViewThumbnail = false
		}
	}

	fun interface Callback {
		fun showError(holder: ViewHolder, message: String?)
	}
}
