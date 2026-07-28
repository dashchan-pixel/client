package com.mishiranu.dashchan.ui.preference

import android.content.Context
import android.os.Bundle
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanManager
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import java.util.Locale

/**
 * Index of the settings reachable from the preferences tree, backing the search on
 * [CategoriesFragment]'s toolbar: it answers "which screen holds this setting?" and navigates there.
 *
 * The screens build their rows imperatively in `onViewCreated`, so there is no declarative model to
 * walk -- the titles have to be repeated here. [checkIndexed] keeps the two from drifting apart: in
 * a debug build, opening a screen that has a searchable row this table doesn't know about fails.
 *
 * Only titles and *static* summaries are indexed. A few summaries are computed from live state
 * ([GeneralFragment]'s captcha-solving status, the two cache sizes, the download directory) and
 * producing them costs a network request or a disk scan, so those rows are matched by title alone.
 */
object PreferenceSearch {
    /** A screen the index can navigate to. Per-forum settings live in [ChanFragment] instead. */
    enum class Screen(
        val titleResId: Int,
    ) {
        GENERAL(R.string.general),
        INTERFACE(R.string.user_interface),
        CONTENTS(R.string.contents),
        MEDIA(R.string.media),
        ;

        fun createFragment(): PreferenceFragment =
            when (this) {
                GENERAL -> GeneralFragment()
                INTERFACE -> InterfaceFragment()
                CONTENTS -> ContentsFragment()
                MEDIA -> MediaFragment()
            }
    }

    class Result internal constructor(
        val title: CharSequence,
        /** Where the setting lives, e.g. "User interface, Posts list". */
        val breadcrumb: CharSequence,
        private val createOwner: () -> PreferenceFragment,
    ) {
        /** The owning screen, primed to reveal [title] once it has built its rows. */
        fun createFragment(): PreferenceFragment {
            val fragment = createOwner()
            val arguments = fragment.arguments ?: Bundle().also { fragment.arguments = it }
            arguments.putString(PreferenceFragment.EXTRA_REVEAL_TITLE, title.toString())
            return fragment
        }
    }

    /** A row of one of the app's own screens. [sectionResId] is 0 for rows above the first header. */
    private class Entry(
        val screen: Screen,
        val sectionResId: Int,
        val titleResId: Int,
        val summaryResId: Int,
        val available: () -> Boolean = { true },
    )

    /**
     * A row of [ChanFragment], which adds each one only if the extension supports it. [available]
     * repeats that condition so the search never offers a setting the forum doesn't have.
     */
    private class ChanEntry(
        val sectionResId: Int,
        val titleResId: Int,
        val summaryResId: Int,
        val available: (Capabilities) -> Boolean,
    )

    /** A resolved row, from either table or from an extension's own preferences. */
    private class Row(
        val title: CharSequence,
        val summary: CharSequence?,
        val sectionResId: Int,
    )

