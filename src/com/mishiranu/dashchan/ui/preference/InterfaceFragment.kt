package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.fragment.app.FragmentManager
import chan.content.ChanMarkup
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.preference.core.CheckPreference
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.SharedPreferences

class InterfaceFragment : PreferenceFragment() {
    override fun getPreferences(): SharedPreferences = Preferences.prefs

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val scaleFormat = ResourceUtils.getColonString(resources, R.string.scale, "%d%%")
        addSeek(
            Preferences.KEY_TEXT_SCALE,
            Preferences.DEFAULT_TEXT_SCALE,
            getString(R.string.text_scale),
            scaleFormat,
            null,
            Preferences.MIN_TEXT_SCALE,
            Preferences.MAX_TEXT_SCALE,
            Preferences.STEP_TEXT_SCALE,
        ).setOnAfterChangeListener { requireActivity().recreate() }
        addSeek(
            Preferences.KEY_THUMBNAILS_SCALE,
            Preferences.DEFAULT_THUMBNAILS_SCALE,
            getString(R.string.thumbnail_scale),
            scaleFormat,
            null,
            Preferences.MIN_THUMBNAILS_SCALE,
            Preferences.MAX_THUMBNAILS_SCALE,
            Preferences.STEP_THUMBNAILS_SCALE,
        ).setOnAfterChangeListener { requireActivity().recreate() }
        addCheck(
            true,
            Preferences.KEY_CUT_THUMBNAILS,
            Preferences.DEFAULT_CUT_THUMBNAILS,
            R.string.crop_thumbnails,
            R.string.crop_thumbnails__summary,
        )
        addCheck(
            true,
            Preferences.KEY_ACTIVE_SCROLLBAR,
            Preferences.DEFAULT_ACTIVE_SCROLLBAR,
            R.string.active_scrollbar,
            0,
        )
        addCheck(
            true,
            Preferences.KEY_SCROLL_THREAD_GALLERY,
            Preferences.DEFAULT_SCROLL_THREAD_GALLERY,
            R.string.scroll_thread_when_scrolling_gallery,
            0,
        )
        addButton(R.string.themes, 0).setOnClickListener {
            (requireActivity() as FragmentHandler).pushFragment(ThemesFragment())
        }
        addList(
            Preferences.KEY_APP_ICON,
            enumList(Preferences.AppIcon.values()) { o -> o.value },
            Preferences.DEFAULT_APP_ICON.value,
            R.string.app_icon,
            enumResList(Preferences.AppIcon.values()) { o -> o.titleResId },
        ).setOnAfterChangeListener { applyAppIcon() }

        addHeader(R.string.navigation_drawer)
        addList(
            Preferences.KEY_PAGES_LIST,
            enumList(Preferences.PagesListMode.values()) { o -> o.value },
            Preferences.DEFAULT_PAGES_LIST.value,
            R.string.headers_order,
            enumResList(Preferences.PagesListMode.values()) { o -> o.titleResId },
        )
        addList(
            Preferences.KEY_DRAWER_INITIAL_POSITION,
            enumList(Preferences.DrawerInitialPosition.values()) { o -> o.value },
            Preferences.DEFAULT_DRAWER_INITIAL_POSITION.value,
            R.string.initial_position,
            enumResList(Preferences.DrawerInitialPosition.values()) { o -> o.titleResId },
        )

        addHeader(R.string.threads_list)
        addCheck(
            true,
            Preferences.KEY_PAGE_BY_PAGE,
            Preferences.DEFAULT_PAGE_BY_PAGE,
            R.string.paged_board_navigation,
            R.string.paged_board_navigation__summary,
        )
        addCheck(
            true,
            Preferences.KEY_DISPLAY_HIDDEN_THREADS,
            Preferences.DEFAULT_DISPLAY_HIDDEN_THREADS,
            R.string.display_hidden_threads,
            0,
        )
        addCheck(
            true,
            Preferences.KEY_SWIPE_TO_HIDE_THREAD,
            Preferences.DEFAULT_SWIPE_TO_HIDE_THREAD,
            R.string.swipe_to_hide_thread,
            0,
        )

