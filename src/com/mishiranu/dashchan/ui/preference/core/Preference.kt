package com.mishiranu.dashchan.ui.preference.core

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ViewFactory
import com.mishiranu.dashchan.widget.ViewFactory.makeTwoLinesListItem

abstract class Preference<T>(
    val context: Context?,
    val key: String?,
    @JvmField val defaultValue: T?,
    protected val title: CharSequence?,
    protected val summaryProvider: SummaryProvider<T>?,
) {
    enum class ViewType {
        NORMAL,
        CATEGORY,
        HEADER,
        CHECK,
    }

    fun interface SummaryProvider<T> {
        fun getSummary(value: Preference<T>?): CharSequence?
    }

    fun interface OnClickListener<T> {
        fun onClick(preference: Preference<T>?)
    }

    internal fun interface OnChangeListener {
        fun onChange(newValue: Boolean)
    }

    fun interface OnBeforeChangeListener<T> {
        fun onBeforeChange(
            preference: Preference<T>?,
            value: T?,
        ): Boolean
    }

    fun interface OnAfterChangeListener<T> {
        fun onAfterChange(preference: Preference<T>?)
    }

    open class ViewHolder(
        val view: View,
        val title: TextView?,
        val summary: TextView?,
        val widgetFrame: LinearLayout?,
    ) {
        constructor(viewHolder: ViewHolder) : this(
            viewHolder.view,
            viewHolder.title,
            viewHolder.summary,
            viewHolder.widgetFrame,
        )
    }

    var value: T? = null
        set(value) {
            if (onBeforeChangeListener == null ||
                onBeforeChangeListener!!.onBeforeChange(
                    this,
                    value,
                )
            ) {
                field = value
                if (onChangeListener != null) {
                    onChangeListener!!.onChange(true)
                }
            }
        }
    private var enabled = true
    private var selectable = true
    private var onClickListener: OnClickListener<T>? = null
    private var onChangeListener: OnChangeListener? = null
    private var onBeforeChangeListener: OnBeforeChangeListener<T>? = null
    private var onAfterChangeListener: OnAfterChangeListener<T>? = null

    open fun getViewType(): ViewType = ViewType.NORMAL

    open fun createViewHolder(parent: ViewGroup): ViewHolder {
        val holder = makeTwoLinesListItem(parent, ViewFactory.FEATURE_WIDGET)
        return ViewHolder(holder.view, holder.text1, holder.text2, holder.widgetFrame)
    }

    open fun bindViewHolder(viewHolder: ViewHolder) {
        if (viewHolder.title != null) {
            viewHolder.title.setText(title)
            viewHolder.title.setVisibility(if (isEmpty(title)) View.GONE else View.VISIBLE)
            viewHolder.title.setEnabled(enabled)
        }
        if (viewHolder.summary != null) {
            val summary = if (summaryProvider != null) summaryProvider.getSummary(this) else null
            viewHolder.summary.setText(summary)
            viewHolder.summary.setVisibility(if (isEmpty(summary)) View.GONE else View.VISIBLE)
            viewHolder.summary.setEnabled(enabled)
        }
        viewHolder.view.setEnabled(enabled && selectable)
    }

    fun performClick() {
        if (onClickListener != null) {
            onClickListener!!.onClick(this)
        }
    }

    internal abstract fun extract(preferences: SharedPreferences)

    internal abstract fun persist(preferences: SharedPreferences)

    fun invalidate() {
        if (onChangeListener != null) {
            onChangeListener!!.onChange(false)
        }
    }

    fun notifyAfterChange() {
        if (onAfterChangeListener != null) {
            onAfterChangeListener!!.onAfterChange(this)
        }
    }

    fun setOnClickListener(listener: OnClickListener<T>?) {
        this.onClickListener = listener
    }

    internal fun setOnChangeListener(listener: OnChangeListener?) {
        this.onChangeListener = listener
    }

    fun setOnBeforeChangeListener(listener: OnBeforeChangeListener<T>?) {
        this.onBeforeChangeListener = listener
    }

    fun setOnAfterChangeListener(listener: OnAfterChangeListener<T>?) {
        this.onAfterChangeListener = listener
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        invalidate()
    }

    fun isEnabled(): Boolean = enabled

    fun setSelectable(selectable: Boolean) {
        this.selectable = selectable
        invalidate()
    }

    open class Runtime<T>(
        context: Context?,
        key: String?,
        defaultValue: T?,
        title: CharSequence?,
        summaryProvider: SummaryProvider<T>?,
    ) : Preference<T>(context, key, defaultValue, title, summaryProvider) {
        override fun extract(preferences: SharedPreferences): Unit = throw UnsupportedOperationException()

        override fun persist(preferences: SharedPreferences): Unit = throw UnsupportedOperationException()

        class IconViewHolder(
            viewHolder: ViewHolder,
            val icon: ImageView?,
        ) : ViewHolder(viewHolder)

        fun createIconViewHolder(parent: ViewGroup): IconViewHolder {
            val viewHolder = super.createViewHolder(parent)
            val density = obtainDensity(parent)
            val icon = ImageView(viewHolder.view.getContext())
            val layoutParams =
                LinearLayout.LayoutParams((24f * density).toInt(), (24f * density).toInt())
            layoutParams.setMarginEnd((32f * density).toInt())

            icon.setLayoutParams(layoutParams)
            (viewHolder.view as LinearLayout).addView(icon, 0)
            return IconViewHolder(viewHolder, icon)
        }
    }
}
