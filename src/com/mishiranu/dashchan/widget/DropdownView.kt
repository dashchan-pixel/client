package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import kotlin.math.min

class DropdownView(
    context: Context,
    attrs: AttributeSet?,
) : FrameLayout(context, attrs) {
    constructor(context: Context) : this(context, null)

    private val spinner: Spinner = Spinner(context)
    private val factory: () -> TextView

    init {
        spinner.id = android.R.id.edit
        spinner.setPadding(0, 0, 0, 0)
        addView(spinner, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        val editText = EditText(context)
        ThemeEngine.applyStyle(editText)
        background = editText.background
        backgroundTintList = editText.backgroundTintList
        setPadding(0, 0, 0, 0)
        setAddStatesFromChildren(true)
        val paddingLeft = editText.paddingLeft
        val paddingTop = editText.paddingTop
        val paddingRight = editText.paddingRight
        val paddingBottom = editText.paddingBottom
        val textColors = editText.textColors
        val textSize = editText.textSize
        val typeface = editText.typeface
        factory = {
            val textView = TextView(context)
            textView.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            textView.setTextColor(textColors)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            textView.typeface = typeface
            textView.isSingleLine = true
            textView
        }

        editText.setText("XXXXXXXXXX")
        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        editText.measure(measureSpec, measureSpec)
        val background = spinner.background
        val bitmap =
            Bitmap.createBitmap(
                editText.measuredWidth,
                editText.measuredHeight,
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        background.setBounds(0, 0, editText.measuredWidth, editText.measuredHeight)
        background.draw(canvas)
        val pixels = IntArray(bitmap.height)
        val zeroPixels = IntArray(bitmap.height)
        var left = -1
        var right = -1
        for (i in 0 until bitmap.width) {
            bitmap.getPixels(pixels, 0, 1, i, 0, 1, bitmap.height)
            if (!pixels.contentEquals(zeroPixels)) {
                left = i
                break
            }
        }
        for (i in bitmap.width - 1 downTo 0) {
            bitmap.getPixels(pixels, 0, 1, i, 0, 1, bitmap.height)
            if (!pixels.contentEquals(zeroPixels)) {
                right = bitmap.width - 1 - i
                break
            }
        }
        bitmap.recycle()
        if (left >= 0 && right >= 0) {
            val imagePadding = min(left, right)
            val textPadding = min(paddingLeft, paddingRight)
            val density = ResourceUtils.obtainDensity(context)
            val targetPadding = (8f * density).toInt() + textPadding
            val margin = targetPadding - imagePadding
            ViewUtils.setNewMarginRelative(spinner, 0, 0, margin, 0)
        }
    }

    fun setItems(collection: Collection<CharSequence>) {
        val adapter =
            object : ArrayAdapter<CharSequence>(context, android.R.layout.simple_spinner_item) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup,
                ): View {
                    val view = convertView ?: factory()
                    return super.getView(position, view, parent)
                }
            }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        adapter.addAll(collection)
        spinner.adapter = adapter
    }

    fun setSelection(position: Int) {
        spinner.setSelection(position)
    }

    fun getSelectedItemPosition(): Int = spinner.selectedItemPosition

    /** Invoked with the item position whenever the current selection changes. */
    fun setOnItemSelectedListener(listener: (position: Int) -> Unit) {
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    listener(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }
}
