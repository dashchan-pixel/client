package com.mishiranu.dashchan.content

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import chan.content.Chan
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.content.storage.DraftsStorage
import com.mishiranu.dashchan.ui.MainActivity
import java.util.regex.Pattern

class PostingShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val draftsStorage = DraftsStorage.getInstance()
        var uris: ArrayList<Uri>? = null
        var contentUri: Uri? = null
        var sharedText: String? = null
        val intent = intent

        if (Intent.ACTION_SEND == intent.action) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri != null) {
                uris = ArrayList(1)
                uris.add(uri)
            } else {
                val text =
                    StringUtils.emptyIfNull(intent.getStringExtra(Intent.EXTRA_SUBJECT)) + '\n' +
                        StringUtils.emptyIfNull(intent.getStringExtra(Intent.EXTRA_TEXT))
                sharedText = StringUtils.nullIfEmpty(text.trim { it <= ' ' })
                // linkify() only returns null for a null argument; text is a non-null concatenation.
                val matcher = PATTERN_HREF.matcher(StringUtils.linkify(text).orEmpty())
                while (matcher.find()) {
                    val uriCandidate = Uri.parse(matcher.group(2))
                    // Only hand the address over to the navigator when it belongs to a known chan;
                    // anything else is worth more as draft text than as an "unknown address" toast.
                    if (Chan.getPreferred(null, uriCandidate).name != null) {
                        contentUri = uriCandidate
                        break
                    }
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == intent.action) {
            uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        }

        var success = 0
        if (!uris.isNullOrEmpty()) {
            for (uri in uris) {
                val fileHolder = FileHolder.obtain(uri)
                if (fileHolder != null && draftsStorage.storeFuture(fileHolder)) {
                    success++
                }
            }
        }

        if (success > 0) {
            Toast.makeText(this, R.string.draft_saved, Toast.LENGTH_SHORT).show()
        } else if (contentUri != null) {
            startActivity(Intent(this, MainActivity::class.java).setData(contentUri))
        } else if (sharedText != null) {
            draftsStorage.storeFutureComment(sharedText)
            Toast.makeText(this, R.string.draft_saved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.unknown_address, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        private val PATTERN_HREF = Pattern.compile("<a .*href=([\"'])(.*?)\\1.*>")
    }
}
