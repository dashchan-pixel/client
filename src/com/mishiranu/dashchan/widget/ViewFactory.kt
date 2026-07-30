package com.mishiranu.dashchan.widget

import android.content.Context
import android.content.res.Configuration
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toolbar
import androidx.core.widget.TextViewCompat
import chan.util.StringUtils
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.FlagUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils

object ViewFactory {
    const val FEATURE_WIDGET = 0x00000001
    const val FEATURE_SINGLE_LINE = 0x00000002
    const val FEATURE_TEXT2_END = 0x00000004

    @JvmStatic
    fun makeListTextHeader(parent: ViewGroup): TextView {
        val textView = TextView(parent.context)
        val density = ResourceUtils.obtainDensity(parent)
        textView.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        textView.minHeight = (48f * density).toInt()
        textView.gravity = Gravity.CENTER_VERTICAL
        textView.setTextColor(ThemeEngine.getTheme(textView.context).accent)
        textView.typeface = ResourceUtils.TYPEFACE_MEDIUM
        ViewUtils.setTextSizeScaled(textView, 14)
        textView.setPadding(
            (16f * density).toInt(),
            (16f * density).toInt(),
            (16f * density).toInt(),
            (8f * density).toInt(),
        )
        return textView
    }

    @JvmStatic
    fun makeSingleLineListItem(parent: ViewGroup): View {
        val density = ResourceUtils.obtainDensity(parent)
        val textView = TextView(parent.context)
        textView.setPadding((16f * density).toInt(), 0, (16f * density).toInt(), 0)
        val typedArray =
            textView.context.obtainStyledAttributes(
                intArrayOf(
                    android.R.attr.textAppearanceListItem,
                    android.R.attr.listPreferredItemHeightSmall,
                ),
            )
        TextViewCompat.setTextAppearance(textView, typedArray.getResourceId(0, 0))
        textView.minimumHeight = typedArray.getDimensionPixelSize(1, 0)
        typedArray.recycle()
        ViewUtils.setSelectableItemBackground(textView)
        textView.gravity = Gravity.CENTER_VERTICAL
        textView.isSingleLine = true
        textView.ellipsize = TextUtils.TruncateAt.END
        textView.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        return textView
    }

    class TwoLinesViewHolder(
        @JvmField val view: View,
        @JvmField val text1: TextView,
        @JvmField val text2: TextView,
        @JvmField val text2End: TextView?,
        @JvmField val widgetFrame: LinearLayout?,
    )

    private val ATTRS_TWO_LINES =
        intArrayOf(
            android.R.attr.listPreferredItemHeightSmall,
            android.R.attr.textAppearanceListItem,
            android.R.attr.textAppearanceListItemSecondary,
            android.R.attr.textColorSecondary,
        )

