package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import chan.content.ChanManager
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.CursorAdapter
import com.mishiranu.dashchan.widget.ViewFactory

class CookiesFragment : BaseListFragment, FragmentHandler.Callback {
	constructor()

	constructor(chanName: String) {
		val args = Bundle()
		args.putCharSequence(EXTRA_CHAN_NAME, chanName)
		arguments = args
	}

	private fun getChanName(): String = requireArguments().getString(EXTRA_CHAN_NAME)!!

	override fun onActivityCreated(savedInstanceState: Bundle?) {
		super.onActivityCreated(savedInstanceState)

		(requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.manage_cookies), null)
		getRecyclerView()!!.adapter = Adapter(this::onCookieClick)
		updateCursor()
	}

	override fun onDestroyView() {
		(getRecyclerView()!!.adapter as Adapter).setCursor(null)
		super.onDestroyView()
	}

	override fun onResume() {
		super.onResume()

		if (!ChanManager.getInstance().isExistingChanName(getChanName())) {
			(requireActivity() as FragmentHandler).removeFragment()
		}
	}

	override fun onChansChanged(changed: Collection<String>, removed: Collection<String>) {
		if (removed.contains(getChanName())) {
			(requireActivity() as FragmentHandler).removeFragment()
		}
	}

	private fun onCookieClick(cookieItem: ChanDatabase.CookieItem) {
		val dialog = ActionDialog(cookieItem.name, cookieItem.blocked, cookieItem.deleteOnExit)
		dialog.show(childFragmentManager, ActionDialog.TAG)
	}

	internal fun removeCookie(cookie: String?) {
		ChanDatabase.getInstance().setCookie(getChanName(), cookie!!, null, null)
		if (!updateCursor()) {
			(requireActivity() as FragmentHandler).removeFragment()
		}
	}

	internal fun setCookieState(cookie: String?, blocked: Boolean?, deleteOnExit: Boolean?) {
		ChanDatabase.getInstance().setCookieState(getChanName(), cookie!!, blocked, deleteOnExit)
		if (!updateCursor()) {
			(requireActivity() as FragmentHandler).removeFragment()
		}
	}

	private fun updateCursor(): Boolean {
		val adapter = getRecyclerView()!!.adapter as Adapter
		adapter.setCursor(ChanDatabase.getInstance().getCookies(getChanName()))
		return adapter.itemCount > 0
	}

	private class Adapter(private val callback: Callback) :
			CursorAdapter<ChanDatabase.CookieCursor, Adapter.ViewHolder>(),
			ListViewUtils.ClickCallback<Void, Adapter.ViewHolder> {
		fun interface Callback {
			fun onCookieClick(cookieItem: ChanDatabase.CookieItem)
		}

		private class ViewHolder(viewHolder: ViewFactory.TwoLinesViewHolder) :
				RecyclerView.ViewHolder(viewHolder.view) {
			val blocked = ImageView(itemView.context)
			val deleteOnExit = ImageView(itemView.context)
			val title: TextView = viewHolder.text1
			val value: TextView = viewHolder.text2

			init {
				val parent = title.parent as ViewGroup
				val index = parent.indexOfChild(title)
				parent.removeView(title)
				val titleLayout = LinearLayout(parent.context)
				titleLayout.orientation = LinearLayout.HORIZONTAL
				titleLayout.gravity = Gravity.CENTER_VERTICAL
				parent.addView(titleLayout, index, title.layoutParams)
				titleLayout.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
						LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

				val density = ResourceUtils.obtainDensity(parent)
				val size = (12f * density + 0.5f).toInt()
				val top = (1f * density + 0.5f).toInt()
				val margin = (6f * density + 0.5f).toInt()
				blocked.setImageDrawable(ResourceUtils.getDrawable(parent.context, R.attr.iconPostClosed, 0))
				deleteOnExit.setImageDrawable(ResourceUtils.getDrawable(parent.context, R.attr.iconPostBanned, 0))
				blocked.imageTintList = title.textColors
				deleteOnExit.imageTintList = title.textColors

				titleLayout.addView(blocked, size, size)
				ViewUtils.setNewMarginRelative(blocked, margin, top, 0, 0)
				titleLayout.addView(deleteOnExit, size, size)
				ViewUtils.setNewMarginRelative(deleteOnExit, margin, top, 0, 0)
			}
		}

		private val cookieItem = ChanDatabase.CookieItem()

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			return ListViewUtils.bind(ViewHolder(ViewFactory.makeTwoLinesListItem(parent, 0)), false, null, this)
		}

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val cookieItem = this.cookieItem.update(moveTo(position))
			holder.blocked.visibility = if (cookieItem.blocked) View.VISIBLE else View.GONE
			holder.deleteOnExit.visibility = if (cookieItem.deleteOnExit) View.VISIBLE else View.GONE
			holder.title.text = if (StringUtils.isEmpty(cookieItem.title)) cookieItem.name else cookieItem.title
			holder.value.text = cookieItem.value
			holder.value.visibility = if (StringUtils.isEmpty(cookieItem.value)) View.GONE else View.VISIBLE
			holder.blocked.isEnabled = !cookieItem.blocked
			holder.deleteOnExit.isEnabled = !cookieItem.blocked
			holder.title.isEnabled = !cookieItem.blocked
			holder.value.isEnabled = !cookieItem.blocked
		}

		override fun onItemClick(holder: ViewHolder, position: Int, item: Void?, longClick: Boolean): Boolean {
			callback.onCookieClick(cookieItem.update(moveTo(position)).copy())
			return true
		}
	}

	class ActionDialog : DialogFragment {
		constructor()

		constructor(cookie: String?, blocked: Boolean, deleteOnExit: Boolean) {
			val args = Bundle()
			args.putString(EXTRA_COOKIE, cookie)
			args.putBoolean(EXTRA_BLOCKED, blocked)
			args.putBoolean(EXTRA_DELETE_ON_EXIT, deleteOnExit)
			arguments = args
		}

		override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
			val args = requireArguments()
			val blocked = args.getBoolean(EXTRA_BLOCKED)
			val deleteOnExit = args.getBoolean(EXTRA_DELETE_ON_EXIT)
			val dialogMenu = DialogMenu(requireContext())
			dialogMenu.addCheck(R.string.block, blocked) {
				(parentFragment as CookiesFragment).setCookieState(args.getString(EXTRA_COOKIE), !blocked, null)
			}
			dialogMenu.addCheck(R.string.delete_on_exit, deleteOnExit) {
				(parentFragment as CookiesFragment).setCookieState(args.getString(EXTRA_COOKIE), null, !deleteOnExit)
			}
			if (!blocked && !deleteOnExit) {
				dialogMenu.add(R.string.delete) {
					(parentFragment as CookiesFragment).removeCookie(args.getString(EXTRA_COOKIE))
				}
			}
			return dialogMenu.create()
		}

		companion object {
			val TAG: String = ActionDialog::class.java.name

			private const val EXTRA_COOKIE = "cookie"
			private const val EXTRA_BLOCKED = "blocked"
			private const val EXTRA_DELETE_ON_EXIT = "deleteOnExit"
		}
	}

	companion object {
		private const val EXTRA_CHAN_NAME = "chanName"
	}
}
