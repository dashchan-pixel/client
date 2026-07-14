package com.mishiranu.dashchan.widget

import android.app.Activity
import android.app.ActivityManager.TaskDescription
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toolbar
import androidx.annotation.RequiresApi
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.Preferences.theme
import com.mishiranu.dashchan.content.storage.ThemesStorage.Companion.getInstance
import com.mishiranu.dashchan.graphics.ColorScheme
import com.mishiranu.dashchan.graphics.ThemeChoiceDrawable
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.GraphicsUtils.applyAlpha
import com.mishiranu.dashchan.util.IOUtils.readRawResourceString
import com.mishiranu.dashchan.util.ResourceUtils.getColor
import com.mishiranu.dashchan.util.ResourceUtils.getColorStateList
import com.mishiranu.dashchan.util.ViewUtils.addWindowFocusListener
import com.mishiranu.dashchan.util.ViewUtils.getDecorView
import com.mishiranu.dashchan.util.ViewUtils.setEdgeEffectColor
import com.mishiranu.dashchan.util.WeakIterator
import com.mishiranu.dashchan.util.WeakObservable
import com.mishiranu.dashchan.widget.ThemeEngine.OnOverlayFocusListener.MutableItem
import org.json.JSONException
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.Iterable
import kotlin.collections.LinkedHashMap
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.dropLastWhile
import kotlin.collections.remove
import kotlin.collections.toTypedArray
import kotlin.math.min

class ThemeEngine {
    class Theme(
        val base: Base?,
        @JvmField val name: String,
        val builtIn: Boolean,
        private val json: String?,
        @JvmField val window: Int,
        val primary: Int,
        @JvmField val accent: Int,
        @JvmField val card: Int,
        val thread: Int,
        val post: Int,
        @JvmField val meta: Int,
        val spoiler: Int,
        val link: Int,
        val quote: Int,
        val tripcode: Int,
        val capcode: Int,
        val highlight: Int,
        val colorGainFactor: Float,
        @JvmField val controlNormal21: Int,
        val neuroslop: Int,
        val neuroslopQuote: Int,
        @JvmField val disabledAlpha21: Float,
    ) : Comparable<Theme> {
        enum class Base(
            internal val resId: Int,
        ) {
            LIGHT(R.style.Theme_Main_Light),
            DARK(R.style.Theme_Main_Dark),
        }

        val isBlack4: Boolean
            get() = false && base == Base.DARK && window == primary && (primary and 0x00ffffff) == 0

        fun createThemeChoiceDrawable(): ThemeChoiceDrawable {
            val window = this.window
            val primary = if (this.primary == window) this.accent else this.primary
            val accent = if (this.accent == primary) this.link else this.accent
            return ThemeChoiceDrawable(window, primary, accent)
        }

        fun toJsonObject(): JSONObject {
            try {
                return JSONObject(json!!)
            } catch (e: JSONException) {
                throw RuntimeException(e)
            }
        }

        internal fun getColor(name: String): Int {
            when (name) {
                "window" -> return window
                "primary" -> return primary
                "accent" -> return accent
                "card" -> return card
                "thread" -> return thread
                "post" -> return post
                "meta" -> return meta
                "spoiler" -> return spoiler
                "link" -> return link
                "quote" -> return quote
                "tripcode" -> return tripcode
                "capcode" -> return capcode
                "highlight" -> return highlight
                "neuroslop" -> return neuroslop
                "neuroslopQuote" -> return neuroslopQuote
                else -> throw IllegalArgumentException()
            }
        }

        override fun compareTo(other: Theme): Int = name.compareTo(other.name)
    }

    fun interface OnOverlayFocusListener {
        class MutableItem {
            var decorView: View? = null
            var indirect: Boolean = false
        }

        fun onOverlayFocusChanged(stack: Iterable<MutableItem>?)
    }

