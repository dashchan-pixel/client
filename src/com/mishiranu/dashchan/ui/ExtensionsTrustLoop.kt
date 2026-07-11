package com.mishiranu.dashchan.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.style.ReplacementSpan
import chan.content.ChanManager
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.text.style.MonospaceSpan
import java.lang.ref.WeakReference

object ExtensionsTrustLoop {
	class State {
		internal var currentDialog: WeakReference<AlertDialog>? = null
	}

	// Allows dots to break lines
	private class DotSpan : ReplacementSpan() {
		override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int,
				fontMetricsInt: Paint.FontMetricsInt?): Int {
			return (paint.measureText(".") + 0.5f).toInt()
		}

		override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int,
				x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
			canvas.drawText(".", x, y.toFloat(), paint)
		}
	}

	@JvmStatic
	fun handleUntrustedExtensions(context: Context, state: State) {
		state.currentDialog?.get()?.dismiss()
		val extensionItem = ChanManager.getInstance().firstUntrustedExtension
		if (extensionItem != null) {
			val message = SpannableStringBuilder()
			message.append(context.getString(R.string.allow_this_extension__sentence))
			message.append("\n\n")
			val packageName = SpannableStringBuilder(extensionItem.packageName)
			var index = -1
			while (true) {
				index = extensionItem.packageName.indexOf('.', index + 1)
				if (index < 0) {
					break
				}
				packageName.setSpan(DotSpan(), index, index + 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
			message.append("Package name:\n").append(packageName)
			message.append("\n\n")
			val fingerprints = SpannableStringBuilder()
			for (fingerprint in extensionItem.fingerprints.fingerprints) {
				if (fingerprints.isNotEmpty()) {
					fingerprints.append(" /")
				}
				for (i in 0 until fingerprint.length / 2) {
					if (fingerprints.isNotEmpty()) {
						fingerprints.append(' ')
					}
					fingerprints.append(Character.toUpperCase(fingerprint[i]))
					fingerprints.append(Character.toUpperCase(fingerprint[i + 1]))
				}
			}
			fingerprints.setSpan(MonospaceSpan(false), 0, fingerprints.length,
					SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
			message.append("SHA-256 fingerprint:\n").append(fingerprints)
			val dialog = AlertDialog.Builder(context)
					.setTitle(extensionItem.title).setMessage(message)
					.setCancelable(false)
					.setPositiveButton(android.R.string.ok) { _, _ ->
						ChanManager.getInstance().changeUntrustedExtensionState(extensionItem.name, true)
						handleUntrustedExtensions(context, state)
					}
					.setNegativeButton(android.R.string.cancel) { _, _ ->
						ChanManager.getInstance().changeUntrustedExtensionState(extensionItem.name, false)
						handleUntrustedExtensions(context, state)
					}
					.setNeutralButton(R.string.details) { _, _ ->
						context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
								.setData(Uri.parse("package:" + extensionItem.packageName)))
						handleUntrustedExtensions(context, state)
					}
					.show()
			state.currentDialog = WeakReference(dialog)
			dialog.setOnDismissListener {
				if (state.currentDialog?.get() === dialog) {
					state.currentDialog = null
				}
			}
		}
	}
}
