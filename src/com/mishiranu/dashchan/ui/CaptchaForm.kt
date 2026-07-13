package com.mishiranu.dashchan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import androidx.core.os.ParcelCompat
import chan.content.ChanConfiguration
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences.isCaptchaTimer
import com.mishiranu.dashchan.content.Preferences.isHugeCaptcha
import com.mishiranu.dashchan.content.async.ReadCaptchaTask
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getColorStateList
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils.setTextSizeScaled
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

class CaptchaForm(
    private val callback: Callback,
    private val hideInput: Boolean,
    private val applyHeight: Boolean,
    container: View,
    private val inputParentView: View?,
    private val inputView: EditText,
    captcha: ChanConfiguration.Captcha
) : View.OnClickListener, OnLongClickListener, OnEditorActionListener {
    enum class CaptchaViewType {
        LOADING, IMAGE, SKIP, SKIP_LOCK, ERROR
    }

    private val blockParentView: View
    private val blockView: View
    private val skipBlockView: View
    private val skipTextView: TextView
    private val loadingView: View
    private val imageView: ImageView
    private val cancelView: ImageView
    private val lifetimeTimerView: TextView?

    private val captchaLifetimeTimerEnabled: Boolean
    private var captchaLifetimeSeconds = 0
    private var captchaImage: Bitmap? = null

    private var captchaInput: ChanConfiguration.Captcha.Input?

    interface Callback {
        fun onRefreshCaptcha(forceRefresh: Boolean)

        fun onConfirmCaptcha()

        fun onCaptchaLifetimeEnded()

        fun showCaptchaOptionsDialog(dialog: CaptchaOptionsDialog)
    }

    class Captcha : Parcelable {
        val image: Bitmap?
        private val lifetimeSeconds: Int
        val creationTimeMillis: Long

        constructor(image: Bitmap?, lifetimeSeconds: Int) {
            this.image = image
            this.lifetimeSeconds = max(0, lifetimeSeconds)
            creationTimeMillis = SystemClock.elapsedRealtime()
        }

        protected constructor(`in`: Parcel) {
            image = ParcelCompat.readParcelable<Bitmap?>(
                `in`,
                Bitmap::class.java.getClassLoader(),
                Bitmap::class.java
            )
            lifetimeSeconds = `in`.readInt()
            creationTimeMillis = `in`.readLong()
        }

        fun alive(): Boolean {
            if (hasLifetime()) {
                return this.remainingLifetimeSeconds > 0
            } else {
                return true
            }
        }

        internal val remainingLifetimeSeconds: Int
            get() {
                if (hasLifetime()) {
                    val now = SystemClock.elapsedRealtime()
                    val secondsPassedSinceCaptchaCreation =
                        TimeUnit.MILLISECONDS.toSeconds(now - creationTimeMillis)
                            .toInt()
                    return max(0, lifetimeSeconds - secondsPassedSinceCaptchaCreation)
                } else {
                    return 0
                }
            }

        fun hasLifetime(): Boolean {
            return lifetimeSeconds > 0
        }

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeParcelable(image, flags)
            dest.writeInt(lifetimeSeconds)
            dest.writeLong(creationTimeMillis)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<Captcha?> = object : Parcelable.Creator<Captcha?> {
                override fun createFromParcel(`in`: Parcel): Captcha {
                    return Captcha(`in`)
                }

                override fun newArray(size: Int): Array<Captcha?> {
                    return arrayOfNulls<Captcha>(size)
                }
            }
        }
    }

    private fun updateCaptchaInput(input: ChanConfiguration.Captcha.Input) {
        when (input) {
            ChanConfiguration.Captcha.Input.ALL -> {
                inputView.setInputType(InputType.TYPE_CLASS_TEXT)
            }

            ChanConfiguration.Captcha.Input.LATIN -> {
                inputView.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            }

            ChanConfiguration.Captcha.Input.NUMERIC -> {
                inputView.setInputType(InputType.TYPE_CLASS_NUMBER)
            }
        }
    }

    private fun updateCaptchaHeight(large: Boolean) {
        if (applyHeight) {
            val density = obtainDensity(inputView)
            val height = ((if (isHugeCaptcha || large) 96f else 48f) * density).toInt()
            val layoutParams = blockView.getLayoutParams()
            if (height != layoutParams.height) {
                layoutParams.height = height
                blockView.requestLayout()
            }
        }
    }

    override fun onClick(v: View) {
        if (v === cancelView) {
            callback.onRefreshCaptcha(true)
        } else if (v === blockParentView && v.isClickable()) {
            callback.onRefreshCaptcha(false)
        } else if (inputParentView != null && v === inputParentView) {
            inputView.requestFocus()
            val inputMethodManager = v.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun onLongClick(v: View?): Boolean {
        if (v === blockParentView) {
            if (captchaImage != null) {
                callback.showCaptchaOptionsDialog(CaptchaOptionsDialog(captchaImage!!))
            } else {
                callback.onRefreshCaptcha(true)
            }
            return true
        }
        return false
    }

    override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
        callback.onConfirmCaptcha()
        return true
    }

    fun showCaptcha(
        captchaState: ReadCaptchaTask.CaptchaState, input: ChanConfiguration.Captcha.Input?,
        captcha: Captcha?, large: Boolean, invertColors: Boolean
    ) {
        when (captchaState) {
            ReadCaptchaTask.CaptchaState.CAPTCHA -> {
                if (captcha != null) {
                    if (captchaLifetimeTimerEnabled && !captcha.alive()) {
                        callback.onCaptchaLifetimeEnded()
                    } else {
                        captchaImage = captcha.image
                        imageView.setImageBitmap(captcha.image)
                        imageView.setColorFilter(if (invertColors) GraphicsUtils.INVERT_FILTER else null)
                        captchaLifetimeSeconds = captcha.remainingLifetimeSeconds
                        switchToCaptchaView(CaptchaViewType.IMAGE, input, large)
                    }
                } else {
                    showError()
                }
            }

            ReadCaptchaTask.CaptchaState.NEED_LOAD, ReadCaptchaTask.CaptchaState.MAY_LOAD, ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING -> {
                skipTextView.setText(R.string.load_captcha)
                cancelView.setVisibility(
                    if (captchaState == ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING)
                        View.VISIBLE
                    else
                        View.GONE
                )
                switchToCaptchaView(CaptchaViewType.SKIP, null, false)
            }

            ReadCaptchaTask.CaptchaState.SKIP -> {
                skipTextView.setText(R.string.captcha_is_not_required)
                cancelView.setVisibility(View.VISIBLE)
                switchToCaptchaView(CaptchaViewType.SKIP_LOCK, null, false)
            }

            ReadCaptchaTask.CaptchaState.PASS -> {
                skipTextView.setText(R.string.captcha_pass)
                cancelView.setVisibility(View.VISIBLE)
                switchToCaptchaView(CaptchaViewType.SKIP, null, false)
            }
        }
    }

    fun showError() {
        imageView.setImageResource(android.R.color.transparent)
        skipTextView.setText(R.string.load_captcha)
        cancelView.setVisibility(View.GONE)
        switchToCaptchaView(CaptchaViewType.ERROR, null, false)
    }

    fun showLoading() {
        inputView.setText(null)
        switchToCaptchaView(CaptchaViewType.LOADING, null, false)
    }

    fun setText(text: String?) {
        inputView.setText(text)
    }

    val input: String
        get() = inputView.getText().toString()

    private fun setInputEnabled(enabled: Boolean, switchVisibility: Boolean) {
        inputView.setEnabled(enabled)
        if (hideInput && switchVisibility) {
            inputView.setVisibility(if (enabled) View.VISIBLE else View.GONE)
        }
    }

    private fun switchToCaptchaView(
        captchaViewType: CaptchaViewType,
        input: ChanConfiguration.Captcha.Input?, large: Boolean
    ) {
        if (captchaViewType != CaptchaViewType.IMAGE) {
            hideCaptchaLifetimeTimer()
            stopCaptchaLifetimeTimer()
            captchaImage = null
        }

        when (captchaViewType) {
            CaptchaViewType.LOADING -> {
                blockParentView.setClickable(true)
                blockView.setVisibility(View.VISIBLE)
                imageView.setVisibility(View.GONE)
                imageView.setImageResource(android.R.color.transparent)
                loadingView.setVisibility(View.VISIBLE)
                skipBlockView.setVisibility(View.GONE)
                setInputEnabled(false, false)
                updateCaptchaHeight(false)
            }

            CaptchaViewType.ERROR -> {
                blockParentView.setClickable(true)
                blockView.setVisibility(View.VISIBLE)
                imageView.setVisibility(View.VISIBLE)
                loadingView.setVisibility(View.GONE)
                skipBlockView.setVisibility(View.VISIBLE)
                setInputEnabled(false, false)
                updateCaptchaHeight(false)
            }

            CaptchaViewType.IMAGE -> {
                blockParentView.setClickable(true)
                blockView.setVisibility(View.VISIBLE)
                imageView.setVisibility(View.VISIBLE)
                loadingView.setVisibility(View.GONE)
                skipBlockView.setVisibility(View.GONE)
                setInputEnabled(true, true)
                updateCaptchaInput((if (input != null) input else captchaInput)!!)
                updateCaptchaHeight(large)
                if (captchaLifetimeTimerAvailable()) {
                    startCaptchaLifetimeTimer()
                    showCaptchaLifetimeTimer()
                } else {
                    hideCaptchaLifetimeTimer()
                }
            }

            CaptchaViewType.SKIP, CaptchaViewType.SKIP_LOCK -> {
                blockParentView.setClickable(captchaViewType != CaptchaViewType.SKIP_LOCK)
                blockView.setVisibility(View.INVISIBLE)
                imageView.setVisibility(View.VISIBLE)
                imageView.setImageResource(android.R.color.transparent)
                loadingView.setVisibility(View.GONE)
                skipBlockView.setVisibility(View.VISIBLE)
                setInputEnabled(false, true)
                updateCaptchaHeight(false)
            }
        }
    }

    private fun captchaLifetimeTimerAvailable(): Boolean {
        return captchaLifetimeTimerEnabled && captchaLifetimeSeconds > 0 && lifetimeTimerView != null
    }

    private fun showCaptchaLifetimeTimer() {
        if (!imageView.isLaidOut()) {
            showCaptchaLifetimeTimerWhenImageViewIsLaidOut()
            return
        }
        alignCaptchaLifetimeTimerWithCaptchaImage()
        lifetimeTimerView!!.setVisibility(View.VISIBLE)
    }

    private fun showCaptchaLifetimeTimerWhenImageViewIsLaidOut() {
        imageView.getViewTreeObserver().addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                imageView.getViewTreeObserver().removeOnGlobalLayoutListener(this)
                showCaptchaLifetimeTimer()
            }
        })
    }

    private fun alignCaptchaLifetimeTimerWithCaptchaImage() {
        val captchaImageRealWidth = this.captchaImageRealWidth
        if (captchaImageRealWidth > 0) {
            val captchaImageViewWidth = imageView.getWidth()
            val lifetimeTimerViewEndMargin = (captchaImageViewWidth - captchaImageRealWidth) / 2
            val params = lifetimeTimerView!!.getLayoutParams() as FrameLayout.LayoutParams
            params.setMarginEnd(lifetimeTimerViewEndMargin)
            lifetimeTimerView.setLayoutParams(params)
        }
    }

    private val captchaImageRealWidth: Int
        get() {
            val captchaImageDrawable = imageView.getDrawable()
            if (captchaImageDrawable == null) {
                return 0
            }

            val captchaImageWidth = captchaImageDrawable.getIntrinsicWidth()
            val imageMatrix = FloatArray(9)
            imageView.getImageMatrix().getValues(imageMatrix)

            return Math.round(captchaImageWidth * imageMatrix[Matrix.MSCALE_X])
        }

    private fun hideCaptchaLifetimeTimer() {
        if (lifetimeTimerView != null) {
            lifetimeTimerView.setVisibility(View.GONE)
        }
    }

    private val captchaLifetimeUpdateRunnable: Runnable = object : Runnable {
        override fun run() {
            val captchaAlive = captchaLifetimeSeconds > 0
            if (captchaAlive) {
                lifetimeTimerView!!.setText(
                    String.format(
                        Locale.getDefault(),
                        "%d",
                        captchaLifetimeSeconds--
                    )
                )
                ConcurrentUtils.HANDLER.postDelayed(this, 1000)
            } else {
                callback.onCaptchaLifetimeEnded()
            }
        }
    }

    init {
        blockParentView = container.findViewById<View>(R.id.captcha_block_parent)
        blockView = container.findViewById<View>(R.id.captcha_block)
        imageView = container.findViewById<ImageView>(R.id.captcha_image)
        loadingView = container.findViewById<View>(R.id.captcha_loading)
        skipBlockView = container.findViewById<View>(R.id.captcha_skip_block)
        skipTextView = container.findViewById<TextView>(R.id.captcha_skip_text)
        cancelView = container.findViewById<ImageView>(R.id.captcha_cancel)
        lifetimeTimerView = container.findViewById<TextView?>(R.id.captcha_lifetime_timer)
        captchaLifetimeTimerEnabled = isHugeCaptcha && isCaptchaTimer
        if (hideInput) {
            inputView.setVisibility(View.GONE)
        }
        cancelView.setImageTintList(
            getColorStateList(
                cancelView.getContext(),
                android.R.attr.textColorPrimary
            )
        )
        skipTextView.setAllCaps(true)
        skipTextView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)
        setTextSizeScaled(skipTextView, 12)

        updateCaptchaHeight(false)
        captchaInput = captcha.input
        if (captchaInput == null) {
            captchaInput = ChanConfiguration.Captcha.Input.ALL
        }
        updateCaptchaInput(captchaInput!!)
        inputView.setFilters(arrayOf<InputFilter>(LengthFilter(50)))
        inputView.setOnEditorActionListener(this)
        cancelView.setOnClickListener(this)
        blockParentView.setOnClickListener(this)
        blockParentView.setOnLongClickListener(this)
        if (inputParentView != null) {
            inputParentView.setOnClickListener(this)
        }
    }

    fun onDestroyView() {
        stopCaptchaLifetimeTimer()
    }

    private fun startCaptchaLifetimeTimer() {
        captchaLifetimeUpdateRunnable.run()
    }

    private fun stopCaptchaLifetimeTimer() {
        ConcurrentUtils.HANDLER.removeCallbacks(captchaLifetimeUpdateRunnable)
    }
}
