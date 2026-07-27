package com.mishiranu.dashchan.ui.gallery

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Window
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelStoreOwner
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.widget.ThemeEngine

class GalleryInstance(
    @JvmField val context: Context,
    @JvmField var callback: Callback,
    @JvmField val actionBarColor: Int,
    @JvmField val chanName: String?,
    galleryItems: List<GalleryItem>,
) {
    /** The whole gallery in the order it was opened with — post order — the base of every view. */
    private val postOrderItems = galleryItems.toList()

    private val displayedItems = ArrayList(galleryItems)

    /**
     * The files on display, filtered and ordered. The grid and the pager adapters hold this very
     * list, so [setMediaType] and [setSort] rewrite it in place and both of them follow.
     */
    @JvmField val galleryItems: List<GalleryItem> = displayedItems

    /** How many files the gallery holds in total, whatever [mediaType] currently shows. */
    val totalCount: Int
        get() = postOrderItems.size

    /**
     * The media types this gallery actually holds, in menu order, each with its file count. A type
     * no file matches is absent, so a chooser built from this never offers an empty gallery.
     */
    val mediaTypeCounts: Map<MediaType, Int> = countMediaTypes(postOrderItems, get(chanName))

    /**
     * The sorting criteria that mean something for these files: one no file carries the data for
     * (sizes or dimensions the chan doesn't report, a single file type) is left out.
     */
    val sortOptions: List<Preferences.GallerySort> = collectSortOptions(postOrderItems, get(chanName))

    /** The media type on display, or null for all of them. Not remembered beyond this gallery. */
    var mediaType: MediaType? = null
        private set

    /**
     * The criterion [galleryItems] is ordered by. Seeded from the remembered choice, unless this
     * gallery is one that criterion says nothing about.
     */
    var sort: Preferences.GallerySort =
        Preferences.gallerySort.takeIf { sortOptions.contains(it) } ?: Preferences.GallerySort.POST_ORDER
        private set

    /** Whether the gallery shows every file in post order, the state it opens in by default. */
    val isDefaultOrder: Boolean
        get() = mediaType == null && sort == Preferences.GallerySort.POST_ORDER

    init {
        applyFilterAndSort()
    }

    /** Rewrites [galleryItems] in place. Returns whether what it holds changed. */
    fun setMediaType(mediaType: MediaType?): Boolean {
        if (mediaType == this.mediaType) {
            return false
        }
        this.mediaType = mediaType
        applyFilterAndSort()
        return true
    }

    /**
     * Reorders [galleryItems] in place, and remembers the criterion for the next gallery. Returns
     * whether the order changed.
     */
    fun setSort(sort: Preferences.GallerySort): Boolean {
        Preferences.gallerySort = sort
        if (sort == this.sort) {
            return false
        }
        this.sort = sort
        applyFilterAndSort()
        return true
    }

    private fun applyFilterAndSort() {
        val chan = get(chanName)
        val mediaType = this.mediaType
        val filtered =
            if (mediaType != null) {
                postOrderItems.filter { mediaTypeOf(it, chan) == mediaType }
            } else {
                postOrderItems
            }
        val comparator = createComparator(sort)
        displayedItems.clear()
        // sortedWith is stable, so the files a criterion cannot tell apart keep their post order
        displayedItems.addAll(if (comparator != null) filtered.sortedWith(comparator) else filtered)
    }

    private fun createComparator(sort: Preferences.GallerySort): Comparator<GalleryItem>? {
        val chan = get(chanName)
        return when (sort) {
            Preferences.GallerySort.POST_ORDER -> {
                null
            }

            // Chan file names are timestamps and dumps are numbered, so ascending reads as
            // "as they were uploaded" for the former and as the poster's own order for the latter
            Preferences.GallerySort.NAME -> {
                Comparator { lhs, rhs -> compareNatural(displayName(lhs, chan), displayName(rhs, chan)) }
            }

            // The point of these two is finding the largest file, so they go descending; files the
            // chan reports nothing about have a zero key and end up last
            Preferences.GallerySort.SIZE -> {
                Comparator { lhs, rhs -> rhs.size.compareTo(lhs.size) }
            }

            Preferences.GallerySort.RESOLUTION -> {
                Comparator { lhs, rhs -> pixels(rhs).compareTo(pixels(lhs)) }
            }

            // Stills first, then videos, with equal extensions adjacent within either group
            Preferences.GallerySort.TYPE -> {
                Comparator { lhs, rhs ->
                    val videos = lhs.isVideo(chan).compareTo(rhs.isVideo(chan))
                    if (videos != 0) {
                        videos
                    } else {
                        StringUtils.compare(extension(lhs, chan), extension(rhs, chan), true)
                    }
                }
            }
        }
    }

    /** The kinds of file a gallery can hold, as the filter chooser offers them. */
    enum class MediaType {
        IMAGE,
        GIF,
        VIDEO,
        ;

        fun title(context: Context): String =
            when (this) {
                IMAGE -> context.getString(R.string.images)

                // A format name, the same word everywhere
                GIF -> "GIF"

                VIDEO -> context.getString(R.string.videos)
            }
    }

    interface Flags {
        companion object {
            const val LOCKED_USER = 0x00000001
            const val LOCKED_GRID = 0x00000002
            const val LOCKED_ERROR = 0x00000004
        }
    }

    interface Callback : ViewModelStoreOwner {
        fun getWindow(): Window?

        fun getChildFragmentManager(): FragmentManager

        fun downloadGalleryItem(galleryItem: GalleryItem)

        fun downloadGalleryItems(galleryItems: List<GalleryItem>)

        fun modifyVerticalSwipeState(
            ignoreIfGallery: Boolean,
            value: Float,
        )

        fun updateTitle()

        /** The filter bar has narrowed or reordered [galleryItems] under the grid. */
        fun onGalleryFilterChanged()

        fun navigateGalleryOrFinish(enableGalleryMode: Boolean)

        fun navigatePageFromList(position: Int)

        fun navigatePost(
            galleryItem: GalleryItem,
            manually: Boolean,
            force: Boolean,
        )

        /** Switch to the video feed (flow) at the currently viewed attachment. */
        fun switchToFlow()

        /** Continue the currently viewed video in the floating picture-in-picture player. */
        fun switchToPip()

        fun isAllowNavigatePostManually(fromPager: Boolean): Boolean

        fun invalidateOptionsMenu()

        fun setScreenOnFixed(fixed: Boolean)

        fun isGalleryWindow(): Boolean

        fun isGalleryMode(): Boolean

        fun isSystemUiVisible(): Boolean

        fun modifySystemUiVisibility(
            flag: Int,
            value: Boolean,
        )

        fun toggleSystemUIVisibility(flag: Int)
    }

    companion object {
        private const val GIF_EXTENSION = "gif"

        private fun mediaTypeOf(
            galleryItem: GalleryItem,
            chan: Chan,
        ): MediaType =
            when {
                galleryItem.isVideo(chan) -> MediaType.VIDEO
                GIF_EXTENSION == extension(galleryItem, chan) -> MediaType.GIF
                else -> MediaType.IMAGE
            }

        private fun countMediaTypes(
            galleryItems: List<GalleryItem>,
            chan: Chan,
        ): Map<MediaType, Int> {
            val counts = LinkedHashMap<MediaType, Int>()
            for (mediaType in MediaType.entries) {
                val count = galleryItems.count { mediaTypeOf(it, chan) == mediaType }
                if (count > 0) {
                    counts[mediaType] = count
                }
            }
            return counts
        }

        private fun collectSortOptions(
            galleryItems: List<GalleryItem>,
            chan: Chan,
        ): List<Preferences.GallerySort> {
            val options = ArrayList<Preferences.GallerySort>()
            // Post order and file names are there for every gallery; the rest depend on what the
            // chan reports about these files
            options.add(Preferences.GallerySort.POST_ORDER)
            options.add(Preferences.GallerySort.NAME)
            if (galleryItems.any { it.size > 0 }) {
                options.add(Preferences.GallerySort.SIZE)
            }
            if (galleryItems.any { pixels(it) > 0 }) {
                options.add(Preferences.GallerySort.RESOLUTION)
            }
            if (galleryItems.mapTo(HashSet()) { extension(it, chan) }.size > 1) {
                options.add(Preferences.GallerySort.TYPE)
            }
            return options
        }

        /** The name the gallery shows for a file: the poster's own one when the chan keeps it. */
        private fun displayName(
            galleryItem: GalleryItem,
            chan: Chan,
        ): String? =
            if (!StringUtils.isEmpty(galleryItem.originalName)) {
                galleryItem.originalName
            } else {
                galleryItem.getFileName(chan)
            }

        private fun extension(
            galleryItem: GalleryItem,
            chan: Chan,
        ): String? = StringUtils.getFileExtension(galleryItem.getFileName(chan))

        private fun pixels(galleryItem: GalleryItem): Long = galleryItem.width.toLong() * galleryItem.height

        /**
         * Compares names the way a file manager does: a run of digits compares as a number, so
         * 2.jpg comes before 10.jpg. Numbered dumps and the numeric file names most chans generate
         * are exactly what plain lexicographic order gets wrong.
         */
        private fun compareNatural(
            first: String?,
            second: String?,
        ): Int {
            if (first == null || second == null) {
                return StringUtils.compare(first, second, true)
            }
            var i = 0
            var j = 0
            while (i < first.length && j < second.length) {
                if (first[i].isDigit() && second[j].isDigit()) {
                    var firstEnd = i
                    while (firstEnd < first.length && first[firstEnd].isDigit()) {
                        firstEnd++
                    }
                    var secondEnd = j
                    while (secondEnd < second.length && second[secondEnd].isDigit()) {
                        secondEnd++
                    }
                    // Leading zeros don't count towards the value, but a shorter number is smaller
                    var firstStart = i
                    while (firstStart < firstEnd - 1 && first[firstStart] == '0') {
                        firstStart++
                    }
                    var secondStart = j
                    while (secondStart < secondEnd - 1 && second[secondStart] == '0') {
                        secondStart++
                    }
                    val digits = firstEnd - firstStart
                    if (digits != secondEnd - secondStart) {
                        return digits - (secondEnd - secondStart)
                    }
                    for (k in 0..<digits) {
                        val result = first[firstStart + k] - second[secondStart + k]
                        if (result != 0) {
                            return result
                        }
                    }
                    i = firstEnd
                    j = secondEnd
                } else {
                    val result = first[i].uppercaseChar().compareTo(second[j].uppercaseChar())
                    if (result != 0) {
                        return result
                    }
                    i++
                    j++
                }
            }
            return (first.length - i) - (second.length - j)
        }

        @JvmStatic
        fun getCallback(provider: InstanceDialog.Provider): Callback = provider.parentFragment as Callback

        /**
         * A context for the gallery's own popups (context menus, dialogs). The gallery window is themed
         * with [com.mishiranu.dashchan.R.style.Theme_Gallery], which is fullscreen — not a floating
         * theme — so the [ThemeEngine] layout inflater it produces is not "direct". Dialogs inflated
         * from it therefore skip [ThemeEngine]'s rounded-corner window background (it only applies to
         * direct app surfaces), leaving gallery menus square while every other menu is rounded.
         *
         * This keeps the gallery Dialog's window token (so the popup stays attached to the immersive
         * gallery window) but re-themes it with the normal app theme and re-attaches it to the
         * [ThemeEngine], yielding a direct inflater. The popup then matches the rest of the app: the
         * user's theme colours and the global corner radius.
         */
        @JvmStatic
        fun menuContext(windowContext: Context): Context {
            val base = ThemeEngine.getTheme(windowContext).base
            val themed =
                if (base != null) ContextThemeWrapper(windowContext, base.resId) else windowContext
            return ThemeEngine.attach(themed)
        }
    }
}