    private class OverlayStack :
        Iterable<MutableItem>,
        WeakIterator.Provider<OverlayStack.StackItem, View, MutableItem> {
        private class StackItem(
            decorView: View,
            val indirect: Boolean,
        ) {
            val decorView: WeakReference<View> = WeakReference(decorView)
        }

        private val stackItems = ArrayList<StackItem>()
        private val mutableItem = MutableItem()

        fun handleOverlayFocused(
            decorView: View,
            direct: Boolean,
            dialog: Boolean,
        ) {
            var topStackItem: StackItem? = null
            val iterator = stackItems.iterator()
            while (iterator.hasNext()) {
                val stackItem = iterator.next()
                val itemDecorView = stackItem.decorView.get()
                if (itemDecorView == null) {
                    iterator.remove()
                } else if (itemDecorView === decorView) {
                    topStackItem = stackItem
                    iterator.remove()
                }
            }
            if (topStackItem == null) {
                val indirect = !direct && !dialog
                topStackItem = StackItem(decorView, indirect)
            }
            stackItems.add(topStackItem)
        }

        override fun iterator(): MutableIterator<MutableItem> = WeakIterator(stackItems.iterator(), this)

        override fun getWeakReference(data: StackItem): WeakReference<View>? = data.decorView

        override fun transform(
            data: StackItem,
            referenced: View,
        ): MutableItem? {
            if (referenced.isAttachedToWindow()) {
                val mutableItem = this.mutableItem
                mutableItem.decorView = referenced
                mutableItem.indirect = data.indirect
                return mutableItem
            } else {
                return null
            }
        }

        override fun onFinished() {
            mutableItem.decorView = null
        }
    }

    private class ThemeContext(
        base: Context?,
    ) : ContextWrapper(base) {
        internal var engineTheme: Theme? = null
        internal var colorScheme: ColorScheme? = null
        private var layoutInflater: ThemeLayoutInflater? = null

        private val overlayStack = OverlayStack()
        internal val overlayFocusListeners = WeakObservable<OnOverlayFocusListener>()

        override fun getSystemService(name: String): Any? {
            if (LAYOUT_INFLATER_SERVICE == name) {
                if (layoutInflater == null) {
                    layoutInflater =
                        ThemeLayoutInflater(
                            LayoutInflater
                                .from(getBaseContext()),
                            this,
                            true,
                            false,
                            false,
                            false,
                        )
                }
                return layoutInflater
            }
            return super.getSystemService(name)
        }

        fun dispatchOverlayFocused(
            decorView: View,
            direct: Boolean,
            dialog: Boolean,
        ) {
            overlayStack.handleOverlayFocused(decorView, direct, dialog)
            for (listener in overlayFocusListeners) {
                listener.onOverlayFocusChanged(overlayStack)
            }
        }

        var checkBoxColors: ColorStateList? = null
            get() {
                if (field == null) {
                    val colorControlDisabled =
                        applyAlpha(engineTheme!!.controlNormal21, engineTheme!!.disabledAlpha21)
                    val states =
                        arrayOf<IntArray?>(
                            intArrayOf(-android.R.attr.state_enabled),
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(),
                        )
                    val colors =
                        intArrayOf(colorControlDisabled, engineTheme!!.accent, engineTheme!!.controlNormal21)
                    field = ColorStateList(states, colors)
                }
                return field
            }
            private set
        var switchThumbColors: ColorStateList? = null
            get() {
                if (field == null) {
                    val thumbColorNormal: Int
                    val thumbColorNormalDisabled: Int
                    val thumbNormalColorsAttr =
                        getResources().getIdentifier("colorSwitchThumbNormal", "attr", "android")
                    val thumbColors =
                        if (thumbNormalColorsAttr != 0) {
                            getColorStateList(
                                this,
                                thumbNormalColorsAttr,
                            )
                        } else {
                            null
                        }
                    if (thumbColors != null) {
                        thumbColorNormal = thumbColors.getDefaultColor()
                        val disabledState =
                            intArrayOf(-android.R.attr.state_enabled)
                        thumbColorNormalDisabled =
                            thumbColors.getColorForState(disabledState, thumbColorNormal)
                    } else {
                        thumbColorNormal = engineTheme!!.controlNormal21
                        thumbColorNormalDisabled = engineTheme!!.controlNormal21
                    }
                    val states =
                        arrayOf<IntArray?>(
                            intArrayOf(-android.R.attr.state_enabled),
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(),
                        )
                    val colors =
                        intArrayOf(thumbColorNormalDisabled, engineTheme!!.accent, thumbColorNormal)
                    field = ColorStateList(states, colors)
                }
                return field
            }
            private set
        var editTextColors: ColorStateList? = null
            get() {
                if (field == null) {
                    val states =
                        arrayOf<IntArray?>(
                            intArrayOf(-android.R.attr.state_enabled),
                            intArrayOf(android.R.attr.state_pressed),
                            intArrayOf(android.R.attr.state_focused),
                            intArrayOf(),
                        )
                    val colors =
                        intArrayOf(
                            engineTheme!!.controlNormal21,
                            engineTheme!!.accent,
                            engineTheme!!.accent,
                            engineTheme!!.controlNormal21,
                        )
                    field = ColorStateList(states, colors)
                }
                return field
            }
            private set
        var buttonColors: ColorStateList? = null
            get() {
                if (field == null) {
                    val colorAccentDisabled =
                        applyAlpha(engineTheme!!.accent, engineTheme!!.disabledAlpha21)
                    val states =
                        arrayOf<IntArray?>(
                            intArrayOf(-android.R.attr.state_enabled),
                            intArrayOf(),
                        )
                    val colors =
                        intArrayOf(colorAccentDisabled, engineTheme!!.accent)
                    field = ColorStateList(states, colors)
                }
                return field
            }
            private set
    }