    @JvmStatic
    fun makeTwoLinesListItem(
        parent: ViewGroup,
        features: Int,
    ): TwoLinesViewHolder {
        val density = ResourceUtils.obtainDensity(parent)
        val outerLayout = LinearLayout(parent.context)
        outerLayout.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        val outerPaddingHorizontal = (16f * density + 0.5f).toInt()
        val innerPaddingVertical = (16f * density + 0.5f).toInt()
        val featureWidgetFrame = FlagUtils.get(features, FEATURE_WIDGET)
        val innerLayout: LinearLayout
        if (featureWidgetFrame) {
            outerLayout.orientation = LinearLayout.HORIZONTAL
            outerLayout.isBaselineAligned = false
            innerLayout = LinearLayout(outerLayout.context)
            innerLayout.orientation = LinearLayout.VERTICAL
            outerLayout.addView(innerLayout, 0, LinearLayout.LayoutParams.WRAP_CONTENT)
            (innerLayout.layoutParams as LinearLayout.LayoutParams).weight = 1f
            outerLayout.setPaddingRelative(outerPaddingHorizontal, 0, outerPaddingHorizontal, 0)
            innerLayout.setPaddingRelative(0, innerPaddingVertical, 0, innerPaddingVertical)
        } else {
            outerLayout.orientation = LinearLayout.VERTICAL
            outerLayout.setPaddingRelative(
                outerPaddingHorizontal,
                innerPaddingVertical,
                outerPaddingHorizontal,
                innerPaddingVertical,
            )
            innerLayout = outerLayout
        }
        outerLayout.gravity = Gravity.CENTER_VERTICAL
        val typedArray = parent.context.obtainStyledAttributes(ATTRS_TWO_LINES)
        try {
            outerLayout.minimumHeight = typedArray.getDimensionPixelSize(0, 0)
            val text1 = TextView(parent.context)
            TextViewCompat.setTextAppearance(text1, typedArray.getResourceId(1, 0))
            text1.isSingleLine = true
            text1.ellipsize = TextUtils.TruncateAt.END
            val text2 = TextView(parent.context)
            TextViewCompat.setTextAppearance(text2, typedArray.getResourceId(2, 0))
            text2.setTextColor(typedArray.getColorStateList(3))
            val featureText2End = FlagUtils.get(features, FEATURE_TEXT2_END)
            if (FlagUtils.get(features, FEATURE_SINGLE_LINE) || featureText2End) {
                text2.isSingleLine = true
                text2.ellipsize = TextUtils.TruncateAt.END
            } else {
                text2.maxLines = 10
            }
            val text2End: TextView?
            if (featureText2End) {
                text2End = TextView(parent.context)
                TextViewCompat.setTextAppearance(text2End, typedArray.getResourceId(2, 0))
                text2End.setTextColor(typedArray.getColorStateList(3))
                text2.isSingleLine = true
                text2.ellipsize = TextUtils.TruncateAt.END
            } else {
                text2End = null
            }
            innerLayout.addView(
                text1,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (featureText2End) {
                val text2Layout = LinearLayout(parent.context)
                text2Layout.orientation = LinearLayout.HORIZONTAL
                innerLayout.addView(
                    text2Layout,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                text2Layout.addView(text2, 0, LinearLayout.LayoutParams.WRAP_CONTENT)
                (text2.layoutParams as LinearLayout.LayoutParams).weight = 1f
                text2Layout.addView(
                    text2End!!,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                ViewUtils.setNewMarginRelative(text2End, (8f * density + 0.5f).toInt(), null, null, null)
            } else {
                innerLayout.addView(
                    text2,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val widgetFrame: LinearLayout?
            if (featureWidgetFrame) {
                widgetFrame = LinearLayout(parent.context)
                widgetFrame.orientation = LinearLayout.VERTICAL
                widgetFrame.gravity = Gravity.CENTER
                widgetFrame.visibility = View.GONE
                outerLayout.addView(
                    widgetFrame,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
                ViewUtils.setNewMarginRelative(widgetFrame, outerPaddingHorizontal, null, null, null)
            } else {
                widgetFrame = null
            }
            ViewUtils.setSelectableItemBackground(outerLayout)
            val holder = TwoLinesViewHolder(outerLayout, text1, text2, text2End, widgetFrame)
            outerLayout.tag = holder
            return holder
        } finally {
            typedArray.recycle()
        }
    }

    class ToolbarHolder internal constructor(
        @JvmField val toolbar: ViewGroup,
        @JvmField val layout: View,
        private val title: TextView,
        private val subtitle: TextView,
    ) {
        fun update(
            title: CharSequence?,
            subtitle: CharSequence?,
        ) {
            this.title.text = title
            this.subtitle.text = subtitle
            this.subtitle.visibility = if (StringUtils.isEmpty(subtitle)) View.GONE else View.VISIBLE
        }
    }

    @JvmStatic
    fun addToolbarTitle(toolbar: Toolbar): ToolbarHolder {
        val layout = LinearLayout(toolbar.context)
        layout.orientation = LinearLayout.VERTICAL
        toolbar.addView(layout, Toolbar.LayoutParams.WRAP_CONTENT, Toolbar.LayoutParams.WRAP_CONTENT)
        val title = TextView(layout.context)
        layout.addView(title, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        TextViewCompat.setTextAppearance(title, android.R.style.TextAppearance_Material_Widget_Toolbar_Title)
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        val subtitle = TextView(layout.context)
        layout.addView(subtitle, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        TextViewCompat.setTextAppearance(subtitle, android.R.style.TextAppearance_Material_Widget_Toolbar_Subtitle)
        subtitle.isSingleLine = true
        subtitle.ellipsize = TextUtils.TruncateAt.END
        val configuration = toolbar.resources.configuration
        if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT && !ResourceUtils.isTablet(configuration)) {
            val density = ResourceUtils.obtainDensity(toolbar)
            ViewUtils.setNewMargin(subtitle, null, (-2f * density).toInt(), null, null)
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, (subtitle.textSize * 0.85f + 0.5f).toInt().toFloat())
        }
        return ToolbarHolder(toolbar, layout, title, subtitle)
    }

    @JvmStatic
    fun createProgressLayout(parent: ViewGroup): View {
        val progress = FrameLayout(parent.context)
        parent.addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        progress.visibility = View.GONE
        val progressBar = ProgressBar(progress.context)
        progress.addView(progressBar, FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        (progressBar.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
        ThemeEngine.applyStyle(progressBar)
        return progress
    }

    class ErrorHolder(
        @JvmField val layout: View,
        @JvmField val text: TextView,
    )

    @JvmStatic
    fun createErrorLayout(parent: ViewGroup): ErrorHolder {
        val layout = LinearLayout(parent.context)
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER
        val image = ImageView(layout.context)
        image.setImageDrawable(ResourceUtils.getDrawable(image.context, R.attr.iconButtonWarning, 0))
        image.imageTintList =
            ResourceUtils.getColorStateList(
                image.context,
                android.R.attr.textColorSecondary,
            )
        layout.addView(image, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val text = TextView(layout.context)
        TextViewCompat.setTextAppearance(
            text,
            ResourceUtils.getResourceId(
                text.context,
                android.R.attr.textAppearanceMedium,
                0,
            ),
        )
        text.gravity = Gravity.CENTER
        val density = ResourceUtils.obtainDensity(parent)
        text.setPadding((16f * density).toInt(), 0, (16f * density).toInt(), 0)
        layout.addView(text, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layout.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        return ErrorHolder(layout, text)
    }

    class SeekLayoutHolder(
        @JvmField val layout: View,
        private val valueText: TextView,
        private val switchView: MaterialSwitch,
        private val slider: Slider,
        private val minValue: Int,
        private val step: Int,
        private val valueFormat: String?,
        private val disabledAlpha: Float,
    ) {
        private val minText: TextView = layout.findViewById(R.id.min_value)
        private val maxText: TextView = layout.findViewById(R.id.max_value)

        /**
         * What the value reads while the switch is off — "Disabled" and the like. Without one the
         * value keeps standing there in words, which is not what an off switch means.
         */
        var disabledText: CharSequence? = null
            set(text) {
                field = text
                updateValueText()
            }

        var isEnabled: Boolean
            get() = slider.isEnabled
            set(enabled) {
                if (switchView.isChecked != enabled) {
                    switchView.isChecked = enabled
                }
                slider.isEnabled = enabled
                // The slider's own tints have a disabled state, but the range labels around it are
                // plain text: fade them the way the framework fades a disabled view, so the whole
                // block reads as off rather than just the track.
                val alpha = if (enabled) 1f else disabledAlpha
                minText.alpha = alpha
                maxText.alpha = alpha
                updateValueText()
            }

        // The slider runs in step units (0..(max-min)/step, stepSize 1) exactly as the old SeekBar's
        // progress did, so mixed step/divisibility never trips Slider's "value must land on a step"
        // check; the real value is mapped back on the way in and out.
        var value: Int
            get() = slider.value.toInt() * step + minValue
            set(value) {
                val progress = ((value - minValue) / step).toFloat().coerceIn(slider.valueFrom, slider.valueTo)
                if (slider.value != progress) {
                    slider.value = progress
                }
                updateValueText()
            }

        private fun updateValueText() {
            val disabledText = this.disabledText
            valueText.text =
                if (!isEnabled && disabledText != null) {
                    disabledText
                } else if (valueFormat != null) {
                    String.format(valueFormat, value)
                } else {
                    value.toString()
                }
        }
    }

    @JvmStatic
    fun createSeekLayout(
        context: Context,
        showSwitch: Boolean,
        minValue: Int,
        maxValue: Int,
        step: Int,
        valueFormat: String?,
    ): SeekLayoutHolder {
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.dialog_seek_bar, null)
        val minText = layout.findViewById<TextView>(R.id.min_value)
        val maxText = layout.findViewById<TextView>(R.id.max_value)
        minText.text = minValue.toString()
        maxText.text = maxValue.toString()
        val valueText = layout.findViewById<TextView>(R.id.current_value)
        // The switch is the on-pattern control for a boolean, the same one the settings rows use, and
        // like the Slider below it must be built in a Material3 context and tinted to the user theme.
        val switchView = MaterialSwitch(MaterialContext.wrap(context))
        ThemeEngine.applyStyle(switchView)
        val switchContainer = layout.findViewById<FrameLayout>(R.id.switch_container)
        switchContainer.addView(
            switchView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        // The Slider must be built in a Material3 context and tinted to the user theme accent; the
        // enclosing layout stays app-themed, so drop it into the placeholder container in code.
        val slider = Slider(MaterialContext.wrap(context))
        slider.valueFrom = 0f
        slider.valueTo = ((maxValue - minValue) / step).toFloat().coerceAtLeast(1f)
        slider.stepSize = 1f
        slider.isSaveEnabled = false
        slider.setLabelBehavior(LabelFormatter.LABEL_GONE)
        ThemeEngine.applyStyle(slider)
        layout.findViewById<FrameLayout>(R.id.seek_bar_container).addView(
            slider,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val holder =
            SeekLayoutHolder(
                layout,
                valueText,
                switchView,
                slider,
                minValue,
                step,
                valueFormat,
                ThemeEngine.getTheme(context).disabledAlpha21,
            )
        layout.tag = holder
        slider.addOnChangeListener { _, sliderValue, _ ->
            holder.value = sliderValue.toInt() * step + minValue
        }
        switchView.isSaveEnabled = false
        if (!showSwitch) {
            switchContainer.visibility = View.GONE
        } else {
            switchView.setOnCheckedChangeListener { _, isChecked -> holder.isEnabled = isChecked }
            ViewUtils.setNewMarginRelative(switchContainer, null, null, 0, null)
        }
        return holder
    }
}
