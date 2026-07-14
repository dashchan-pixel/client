package com.mishiranu.dashchan.ui.posting.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.fragment.app.DialogFragment
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.ThemeEngine
import com.mishiranu.dashchan.widget.ViewFactory
import java.util.Locale

class ReencodingDialog :
    DialogFragment(),
    DialogInterface.OnClickListener,
    RadioGroup.OnCheckedChangeListener {
    private lateinit var radioGroup: RadioGroup
    private lateinit var qualityLayoutHolder: ViewFactory.SeekLayoutHolder
    private lateinit var reduceLayoutHolder: ViewFactory.SeekLayoutHolder
    private lateinit var jpegToJpg: CheckBox

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context: Context = requireActivity()
        qualityLayoutHolder =
            ViewFactory.createSeekLayout(
                context,
                false,
                1,
                100,
                1,
                ResourceUtils.getColonString(resources, R.string.quality, "%d%%"),
            )
        qualityLayoutHolder.value = if (savedInstanceState != null) savedInstanceState.getInt(EXTRA_QUALITY) else 90
        reduceLayoutHolder =
            ViewFactory.createSeekLayout(
                context,
                false,
                1,
                8,
                1,
                ResourceUtils.getColonString(resources, R.string.reduce, "%dx"),
            )
        reduceLayoutHolder.value = if (savedInstanceState != null) savedInstanceState.getInt(EXTRA_REDUCE) else 1
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_padding_view)
        ViewUtils.setNewPadding(qualityLayoutHolder.layout, null, 0, null, padding / 2)
        ViewUtils.setNewPadding(reduceLayoutHolder.layout, null, 0, null, null)
        jpegToJpg = CheckBox(context)
        ThemeEngine.applyStyle(jpegToJpg)
        jpegToJpg.setText(R.string.rename_to_jpg)
        jpegToJpg.isChecked = false
        radioGroup = RadioGroup(context)
        radioGroup.orientation = RadioGroup.VERTICAL
        radioGroup.setPadding(padding, padding, padding, padding / 2)
        radioGroup.setOnCheckedChangeListener(this)
        for (i in OPTIONS.indices) {
            val radioButton = RadioButton(context)
            ThemeEngine.applyStyle(radioButton)
            radioButton.text = OPTIONS[i]
            radioButton.id = IDS[i]
            radioGroup.addView(radioButton)
        }
        radioGroup.check(IDS[JPEG_POSITION])
        val jpegToJpgLayout = FrameLayout(context)
        jpegToJpgLayout.setPadding(padding, 0, 0, padding / 2)
        jpegToJpgLayout.addView(jpegToJpg)
        val linearLayout = LinearLayout(context)
        linearLayout.orientation = LinearLayout.VERTICAL
        val qualityLayout = FrameLayout(context)
        qualityLayout.id = android.R.id.text1
        qualityLayout.addView(qualityLayoutHolder.layout)
        val reduceLayout = FrameLayout(context)
        reduceLayout.id = android.R.id.text2
        reduceLayout.addView(reduceLayoutHolder.layout)
        linearLayout.addView(
            radioGroup,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        linearLayout.addView(
            jpegToJpgLayout,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        linearLayout.addView(
            qualityLayout,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        linearLayout.addView(
            reduceLayout,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val scrollView = ScrollView(context)
        scrollView.addView(
            linearLayout,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        return AlertDialog
            .Builder(context)
            .setTitle(R.string.reencode_image)
            .setView(scrollView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, this)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_QUALITY, qualityLayoutHolder.value)
        outState.putInt(EXTRA_REDUCE, reduceLayoutHolder.value)
    }

    override fun onClick(
        dialog: DialogInterface,
        which: Int,
    ) {
        var format: String? = null
        val id = radioGroup.checkedRadioButtonId
        for (i in IDS.indices) {
            if (IDS[i] == id) {
                format =
                    if (i == JPEG_POSITION && jpegToJpg.isChecked) {
                        GraphicsUtils.Reencoding.FORMAT_JPEG_ALTNAME
                    } else {
                        FORMATS[i]
                    }
                break
            }
        }
        (parentFragment as AttachmentOptionsDialog).setReencoding(
            GraphicsUtils
                .Reencoding(format, qualityLayoutHolder.value, reduceLayoutHolder.value),
        )
    }

    override fun onCheckedChanged(
        group: RadioGroup,
        checkedId: Int,
    ) {
        var allowQuality = true
        for (i in IDS.indices) {
            if (IDS[i] == checkedId) {
                allowQuality = GraphicsUtils.Reencoding.allowQuality(FORMATS[i])
                break
            }
        }
        qualityLayoutHolder.isEnabled = allowQuality
        jpegToJpg.isEnabled = checkedId == IDS[JPEG_POSITION]
    }

    companion object {
        @JvmField
        val TAG: String = ReencodingDialog::class.java.name

        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_REDUCE = "reduce"
        private const val JPEG_POSITION = 0

        private val OPTIONS =
            arrayOf(
                GraphicsUtils.Reencoding.FORMAT_JPEG.uppercase(Locale.US),
                GraphicsUtils.Reencoding.FORMAT_PNG.uppercase(Locale.US),
            )
        private val FORMATS =
            arrayOf(
                GraphicsUtils.Reencoding.FORMAT_JPEG,
                GraphicsUtils.Reencoding.FORMAT_PNG,
            )
        private val IDS = intArrayOf(android.R.id.icon1, android.R.id.icon2)
    }
}
