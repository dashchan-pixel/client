package com.mishiranu.dashchan.widget

import android.annotation.TargetApi
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.MimeTypeFilter
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import com.mishiranu.dashchan.R

class UriPasteEditText : SafePasteEditText, ActionMode.Callback {
	private var callback: Callback? = null
	private var allowedUriMimeTypes: Array<String>? = null
	private var actionMode: ActionMode? = null
	private var actionModeCallbackDelegate: ActionMode.Callback? = null

	fun interface Callback {
		fun onUriWithAllowedMimeTypePasted(uri: Uri)
	}

	constructor(context: Context) : super(context)

	constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

	constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
			super(context, attrs, defStyleAttr, defStyleRes)

	fun setCallback(callback: Callback, allowedUriMimeTypes: List<String>) {
		this.callback = callback
		this.allowedUriMimeTypes = allowedUriMimeTypes.toTypedArray()
	}

	override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
		var inputConnection = super.onCreateInputConnection(outAttrs)
		if (uriPasteAllowed()) {
			EditorInfoCompat.setContentMimeTypes(outAttrs, allowedUriMimeTypes)

			val cb = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, opts ->
				inputContentInfo.requestPermission()
				callback!!.onUriWithAllowedMimeTypePasted(inputContentInfo.contentUri)
				inputContentInfo.releasePermission()
				ClickableToast.show(R.string.pasted)
				true
			}

			inputConnection = InputConnectionCompat.createWrapper(inputConnection, outAttrs, cb)
		}
		return inputConnection
	}

	override fun onTextContextMenuItem(id: Int): Boolean {
		if (id == android.R.id.paste && uriPasteAllowed()) {
			val uriFromClipboard = getUriFromClipboard()
			if (uriFromClipboard != null && uriMimeTypeAllowed(uriFromClipboard)) {
				callback!!.onUriWithAllowedMimeTypePasted(uriFromClipboard)
				cancelActionMode()
				return true
			}
		}
		return super.onTextContextMenuItem(id)
	}

	private fun uriPasteAllowed(): Boolean {
		return callback != null && allowedUriMimeTypes.let { it != null && it.isNotEmpty() }
	}

	private fun getUriFromClipboard(): Uri? {
		val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		val clip = clipboardManager.primaryClip
		return if (clip != null) clip.getItemAt(0).uri else null
	}

	private fun uriMimeTypeAllowed(uri: Uri): Boolean {
		val contentResolver = context.contentResolver
		val uriMimeType = contentResolver.getType(uri)
		return MimeTypeFilter.matches(uriMimeType, allowedUriMimeTypes!!) != null
	}

	private fun cancelActionMode() {
		actionMode?.finish()
	}

	override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
		actionModeCallbackDelegate = callback
		actionMode = super.startActionMode(this)
		return actionMode
	}

	override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
		actionModeCallbackDelegate = callback
		actionMode = super.startActionMode(this, type)
		return actionMode
	}

	override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
		return actionModeCallbackDelegate!!.onCreateActionMode(mode, menu)
	}

	override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
		return actionModeCallbackDelegate!!.onPrepareActionMode(mode, menu)
	}

	override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
		return actionModeCallbackDelegate!!.onActionItemClicked(mode, item)
	}

	override fun onDestroyActionMode(mode: ActionMode?) {
		actionModeCallbackDelegate!!.onDestroyActionMode(mode)
		actionMode = null
		actionModeCallbackDelegate = null
	}
}