    /**
     * The extension probes [ChanFragment] makes to decide which rows to add. Each one crosses into
     * the extension APK, so they are resolved lazily and cached for the duration of one search.
     */
    private class Capabilities(
        private val chan: Chan,
    ) {
        private val configuration = chan.configuration

        val singleBoardMode: Boolean by lazy {
            configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)
        }
        val board: ChanConfiguration.Board by lazy { configuration.safe().obtainBoard(null) }
        val deletingPassword: Boolean by lazy {
            board.allowDeleting && configuration.safe().obtainDeleting(null)?.password == true
        }
        val multipleCaptchaTypes: Boolean by lazy {
            val captchaTypes = configuration.getSupportedCaptchaTypes()
            captchaTypes != null && captchaTypes.size > 1
        }
        val captchaPass: Boolean by lazy {
            configuration.getOption(ChanConfiguration.OPTION_ALLOW_CAPTCHA_PASS) &&
                configuration.safe().obtainCaptchaPass().fieldsCount > 0
        }
        val userAuthorization: Boolean by lazy {
            configuration.getOption(ChanConfiguration.OPTION_ALLOW_USER_AUTHORIZATION) &&
                configuration.safe().obtainUserAuthorization().fieldsCount > 0
        }
        val localMode: Boolean by lazy {
            configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE) ||
                chan.locator.getChanHosts(true).isEmpty()
        }
        val httpsConfigurable: Boolean by lazy { chan.locator.isHttpsConfigurable }
        val readThreadPartially: Boolean by lazy {
            configuration.getOption(ChanConfiguration.OPTION_READ_THREAD_PARTIALLY)
        }
        val aiPosting: Boolean by lazy { configuration.getOption(ChanConfiguration.OPTION_AI_POSTING) }
        val cookies: Boolean by lazy {
            val chanName = chan.name
            chanName != null && ChanDatabase.getInstance().hasCookies(chanName)
        }

        /** The rows the extension itself contributes, which no table here could know about. */
        val customPreferences: List<Row> by lazy {
            configuration.customPreferences?.keys.orEmpty().mapNotNull { key ->
                val customPreference = configuration.safe().obtainCustomPreference(key)
                val title = customPreference?.title
                if (title != null) Row(title, customPreference.summary, 0) else null
            }
        }
    }

    private val ENTRIES =
        listOf(
            Entry(Screen.GENERAL, 0, R.string.language, 0),
            Entry(Screen.GENERAL, R.string.navigation, R.string.close_pages, R.string.close_pages__summary),
            Entry(Screen.GENERAL, R.string.navigation, R.string.remember_history, 0),
            Entry(Screen.GENERAL, R.string.navigation, R.string.merge_pages, R.string.merge_pages__summary) {
                ChanManager.getInstance().hasMultipleAvailableChans()
            },
            Entry(Screen.GENERAL, R.string.navigation, R.string.internal_browser, R.string.internal_browser__sumamry),
            Entry(Screen.GENERAL, R.string.navigation, R.string.ephemeral_browsing, R.string.ephemeral_browsing__summary),
            Entry(
                Screen.GENERAL,
                R.string.services,
                R.string.solve_invisible_recaptcha,
                R.string.solve_invisible_recaptcha__summary,
            ),
            Entry(Screen.GENERAL, R.string.services, R.string.captcha_solving, R.string.captcha_solving__summary),
            Entry(Screen.GENERAL, R.string.services, R.string.firewall_resolution_method, 0),
            Entry(Screen.GENERAL, R.string.connection, R.string.secure_connection, R.string.secure_connection__summary),
            Entry(Screen.GENERAL, R.string.connection, R.string.verify_certificate, R.string.verify_certificate__summary),
            Entry(Screen.GENERAL, R.string.repositories, R.string.client, 0),
            Entry(Screen.GENERAL, R.string.repositories, R.string.extensions, 0),
            Entry(Screen.GENERAL, R.string.repositories, R.string.themes, 0),
            Entry(Screen.INTERFACE, 0, R.string.text_scale, 0),
            Entry(Screen.INTERFACE, 0, R.string.thumbnail_scale, 0),
            Entry(Screen.INTERFACE, 0, R.string.ui_corner_radius, 0),
            Entry(Screen.INTERFACE, 0, R.string.crop_thumbnails, R.string.crop_thumbnails__summary),
            Entry(Screen.INTERFACE, 0, R.string.active_scrollbar, 0),
            Entry(Screen.INTERFACE, 0, R.string.scroll_thread_when_scrolling_gallery, 0),
            Entry(Screen.INTERFACE, 0, R.string.themes, 0),
            Entry(Screen.INTERFACE, 0, R.string.app_icon, 0),
            Entry(Screen.INTERFACE, R.string.navigation_drawer, R.string.headers_order, 0),
            Entry(Screen.INTERFACE, R.string.navigation_drawer, R.string.initial_position, 0),
            Entry(
                Screen.INTERFACE,
                R.string.threads_list,
                R.string.paged_board_navigation,
                R.string.paged_board_navigation__summary,
            ),
            Entry(Screen.INTERFACE, R.string.threads_list, R.string.display_hidden_threads, 0),
            Entry(Screen.INTERFACE, R.string.threads_list, R.string.swipe_to_hide_thread, 0),
            Entry(Screen.INTERFACE, R.string.posts_list, R.string.max_lines_count, R.string.max_lines_count__summary),
            Entry(Screen.INTERFACE, R.string.posts_list, R.string.all_attachments, R.string.all_attachments__summary),
            Entry(Screen.INTERFACE, R.string.posts_list, R.string.highlight_unread_posts, 0),
            Entry(Screen.INTERFACE, R.string.posts_list, R.string.advanced_search, R.string.advanced_search__summary),
            Entry(
                Screen.INTERFACE,
                R.string.posts_list,
                R.string.display_post_icons,
                R.string.display_post_icons__summary,
            ),
            Entry(Screen.INTERFACE, R.string.posts_list, R.string.post_swipe_action, 0),
            Entry(Screen.INTERFACE, R.string.posts_list, R.string.show_important_posts_on_fastscroll_bar, 0),
            Entry(Screen.INTERFACE, R.string.posts_list, R.string.show_posts_borders, 0),
            Entry(Screen.INTERFACE, R.string.posts_list, R.string.highlight_user_posts, 0),
            Entry(
                Screen.INTERFACE,
                R.string.posts_list,
                R.string.display_hidden_posts,
                R.string.display_hidden_posts__summary,
            ),
            Entry(Screen.INTERFACE, R.string.submission_form, R.string.hide_personal_data_block, 0),
            Entry(Screen.INTERFACE, R.string.submission_form, R.string.space_after_quote, 0),
            Entry(
                Screen.INTERFACE,
                R.string.submission_form,
                R.string.markup_buttons_at_bottom,
                R.string.markup_buttons_at_bottom__summary,
            ),
            Entry(Screen.INTERFACE, R.string.submission_form, R.string.huge_captcha, 0),
            Entry(
                Screen.INTERFACE,
                R.string.submission_form,
                R.string.captcha_show_ttl,
                R.string.captcha_show_ttl__summary,
            ),
            Entry(
                Screen.INTERFACE,
                R.string.submission_form,
                R.string.captcha_reload_automatically,
                R.string.captcha_reload_automatically__summary,
            ),
            Entry(
                Screen.INTERFACE,
                R.string.submission_form,
                R.string.hide_captcha_pass_block,
                R.string.hide_captcha_pass_block__summary,
            ),
            Entry(Screen.CONTENTS, R.string.threads, R.string.refresh_open_thread, R.string.every_number_sec__format),
            Entry(Screen.CONTENTS, R.string.threads, R.string.cyclical_threads_refresh_mode, 0),
            Entry(Screen.CONTENTS, R.string.favorites, R.string.favorite_threads_order, 0),
            Entry(Screen.CONTENTS, R.string.favorites, R.string.add_thread_on_reply, 0),
            Entry(Screen.CONTENTS, R.string.favorites, R.string.follow_thread_continuation, 0),
            Entry(
                Screen.CONTENTS,
                R.string.favorites,
                R.string.remove_finished_thread,
                R.string.remove_finished_thread__summary,
            ),
            Entry(Screen.CONTENTS, R.string.favorites, R.string.watch_initially, R.string.watch_initially__summary),
            Entry(
                Screen.CONTENTS,
                R.string.favorites_watcher,
                R.string.refresh_favorites,
                R.string.every_number_sec__format,
            ),
            Entry(
                Screen.CONTENTS,
                R.string.favorites_watcher,
                R.string.board_intervals,
                R.string.board_intervals__summary,
            ),
            Entry(Screen.CONTENTS, R.string.favorites_watcher, R.string.wifi_only, 0),
            Entry(
                Screen.CONTENTS,
                R.string.favorites_watcher,
                R.string.reply_notifications,
                R.string.reply_notifications__format,
            ),
            Entry(Screen.CONTENTS, R.string.favorites_watcher, R.string.echo, R.string.echo__summary),
            Entry(Screen.CONTENTS, R.string.additional, R.string.clear_cache, 0),
            Entry(Screen.MEDIA, R.string.images, R.string.load_thumbnails, 0),
            Entry(Screen.MEDIA, R.string.images, R.string.load_nearest_image, 0),
            Entry(Screen.MEDIA, R.string.posting_media, R.string.always_unique_hash, 0),
            Entry(Screen.MEDIA, R.string.posting_media, R.string.always_clear_metadata, 0),
            Entry(Screen.MEDIA, R.string.posting_media, R.string.always_remove_filename, 0),
            Entry(Screen.MEDIA, R.string.posting_media, R.string.always_rename_files, 0),
            Entry(Screen.MEDIA, R.string.posting_media, R.string.new_filename, 0),
            Entry(Screen.MEDIA, R.string.downloads, R.string.detailed_file_name, R.string.detailed_file_name__summary),
            Entry(Screen.MEDIA, R.string.downloads, R.string.original_file_name, R.string.original_file_name__summary),
            Entry(Screen.MEDIA, R.string.downloads, R.string.download_directory, 0),
            Entry(Screen.MEDIA, R.string.downloads, R.string.show_download_configuration_dialog, 0),
            Entry(Screen.MEDIA, R.string.downloads, R.string.subdirectory_pattern, 0),
            Entry(
                Screen.MEDIA,
                R.string.downloads,
                R.string.notify_when_download_is_completed,
                R.string.notify_when_download_is_completed__summary,
            ),
            Entry(Screen.MEDIA, R.string.downloads, R.string.standard_action_when_loading_media, 0),
            Entry(
                Screen.MEDIA,
                R.string.video_player,
                R.string.use_built_in_video_player,
                R.string.use_built_in_video_player__summary,
            ),
            Entry(Screen.MEDIA, R.string.video_player, R.string.action_on_playback_completion, 0),
            Entry(Screen.MEDIA, R.string.video_player, R.string.play_after_scroll, R.string.play_after_scroll__summary),
            Entry(Screen.MEDIA, R.string.video_player, R.string.seek_any_frame, R.string.seek_any_frame__summary),
            Entry(Screen.MEDIA, R.string.video_player, R.string.multi_tap_seek, R.string.multi_tap_seek__summary),
            Entry(Screen.MEDIA, R.string.video_player, R.string.playback_speed_options, 0),
            Entry(Screen.MEDIA, R.string.additional, R.string.cache_size, 0),
            Entry(Screen.MEDIA, R.string.additional, R.string.clear_cache, 0),
        )

    private val CHAN_ENTRIES =
        listOf(
            ChanEntry(0, R.string.default_starting_board, 0) { !it.singleBoardMode },
            ChanEntry(0, R.string.load_catalog, R.string.load_catalog__summary) { it.board.allowCatalog },
            ChanEntry(0, R.string.password_for_removal, R.string.password_for_removal__summary) { it.deletingPassword },
            ChanEntry(0, R.string.captcha_type, 0) { it.multipleCaptchaTypes },
            ChanEntry(0, R.string.captcha_pass, R.string.captcha_pass__summary) { it.captchaPass },
            ChanEntry(0, R.string.user_authorization, 0) { it.userAuthorization },
            ChanEntry(0, R.string.manage_cookies, 0) { it.cookies },
            ChanEntry(R.string.connection, R.string.domain_name, 0) { !it.localMode },
            ChanEntry(R.string.connection, R.string.secure_connection, R.string.secure_connection__summary) {
                it.httpsConfigurable
            },
            ChanEntry(R.string.connection, R.string.proxy, 0) { !it.localMode },
            ChanEntry(R.string.connection, R.string.partial_thread_loading, R.string.partial_thread_loading__summary) {
                it.readThreadPartially
            },
            ChanEntry(R.string.ai_settings, R.string.hide_ai_posts, 0) { it.aiPosting },
            ChanEntry(R.string.additional, R.string.uninstall_extension, 0) { true },
        )

    /**
     * Settings whose title matches [query], or whose static summary does. Per-forum settings come
     * last, one group per installed extension.
     */
    fun search(
        context: Context,
        query: String,
    ): List<Result> {
        val normalized = query.trim().lowercase(Locale.getDefault())
        if (normalized.isEmpty()) {
            return emptyList()
        }
        val results = ArrayList<Result>()
        for (entry in ENTRIES) {
            val title = context.getString(entry.titleResId)
            val summary = if (entry.summaryResId != 0) context.getString(entry.summaryResId) else null
            if (entry.available() && matches(normalized, title, summary)) {
                val screen = entry.screen
                results.add(
                    Result(title, breadcrumb(context, context.getString(screen.titleResId), entry.sectionResId)) {
                        screen.createFragment()
                    },
                )
            }
        }
        for (chan in ChanManager.getInstance().availableChans) {
            val chanName = chan.name ?: continue
            val screenTitle = chan.configuration.getTitle() ?: chanName
            for (row in chanRows(context, chan, includeTransient = false)) {
                if (matches(normalized, row.title, row.summary)) {
                    results.add(
                        Result(row.title, breadcrumb(context, screenTitle, row.sectionResId)) {
                            ChanFragment(chanName)
                        },
                    )
                }
            }
        }
        return results
    }

    /**
     * Debug-only guard against this index going stale: every searchable row [fragment] built has to
     * be indexed, otherwise a setting added later would silently be unfindable. Screens that only
     * navigate ([CategoriesFragment], [ChansFragment]) have nothing to check.
     */
    internal fun checkIndexed(
        fragment: PreferenceFragment,
        titles: List<CharSequence>,
    ) {
        val indexed = indexedTitles(fragment) ?: return
        val missing = titles.filter { it.toString() !in indexed }
        if (missing.isNotEmpty()) {
            error(
                "${fragment.javaClass.simpleName} rows missing from PreferenceSearch: " +
                    missing.joinToString(", ") + ". Add them to ENTRIES/CHAN_ENTRIES.",
            )
        }
    }

    private fun indexedTitles(fragment: PreferenceFragment): Set<String>? {
        val context = fragment.requireContext()
        if (fragment is ChanFragment) {
            val chanName = fragment.arguments?.getString(ChanFragment.EXTRA_CHAN_NAME) ?: return null
            return chanRows(context, Chan.get(chanName), includeTransient = true)
                .mapTo(HashSet()) { it.title.toString() }
        }
        val screen =
            when (fragment) {
                is GeneralFragment -> Screen.GENERAL
                is InterfaceFragment -> Screen.INTERFACE
                is ContentsFragment -> Screen.CONTENTS
                is MediaFragment -> Screen.MEDIA
                else -> return null
            }
        return ENTRIES
            .filter { it.screen == screen && it.available() }
            .mapTo(HashSet()) { context.getString(it.titleResId) }
    }

    /**
     * [includeTransient] keeps the "Manage cookies" row, which [ChanFragment] adds unconditionally
     * and only drops in `onResume` once it knows there are no cookies: the search must not offer it
     * for a forum that has none, but [checkIndexed] still sees it on the freshly built screen.
     */
    private fun chanRows(
        context: Context,
        chan: Chan,
        includeTransient: Boolean,
    ): List<Row> {
        val capabilities = Capabilities(chan)
        val rows = ArrayList<Row>()
        for (entry in CHAN_ENTRIES) {
            val transient = entry.titleResId == R.string.manage_cookies
            if ((includeTransient && transient) || entry.available(capabilities)) {
                rows.add(
                    Row(
                        context.getString(entry.titleResId),
                        if (entry.summaryResId != 0) context.getString(entry.summaryResId) else null,
                        entry.sectionResId,
                    ),
                )
            }
        }
        rows.addAll(capabilities.customPreferences)
        return rows
    }

    private fun breadcrumb(
        context: Context,
        screenTitle: CharSequence,
        sectionResId: Int,
    ): CharSequence =
        if (sectionResId != 0) {
            "$screenTitle, ${context.getString(sectionResId)}"
        } else {
            screenTitle
        }

    private fun matches(
        query: String,
        title: CharSequence,
        summary: CharSequence?,
    ): Boolean = contains(title, query) || contains(summary, query)

    private fun contains(
        text: CharSequence?,
        query: String,
    ): Boolean = text != null && text.toString().lowercase(Locale.getDefault()).contains(query)
}
