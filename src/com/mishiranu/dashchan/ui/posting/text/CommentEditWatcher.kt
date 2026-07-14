package com.mishiranu.dashchan.ui.posting.text

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import chan.content.ChanConfiguration
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ErrorEditTextSetter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import kotlin.math.max
import kotlin.math.min

class CommentEditWatcher(
    private var postingConfiguration: ChanConfiguration.Posting?,
    private val commentView: EditText,
    private val remainingCharacters: TextView,
    private val layoutCallback: Runnable,
    private val storeDraftCallback: Runnable,
) : TextWatcher {
    fun updateConfiguration(posting: ChanConfiguration.Posting?) {
        this.postingConfiguration = posting
    }

    private var show = false
    private var error = false

    private var errorSetter: ErrorEditTextSetter? = null

    private var encoder: CharsetEncoder? = null
    private var encoderReady = false
    private var byteBuffer: ByteBuffer? = null

    override fun beforeTextChanged(
        s: CharSequence?,
        start: Int,
        count: Int,
        after: Int,
    ) {}

    override fun onTextChanged(
        s: CharSequence,
        start: Int,
        before: Int,
        count: Int,
    ) {
        var length = 0
        var maxCommentLength = 0
        var show = false
        val postingConfiguration = this.postingConfiguration
        if (postingConfiguration != null) {
            maxCommentLength = postingConfiguration.maxCommentLength
            val threshold = min(maxCommentLength / 2, 1000)
            length = s.length
            if (!encoderReady) {
                encoderReady = true
                val encoding = postingConfiguration.maxCommentLengthEncoding
                if (encoding != null) {
                    try {
                        encoder = Charset.forName(encoding).newEncoder()
                    } catch (e: Exception) {
                        // Ignore encoding exceptions
                    }
                }
            }
            val encoder = this.encoder
            if (encoder != null) {
                val capacity = (max(100, length) * encoder.maxBytesPerChar()).toInt()
                var byteBuffer = this.byteBuffer
                if (byteBuffer == null || byteBuffer.capacity() < capacity) {
                    byteBuffer = ByteBuffer.allocate(4 * capacity)
                    this.byteBuffer = byteBuffer
                } else {
                    byteBuffer.rewind()
                }
                encoder.reset()
                encoder.encode(CharBuffer.wrap(s), byteBuffer, true)
                length = byteBuffer.position()
            }
            show = threshold > 0 && length >= threshold
        }
        val error = show && length > maxCommentLength
        if (this.show != show) {
            remainingCharacters.visibility = if (show) View.VISIBLE else View.GONE
            layoutCallback.run()
            this.show = show
        }
        if (this.error != error || remainingCharacters.text.length == 0) {
            val color =
                ResourceUtils.getColor(
                    commentView.context,
                    if (error) {
                        R.attr.colorTextError
                    } else {
                        android.R.attr.textColorSecondary
                    },
                )
            remainingCharacters.setTextColor(color)
            if (errorSetter == null) {
                errorSetter = ErrorEditTextSetter(commentView)
            }
            errorSetter!!.setError(error)
            this.error = error
        }
        if (show) {
            remainingCharacters.text = "$length / $maxCommentLength"
        }
        if (before == 0 && count == 1) {
            val c = s[start]
            if (c == '\n' || c == '.') {
                storeDraftCallback.run()
            }
        }
    }

    override fun afterTextChanged(s: Editable?) {}
}
