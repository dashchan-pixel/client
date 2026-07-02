package com.mishiranu.dashchan.text.style

import android.graphics.Color
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import chan.util.StringUtils
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.graphics.ColorScheme
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.widget.CommentTextView

class LinkSpan(uriString: String?, @JvmField val postNumber: PostNumber?) :
		CharacterStyle(), UpdateAppearance, CommentTextView.ClickableSpan, ColorScheme.Span {
	@JvmField val uriString: String? = StringUtils.fixParsedUriString(uriString)

	private var foregroundColor = 0
	private var clickedColor = 0

	private var clicked = false
	private var hidden = false

	override fun applyColorScheme(colorScheme: ColorScheme?) {
		if (colorScheme != null) {
			foregroundColor = colorScheme.linkColor
			clickedColor = colorScheme.clickedColor
		}
	}

	override fun updateDrawState(paint: TextPaint) {
		if (paint.color != Color.TRANSPARENT) {
			if (hidden) {
				paint.color = foregroundColor and 0x00ffffff or
						Color.argb(Color.alpha(foregroundColor) / 2, 0, 0, 0)
				paint.isStrikeThruText = true
			} else {
				paint.color = foregroundColor
			}
			paint.isUnderlineText = true
			if (clicked) {
				paint.bgColor = if (Color.alpha(paint.bgColor) == 0x00) clickedColor
				else GraphicsUtils.mixColors(paint.bgColor, clickedColor)
			}
		}
	}

	override fun setClicked(clicked: Boolean) {
		this.clicked = clicked
	}

	fun setHidden(hidden: Boolean) {
		this.hidden = hidden
	}

	fun inBoardLink(): Boolean {
		// >>NUMBER links cannot refer to another board
		// This check can be useful for threads moved to another board
		return postNumber != null
	}
}