    private interface AttachListener : OnAttachStateChangeListener {
        val isProcessed: Boolean

        fun handleView(view: View)

        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            handleView(v)
        }

        override fun onViewDetachedFromWindow(v: View) {}
    }

    private class OverlayAttachListener(
        private val direct: Boolean,
        private val dialog: Boolean,
    ) : AttachListener {
        private var processed = false

        override val isProcessed: Boolean
            get() = processed

        override fun handleView(view: View) {
            if (!processed) {
                processed = true
                val decorView = getDecorView(view)
                if ("DecorView" == decorView.javaClass.getSimpleName()) {
                    val themeContext: ThemeContext? = obtainThemeContext(decorView.getContext())
                    if (themeContext != null) {
                        if (dialog && shouldApplyStyle(decorView.getContext())) {
                            decorView.setBackgroundTintList(ColorStateList.valueOf(themeContext.engineTheme!!.card))
                        }
                        val tag = decorView.getTag(R.id.tag_theme_engine)
                        val forceDialog = tag is Boolean && tag
                        val dialog = this.dialog || forceDialog
                        themeContext.dispatchOverlayFocused(decorView, direct, dialog)
                        addWindowFocusListener(
                            decorView,
                            OnFocusChangeListener { v: View?, hasFocus: Boolean ->
                                if (hasFocus) {
                                    themeContext.dispatchOverlayFocused(decorView, direct, dialog)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private class ThemeLayoutInflater(
        original: LayoutInflater?,
        newContext: Context?,
        private val direct: Boolean,
        dialog: Boolean,
        overlay: Boolean,
        popup: Boolean,
    ) : LayoutInflater(original, newContext) {
        private val attachListener: AttachListener?

        private var toolbar = false

        fun isDirect(): Boolean = direct || toolbar

        init {
            attachListener =
                if (dialog || overlay) {
                    OverlayAttachListener(direct, dialog)
                } else {
                    if (popup) {
                        POPUP_ATTACH_LISTENER
                    } else {
                        null
                    }
                }
        }

        override fun cloneInContext(newContext: Context): LayoutInflater {
            val typedArray = newContext.obtainStyledAttributes(CLONE_ATTRS)
            val dialog = typedArray.getBoolean(0, false)
            val overlay = typedArray.getBoolean(1, false)
            val popup = typedArray.getBoolean(2, false)
            typedArray.recycle()
            val inheritDirect = dialog || popup
            val forceDirect = newContext is Activity
            val direct = isDirect() && inheritDirect || forceDirect
            return ThemeLayoutInflater(this, newContext, direct, dialog, overlay, popup)
        }

        @Throws(ClassNotFoundException::class)
        override fun onCreateView(
            name: String?,
            attrs: AttributeSet?,
        ): View {
            val view = createViewInternal(name, attrs)
            if (view is Toolbar) {
                val layoutInflater = from(view.getContext())
                if (layoutInflater is ThemeLayoutInflater) {
                    layoutInflater.toolbar = true
                }
            }
            applyStyle(view)
            if (attachListener != null && !attachListener.isProcessed) {
                view.addOnAttachStateChangeListener(attachListener)
            }
            return view
        }

        @Throws(ClassNotFoundException::class)
        fun createViewInternal(
            name: String?,
            attrs: AttributeSet?,
        ): View {
            for (prefix in PREFIXES) {
                try {
                    return createView(name, prefix, attrs)
                } catch (e: ClassNotFoundException) {
                    // Skip
                }
            }
            return super.onCreateView(name, attrs)
        }

        companion object {
            private val PREFIXES =
                arrayOf<String?>("android.widget.", "android.webkit.", "android.app.")

            private val CLONE_ATTRS =
                intArrayOf(android.R.attr.windowIsFloating, R.attr.isOverlay, R.attr.isPopup)
        }
    }

    private var themes: LinkedHashMap<String?, Theme>? = null

    private fun prepareThemes(context: Context) {
        if (themes == null) {
            val themes = LinkedHashMap<String?, Theme>()
            for (resId in DEFAULT_THEME_RESOURCES) {
                val theme: Theme?
                try {
                    val jsonObject =
                        JSONObject(readRawResourceString(context.getResources(), resId))
                    theme = parseThemeInternal(context, jsonObject, true)
                } catch (e: JSONException) {
                    throw RuntimeException(e)
                }
                themes[theme.name] = theme
            }
            var additionalChanged = false
            val additionalThemes = getInstance().getItems()
            val additionalThemeNames = ArrayList(additionalThemes.keys)
            additionalThemeNames.sort()
            for (name in additionalThemeNames) {
                var theme: Theme? = null
                if (!themes.containsKey(name)) {
                    val jsonObject = additionalThemes[name]
                    theme = Companion.parseTheme(context, jsonObject!!)
                }
                if (theme != null) {
                    themes[name] = theme
                } else {
                    additionalThemes.remove(name)
                    additionalChanged = true
                }
            }
            if (additionalChanged) {
                getInstance().serialize()
            }
            this.themes = themes
            if (themes.isEmpty()) {
                throw RuntimeException("No themes found")
            }
        }
    }

    private class ThemeBuilder {
        fun interface Setter {
            fun setColor(
                builder: ThemeBuilder?,
                color: Int,
            )
        }

        fun interface Getter {
            fun getColor(builder: ThemeBuilder?): Int?
        }

        fun interface Transform {
            fun getTransformed(builder: ThemeBuilder?): Int?
        }

        class Value(
            val setter: Setter,
            val getter: Getter,
            val transform: Transform?,
            val fallback: Int,
        )

        var window: Int? = null
        var primary: Int? = null
        var accent: Int? = null
        var card: Int? = null
        var post: Int? = null
        var meta: Int? = null
        var spoiler: Int? = null
        var link: Int? = null
        var quote: Int? = null
        var tripcode: Int? = null
        var capcode: Int? = null
        var neuroslop: Int? = null
        var neuroslopQuote: Int? = null
        var highlight: Int? = null

        fun create(
            base: Theme.Base?,
            name: String,
            builtIn: Boolean,
            json: String?,
            context: Context,
        ): Theme {
            while (true) {
                var changed = false
                for (value in MAP.values) {
                    changed = changed or transform(value)
                }
                if (!changed) {
                    break
                }
            }
            for (value in MAP.values) {
                if (value.getter.getColor(this) == null) {
                    value.setter.setColor(this, getColor(context, value.fallback))
                }
            }
            var typedArray =
                context.obtainStyledAttributes(
                    intArrayOf(
                        R.attr.colorTextThread,
                        R.attr.colorTextPost,
                        R.attr.colorGainFactor,
                    ),
                )
            val threadAlpha = Color.alpha(typedArray.getColor(0, 0)).toFloat() / 0xff
            val postAlpha = Color.alpha(typedArray.getColor(1, 0)).toFloat() / 0xff
            val colorGainFactor = typedArray.getFloat(2, 0f)
            typedArray.recycle()
            val postToThreadAlpha = min(threadAlpha / postAlpha, 1f)
            val thread = GraphicsUtils.applyAlpha(post!!, postToThreadAlpha)
            var controlNormal21 = 0
            var disabledAlpha21 = 1f
            val attrs = intArrayOf(android.R.attr.colorControlNormal, android.R.attr.disabledAlpha)
            typedArray = context.obtainStyledAttributes(attrs)
            val colorControlNormal = typedArray.getColorStateList(0)
            disabledAlpha21 = typedArray.getFloat(1, 1f)
            typedArray.recycle()
            controlNormal21 =
                colorControlNormal!!.getColorForState(
                    intArrayOf(android.R.attr.state_enabled),
                    colorControlNormal.getDefaultColor(),
                )

            return ThemeEngine.Theme(
                base,
                name,
                builtIn,
                json,
                window!!,
                primary!!,
                accent!!,
                card!!,
                thread,
                post!!,
                meta!!,
                spoiler!!,
                link!!,
                quote!!,
                tripcode!!,
                capcode!!,
                highlight!!,
                colorGainFactor,
                controlNormal21,
                neuroslop!!,
                neuroslopQuote!!,
                disabledAlpha21,
            )
        }

        fun transform(value: Value): Boolean {
            if (value.getter.getColor(this) == null && value.transform != null) {
                val transformed = value.transform.getTransformed(this)
                if (transformed != null) {
                    value.setter.setColor(this, transformed)
                    return true
                }
            }
            return false
        }

        companion object {
            internal val MAP: MutableMap<String?, Value>

            init {
                val map = HashMap<String?, Value>()
                map["window"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.window = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.window },
                        null,
                        R.attr.colorWindowBackground,
                    )
                map["primary"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.primary = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.primary },
                        null,
                        R.attr.colorPrimarySupport,
                    )
                map["accent"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.accent = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.accent },
                        ThemeBuilder.Transform { b: ThemeBuilder? -> b!!.primary },
                        R.attr.colorAccentSupport,
                    )
                map["card"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.card = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.card },
                        null,
                        R.attr.colorCardBackground,
                    )
                map["post"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.post = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.post },
                        null,
                        R.attr.colorTextPost,
                    )
                map["meta"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.meta = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.meta },
                        null,
                        R.attr.colorTextMeta,
                    )
                map["spoiler"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.spoiler = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.spoiler },
                        null,
                        R.attr.colorSpoilerBackground,
                    )
                map["link"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.link = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.link },
                        ThemeBuilder.Transform { b: ThemeBuilder? -> b!!.accent },
                        android.R.attr.textColorLink,
                    )
                map["quote"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.quote = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.quote },
                        null,
                        R.attr.colorTextQuote,
                    )
                map["tripcode"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.tripcode = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.tripcode },
                        null,
                        R.attr.colorTextTripcode,
                    )
                map["capcode"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.capcode = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.capcode },
                        ThemeBuilder.Transform { b: ThemeBuilder? -> b!!.tripcode },
                        R.attr.colorTextCapcode,
                    )
                map["highlight"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.highlight = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.highlight },
                        null,
                        R.attr.colorPostHighlight,
                    )
                map["neuroslop"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.neuroslop = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.neuroslop },
                        null,
                        R.attr.colorPostNeuroslop,
                    )
                map["neuroslopQuote"] =
                    Value(
                        ThemeBuilder.Setter { b: ThemeBuilder?, c: Int -> b!!.neuroslopQuote = c },
                        ThemeBuilder.Getter { b: ThemeBuilder? -> b!!.neuroslopQuote },
                        null,
                        R.attr.colorPostQuoteNeuroslop,
                    )
                MAP = Collections.unmodifiableMap<String?, Value>(map)
            }
        }
    }

    companion object {
        private val INSTANCE = ThemeEngine()

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        private val POPUP_ATTACH_LISTENER: AttachListener =
            object : AttachListener {
                override val isProcessed: Boolean
                    get() = false

                override fun handleView(view: View) {
                    val decorView = getDecorView(view)
                    val tag = decorView.getTag(R.id.tag_theme_engine)
                    if (tag == null || !(tag as Boolean)) {
                        // Mark as handled
                        decorView.setTag(R.id.tag_theme_engine, true)
                        if (shouldApplyStyle(decorView.getContext())) {
                            val decorViewName = decorView.javaClass.getSimpleName()
                            if ("PopupDecorView" == decorViewName || "PopupViewContainer" == decorViewName) {
                                var backgroundView = decorView
                                if (backgroundView.getBackground() == null) {
                                    val viewGroup = decorView as ViewGroup
                                    if (viewGroup.getChildCount() > 0) {
                                        val child = viewGroup.getChildAt(0)
                                        if (child.getBackground() != null) {
                                            // More modern Android versions wrap background with decor view
                                            backgroundView = child
                                        }
                                    }
                                }
                                val themeContext: ThemeContext =
                                    requireThemeContext(decorView.getContext())
                                backgroundView.setBackgroundTintList(ColorStateList.valueOf(themeContext.engineTheme!!.card))
                            }
                        }
                    }
                }
            }

        private fun obtainThemeContext(context: Context?): ThemeContext? {
            var current = context
            while (true) {
                if (current is ThemeContext) {
                    return current
                } else if (current is ContextWrapper) {
                    current = current.getBaseContext()
                } else {
                    return null
                }
            }
        }

        private fun requireThemeContext(context: Context?): ThemeContext {
            val themeContext: ThemeContext? = obtainThemeContext(context)
            requireNotNull(themeContext) { "Context is not attached to theme engine" }
            return themeContext
        }

        @JvmStatic
        fun attach(baseContext: Context?): Context = ThemeContext(baseContext)

        @JvmStatic
        fun applyTheme(context: Context) {
            val themeContext: ThemeContext = requireThemeContext(context)
            INSTANCE.prepareThemes(context)
            val themeString = theme
            var theme: Theme? = INSTANCE.themes!![themeString]
            if (theme == null) {
                theme =
                    INSTANCE.themes!!
                        .values
                        .iterator()
                        .next()
                Preferences.theme = theme.name
            }
            themeContext.engineTheme = theme
            context.setTheme(theme.base!!.resId)
            if (context is Activity) {
                val activity = context
                activity.getWindow().getDecorView().setBackgroundColor(theme.window)
                val toolbarColor = theme.primary or -0x1000000
                val taskDescription: TaskDescription?
                taskDescription =
                    TaskDescription
                        .Builder()
                        .setIcon(R.mipmap.ic_launcher)
                        .setPrimaryColor(toolbarColor)
                        .build()

                activity.setTaskDescription(taskDescription)
            }
        }

        fun getThemes(): MutableList<Theme> = ArrayList(INSTANCE.themes!!.values)

        fun attachAndApply(context: Context?): Theme {
            val themeContext: Context = attach(context)
            applyTheme(themeContext)
            return getTheme(themeContext)
        }

        private fun ensureTheme(themeContext: ThemeContext) {
            if (themeContext.engineTheme == null) {
                themeContext.engineTheme = ThemeBuilder().create(null, "", true, null, themeContext)
            }
        }

        @JvmStatic
        fun getTheme(context: Context?): Theme {
            val themeContext: ThemeContext = requireThemeContext(context)
            ensureTheme(themeContext)
            return themeContext.engineTheme!!
        }

        @JvmStatic
        fun getColorScheme(context: Context): ColorScheme {
            val themeContext: ThemeContext = requireThemeContext(context)
            ensureTheme(themeContext)
            if (themeContext.colorScheme == null) {
                themeContext.colorScheme = ColorScheme(context, themeContext.engineTheme!!)
            }
            return themeContext.colorScheme!!
        }

        private fun shouldApplyStyle(context: Context?): Boolean {
            val layoutInflater = LayoutInflater.from(context)
            return layoutInflater is ThemeLayoutInflater && layoutInflater.isDirect()
        }

        @JvmStatic
        fun applyStyle(view: View) {
            // Stateful tints are buggy on Android 5.0, so some changes are applied to 5.1+ only
            val context = view.getContext()
            val themeContext: ThemeContext? = obtainThemeContext(context)
            if (themeContext != null) {
                ensureTheme(themeContext)
                val theme = themeContext.engineTheme
                if (shouldApplyStyle(context)) {
                    if (view is CompoundButton) {
                        view.setButtonTintList(themeContext.checkBoxColors)

                        if (view is Switch) {
                            val switchView = view
                            switchView.setTrackTintList(themeContext.checkBoxColors)
                            switchView.setThumbTintList(themeContext.switchThumbColors)
                        }
                    } else if (view is TextView) {
                        val textView = view
                        textView.setLinkTextColor(theme!!.link)
                        if (view is EditText) {
                            view.setBackgroundTintList(themeContext.editTextColors)
                        } else if (view is CheckedTextView) {
                            // Mostly used by alert dialogs to display lists
                            (textView as CheckedTextView).setCheckMarkTintList(themeContext.checkBoxColors)
                            // On newer Android versions "compound drawable" is used instead of "check mark"
                            textView.setCompoundDrawableTintList(themeContext.checkBoxColors)
                        } else if (view is Button) {
                            val button = view
                            if (button.getTextColors().getDefaultColor() ==
                                getColor(button.getContext(), android.R.attr.colorAccent)
                            ) {
                                button.setTextColor(themeContext.buttonColors)
                            }
                        }
                    } else if (view is ProgressBar) {
                        val tint = ColorStateList.valueOf(theme!!.accent)
                        val progressBar = view
                        progressBar.setIndeterminateTintList(tint)
                        progressBar.setProgressTintList(tint)
                        if (progressBar is AbsSeekBar) {
                            val seekBar = progressBar
                            seekBar.setThumbTintList(tint)
                            seekBar.setTickMarkTintList(tint)
                        }
                    } else if (view is ScrollView) {
                        setEdgeEffectColor(view, theme!!.accent)
                    }
                }
                Companion.handleTag(theme!!, view)
            }
        }

        private fun handleTag(
            theme: Theme,
            view: View,
        ) {
            val tag = view.getTag()
            if (tag is String) {
                val options = tag.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                var unhandledOptions: StringBuilder? = null
                for (option in options) {
                    var handled = false
                    val index = option.indexOf("=")
                    if (index >= 0) {
                        val name = option.substring(0, index)
                        val value = option.substring(index + 1)
                        if (name.startsWith("theme.")) {
                            handled = handleTagValue(theme, view, name.substring(6), value)
                        }
                    }
                    if (!handled) {
                        if (unhandledOptions == null) {
                            unhandledOptions = StringBuilder()
                        } else {
                            unhandledOptions.append(':')
                        }
                        unhandledOptions.append(option)
                    }
                }
                view.setTag(if (unhandledOptions != null) unhandledOptions.toString() else null)
            }
        }

        private fun handleTagValue(
            theme: Theme,
            view: View,
            name: String,
            value: String,
        ): Boolean {
            when (name) {
                "background" -> {
                    view.setBackgroundColor(theme.getColor(value))
                    return true
                }

                "textColor" -> {
                    if (view is TextView) {
                        view.setTextColor(theme.getColor(value))
                        return true
                    }
                    return false
                }
            }
            return false
        }

        fun addWeakOnOverlayFocusListener(
            context: Context?,
            listener: OnOverlayFocusListener,
        ) {
            requireThemeContext(context).overlayFocusListeners.register(listener)
        }

        /** Dirty hack, see [OverlayAttachListener.handleView]  */
        @JvmStatic
        fun markDecorAsDialog(decorView: View) {
            decorView.setTag(R.id.tag_theme_engine, true)
        }

        private val DEFAULT_THEME_RESOURCES = intArrayOf(R.raw.theme_normie, R.raw.theme_tomorrow)

        @JvmStatic
        fun addTheme(theme: Theme): Boolean {
            val existingTheme: Theme? = INSTANCE.themes!![theme.name]
            if (existingTheme != null && existingTheme.builtIn) {
                return false
            }
            getInstance().getItems()[theme.name] = theme.toJsonObject()
            getInstance().serialize()
            val installedThemesMap = HashMap(INSTANCE.themes!!)
            val iterator = installedThemesMap.values.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().builtIn) {
                    iterator.remove()
                }
            }
            INSTANCE.themes!!.keys.removeAll(installedThemesMap.keys)
            installedThemesMap[theme.name] = theme
            val installedThemes = ArrayList(installedThemesMap.values)
            installedThemes.sort()
            for (installedTheme in installedThemes) {
                INSTANCE.themes!![installedTheme.name] = installedTheme
            }
            return true
        }

        fun deleteTheme(name: String?): Boolean {
            val theme: Theme? = INSTANCE.themes!![name]
            if (theme == null || theme.builtIn) {
                return false
            }
            getInstance().getItems().remove(name)
            getInstance().serialize()
            INSTANCE.themes!!.remove(name)
            return true
        }

        @JvmStatic
        fun fastParseThemeFromText(
            context: Context?,
            text: String,
        ): Theme? {
            if (text.contains("\"base\"") && text.contains("\"name\"")) {
                val start = text.indexOf("{")
                val end = text.lastIndexOf("}") + 1
                if (start >= 0 && end > start) {
                    var jsonObject: JSONObject?
                    try {
                        jsonObject = JSONObject(text.substring(start, end))
                    } catch (e: JSONException) {
                        jsonObject = null
                    }
                    return if (jsonObject != null) parseTheme(context, jsonObject) else null
                }
            }
            return null
        }

        fun parseTheme(
            context: Context?,
            jsonObject: JSONObject,
        ): Theme? {
            try {
                return parseThemeInternal(context, jsonObject, false)
            } catch (e: JSONException) {
                e.printStackTrace()
                return null
            }
        }

        @Throws(JSONException::class)
        private fun parseThemeInternal(
            context: Context?,
            jsonObject: JSONObject,
            builtIn: Boolean,
        ): Theme {
            val baseString = jsonObject.getString("base")
            val base: Theme.Base?
            if ("light" == baseString) {
                base = Theme.Base.LIGHT
            } else if ("dark" == baseString) {
                base = Theme.Base.DARK
            } else {
                throw JSONException("Unknown base theme")
            }
            val name = jsonObject.optString("name")
            if (isEmpty(name)) {
                throw JSONException("Invalid theme name")
            }
            val builder = ThemeBuilder()
            for (entry in ThemeBuilder.Companion.MAP.entries) {
                val set = HashSet<String?>()
                set.add(entry.key)
                val color: Int? = resolveColor(jsonObject, entry.key, set)
                if (color != null) {
                    entry.value.setter.setColor(builder, color)
                }
            }
            return builder.create(
                base,
                name,
                builtIn,
                jsonObject.toString(),
                ContextThemeWrapper(context, base.resId),
            )
        }

        @Throws(JSONException::class)
        private fun resolveColor(
            jsonObject: JSONObject,
            name: String?,
            checked: HashSet<String?>,
        ): Int? {
            val value = emptyIfNull(jsonObject.optString(name))
            if (value.startsWith("#")) {
                return parseColor(value.substring(1))
            } else if (value.startsWith("@")) {
                val checkName = value.substring(1)
                if (checked.contains(checkName)) {
                    throw JSONException("Cyclical definition of \"" + name + "\"")
                }
                checked.add(checkName)
                return resolveColor(jsonObject, checkName, checked)
            } else if (!value.isEmpty()) {
                throw JSONException("Invalid color: " + value)
            }
            return null
        }

        @Throws(JSONException::class)
        private fun parseColor(color: String): Int {
            try {
                if (color.length == 8) {
                    val alpha = color.substring(0, 2).toInt(16)
                    val red = color.substring(2, 4).toInt(16)
                    val green = color.substring(4, 6).toInt(16)
                    val blue = color.substring(6, 8).toInt(16)
                    return Color.argb(alpha, red, green, blue)
                } else if (color.length == 6) {
                    val red = color.substring(0, 2).toInt(16)
                    val green = color.substring(2, 4).toInt(16)
                    val blue = color.substring(4, 6).toInt(16)
                    return Color.argb(0xff, red, green, blue)
                }
            } catch (e: NumberFormatException) {
                // Ignore exception
            }
            throw JSONException("Invalid color value: " + color)
        }
    }
}