        addHeader(R.string.posts_list)
        addEdit(
            Preferences.KEY_POST_MAX_LINES,
            Preferences.DEFAULT_POST_MAX_LINES,
            R.string.max_lines_count,
            R.string.max_lines_count__summary,
            null,
            InputType.TYPE_CLASS_NUMBER,
        )
        addCheck(
            true,
            Preferences.KEY_ALL_ATTACHMENTS,
            Preferences.DEFAULT_ALL_ATTACHMENTS,
            R.string.all_attachments,
            R.string.all_attachments__summary,
        )
        addList(
            Preferences.KEY_HIGHLIGHT_UNREAD,
            enumList(Preferences.HighlightUnreadMode.values()) { o -> o.value },
            Preferences.DEFAULT_HIGHLIGHT_UNREAD.value,
            R.string.highlight_unread_posts,
            enumResList(Preferences.HighlightUnreadMode.values()) { o -> o.titleResId },
        )
        addCheck(
            true,
            Preferences.KEY_ADVANCED_SEARCH,
            Preferences.DEFAULT_ADVANCED_SEARCH,
            R.string.advanced_search,
            R.string.advanced_search__summary,
        ).setOnAfterChangeListener { p ->
            if (p.value!!) {
                displayAdvancedSearchDialog(childFragmentManager)
            }
        }
        addCheck(
            true,
            Preferences.KEY_DISPLAY_ICONS,
            Preferences.DEFAULT_DISPLAY_ICONS,
            R.string.display_post_icons,
            R.string.display_post_icons__summary,
        )
        addCheck(
            true,
            Preferences.KEY_SHOW_IMPORTANT_POSTS_ON_FASTSCROLL_BAR,
            Preferences.DEFAULT_SHOW_IMPORTANT_POSTS_ON_FASTSCROLL_BAR,
            R.string.show_important_posts_on_fastscroll_bar,
            0,
        )
        addDependency(
            Preferences.KEY_SHOW_IMPORTANT_POSTS_ON_FASTSCROLL_BAR,
            Preferences.KEY_ACTIVE_SCROLLBAR,
            true,
        )
        addCheck(
            true,
            Preferences.KEY_SHOW_POSTS_BORDERS,
            Preferences.DEFAULT_SHOW_POSTS_BORDERS,
            R.string.show_posts_borders,
            0,
        )
        addCheck(
            true,
            Preferences.KEY_HIGHLIGHT_USER_POSTS,
            Preferences.DEFAULT_HIGHLIGHT_USER_POSTS,
            R.string.highlight_user_posts,
            0,
        )
        addCheck(
            true,
            Preferences.KEY_DISPLAY_HIDDEN_POSTS,
            Preferences.DEFAULT_DISPLAY_HIDDEN_POSTS,
            R.string.display_hidden_posts,
            R.string.display_hidden_posts__summary,
        )
        addHeader(R.string.submission_form)
        addCheck(
            true,
            Preferences.KEY_HIDE_PERSONAL_DATA,
            Preferences.DEFAULT_HIDE_PERSONAL_DATA,
            R.string.hide_personal_data_block,
            0,
        )
        addCheck(
            true,
            Preferences.KEY_SPACE_AFTER_QUOTE,
            Preferences.DEFAULT_SPACE_AFTER_QUOTE,
            R.string.space_after_quote,
            0,
        )
        addCheck(
            true,
            Preferences.KEY_HUGE_CAPTCHA,
            Preferences.DEFAULT_HUGE_CAPTCHA,
            R.string.huge_captcha,
            0,
        ).setOnAfterChangeListener {
            findPreference(Preferences.KEY_CAPTCHA_AUTO_RELOAD)!!.setEnabled(captchaAutoReloadEnabled())
        }
        addCheck(
            true,
            Preferences.KEY_CAPTCHA_TIMER,
            Preferences.DEFAULT_CAPTCHA_TIMER,
            R.string.captcha_show_ttl,
            R.string.captcha_show_ttl__summary,
        ).setOnAfterChangeListener {
            findPreference(Preferences.KEY_CAPTCHA_AUTO_RELOAD)!!.setEnabled(captchaAutoReloadEnabled())
        }
        addDependency(Preferences.KEY_CAPTCHA_TIMER, Preferences.KEY_HUGE_CAPTCHA, true)
        addCheck(
            true,
            Preferences.KEY_CAPTCHA_AUTO_RELOAD,
            Preferences.DEFAULT_CAPTCHA_AUTO_RELOAD,
            R.string.captcha_reload_automatically,
            R.string.captcha_reload_automatically__summary,
        ).setEnabled(captchaAutoReloadEnabled())

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.user_interface), null)
    }

    private fun applyAppIcon() {
        val context = requireContext()
        val packageManager = context.packageManager
        val selected = Preferences.appIcon
        // Enable the new alias before disabling the old one so a launcher entry always exists.
        for (icon in Preferences.AppIcon.values().sortedByDescending { it == selected }) {
            packageManager.setComponentEnabledSetting(
                ComponentName(context, "com.mishiranu.dashchan.ui.${icon.componentSuffix}"),
                if (icon == selected) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun captchaAutoReloadEnabled(): Boolean {
        val hugeCaptchaEnabled = (findPreference(Preferences.KEY_HUGE_CAPTCHA) as CheckPreference).value
        val captchaTimerEnabled = (findPreference(Preferences.KEY_CAPTCHA_TIMER) as CheckPreference).value
        return hugeCaptchaEnabled!! && captchaTimerEnabled!!
    }

    companion object {
        private val BUILDER_ADVANCED_SEARCH =
            ChanMarkup.MarkupBuilder { markup ->
                markup!!.addTag("b", ChanMarkup.TAG_BOLD)
            }

        private fun displayAdvancedSearchDialog(fragmentManager: FragmentManager) {
            InstanceDialog(fragmentManager, null) { provider ->
                val context: Context = provider.context
                val html = IOUtils.readRawResourceString(context.resources, R.raw.markup_advanced_search)
                AlertDialog
                    .Builder(context)
                    .setTitle(R.string.advanced_search)
                    .setMessage(BUILDER_ADVANCED_SEARCH.fromHtmlReduced(html))
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            }
        }
    }
}
