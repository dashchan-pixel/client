package chan.content

import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.widget.ClickableToast

class ExtensionException(
    throwable: Throwable?,
) : Exception(throwable),
    ErrorItem.Holder {
    override fun getErrorItemAndHandle(): ErrorItem {
        logException(cause, false)
        return ErrorItem(ErrorItem.Type.EXTENSION)
    }

    companion object {
        @JvmStatic
        fun logException(
            t: Throwable?,
            showToast: Boolean,
        ) {
            if (t is LinkageError || t is RuntimeException) {
                t.printStackTrace()
                if (showToast) {
                    ClickableToast.show(ErrorItem(ErrorItem.Type.EXTENSION))
                }
            }
        }
    }
}
