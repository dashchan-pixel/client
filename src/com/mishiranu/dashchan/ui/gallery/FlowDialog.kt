package com.mishiranu.dashchan.ui.gallery

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.ui.FragmentHandler

/**
 * A full-screen, vertically-swiped feed of a thread's video attachments — reels/TikTok-style.
 * Each page auto-plays and loops the video; only the centered page plays. Launched from the
 * thread menu's "Flow" item next to "Gallery".
 */
class FlowDialog : DialogFragment(), FlowVideoView.Callback {
	/** Retains the (non-Parcelable) item list across configuration changes. */
	class FlowViewModel : ViewModel() {
		var chan: Chan? = null
		var items: List<GalleryItem>? = null
		var threadTitle: String? = null
	}

	private lateinit var viewModel: FlowViewModel
	private var recyclerView: RecyclerView? = null
	private val snapHelper = PagerSnapHelper()
	private var currentPosition = -1
	private var bottomInset = 0

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
		viewModel = ViewModelProvider(this).get(FlowViewModel::class.java)
		if (viewModel.items == null) {
			viewModel.chan = pendingChan
			viewModel.items = pendingItems
			viewModel.threadTitle = pendingThreadTitle
		}
		pendingChan = null
		pendingItems = null
		pendingThreadTitle = null
		if (viewModel.items == null) {
			// Handoff lost (e.g. process death) — nothing to show.
			dismiss()
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
			savedInstanceState: Bundle?): View {
		val recyclerView = RecyclerView(requireContext())
		this.recyclerView = recyclerView
		recyclerView.setBackgroundColor(Color.BLACK)
		recyclerView.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
		recyclerView.setHasFixedSize(true)
		// Keep neighbours bound so the next page can be pre-buffered.
		recyclerView.setItemViewCacheSize(2)
		snapHelper.attachToRecyclerView(recyclerView)
		// Keep the control bar clear of the gesture nav bar: propagate the bottom inset to pages.
		ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
			bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
			for (i in 0 until (view as RecyclerView).childCount) {
				(view.getChildAt(i) as? FlowVideoView)?.setBottomInset(bottomInset)
			}
			insets
		}
		// Pause any page as soon as it leaves the screen. Necessary because RecyclerView keeps
		// recently-detached views in its cache (without recycling them), so their players would
		// otherwise keep playing off-screen.
		recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
			override fun onChildViewAttachedToWindow(view: View) {}
			override fun onChildViewDetachedFromWindow(view: View) {
				(view as? FlowVideoView)?.setActive(false)
			}
		})
		val chan = viewModel.chan
		val items = viewModel.items
		if (chan != null && items != null) {
			recyclerView.adapter = Adapter(chan, items)
			recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
				override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
					if (newState == RecyclerView.SCROLL_STATE_IDLE) {
						val layoutManager = rv.layoutManager as LinearLayoutManager
						val snapView = snapHelper.findSnapView(layoutManager)
						if (snapView != null) {
							updateActive(rv.getChildAdapterPosition(snapView))
						}
					}
				}
			})
			recyclerView.post { if (currentPosition < 0) updateActive(0) }
		}
		return recyclerView
	}

	override fun onStart() {
		super.onStart()
		dialog?.window?.apply {
			setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
			setBackgroundDrawable(ColorDrawable(Color.BLACK))
			// Draw edge-to-edge so the bottom inset is reported and the controls can clear the nav bar.
			WindowCompat.setDecorFitsSystemWindows(this, false)
		}
	}

	override fun onResume() {
		super.onResume()
		currentHolder()?.videoView?.setActive(true)
	}

	override fun onPause() {
		super.onPause()
		currentHolder()?.videoView?.setActive(false)
	}

	override fun onDestroyView() {
		super.onDestroyView()
		// Releases every player: setting a null adapter recycles all live holders.
		recyclerView?.adapter = null
		recyclerView = null
	}

	private fun currentHolder(): Holder? {
		return recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? Holder
	}

	private fun updateActive(position: Int) {
		if (position == RecyclerView.NO_POSITION || position == currentPosition) {
			return
		}
		val recyclerView = recyclerView ?: return
		(recyclerView.findViewHolderForAdapterPosition(currentPosition) as? Holder)?.videoView?.setActive(false)
		currentPosition = position
		(recyclerView.findViewHolderForAdapterPosition(position) as? Holder)?.videoView?.setActive(true)
		// Pre-buffer the adjacent pages so the next swipe plays instantly.
		preload(position + 1)
		preload(position - 1)
	}

	private fun preload(position: Int) {
		if (position < 0) {
			return
		}
		(recyclerView?.findViewHolderForAdapterPosition(position) as? Holder)?.videoView?.prepare()
	}

	private inner class Adapter(private val chan: Chan, private val items: List<GalleryItem>) :
			RecyclerView.Adapter<Holder>() {
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val videoView = FlowVideoView(parent.context)
			videoView.layoutParams = RecyclerView.LayoutParams(
					RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.MATCH_PARENT)
			return Holder(videoView)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) {
			holder.videoView.bind(chan, items[position], this@FlowDialog)
			holder.videoView.setBottomInset(bottomInset)
			if (position == currentPosition) {
				holder.videoView.setActive(true)
			} else if (position == currentPosition + 1 || position == currentPosition - 1) {
				// This neighbour bound after the active page settled (e.g. via prefetch) — buffer it.
				holder.videoView.prepare()
			}
		}

		override fun getItemCount(): Int = items.size

		override fun onViewRecycled(holder: Holder) {
			holder.videoView.recycle()
		}
	}

	private class Holder(val videoView: FlowVideoView) : RecyclerView.ViewHolder(videoView)

	// FlowVideoView.Callback

	override fun onGoToPost(galleryItem: GalleryItem) {
		val chan = viewModel.chan ?: return
		(activity as? FragmentHandler)?.scrollToPost(chan.name, galleryItem.boardName,
				galleryItem.threadNumber, galleryItem.postNumber)
		dismiss()
	}

	override fun getDownloadBinder(): DownloadService.Binder? {
		return (activity as? FragmentHandler)?.getDownloadBinder()
	}

	override fun getThreadTitle(): String? = viewModel.threadTitle

	companion object {
		private val TAG = FlowDialog::class.java.name
		private var pendingChan: Chan? = null
		private var pendingItems: List<GalleryItem>? = null
		private var pendingThreadTitle: String? = null

		@JvmStatic
		fun show(fragmentManager: FragmentManager, chan: Chan, items: List<GalleryItem>, threadTitle: String?) {
			if (items.isEmpty()) {
				return
			}
			(fragmentManager.findFragmentByTag(TAG) as? FlowDialog)?.dismiss()
			pendingChan = chan
			pendingItems = items
			pendingThreadTitle = threadTitle
			FlowDialog().show(fragmentManager, TAG)
		}
	}
}
