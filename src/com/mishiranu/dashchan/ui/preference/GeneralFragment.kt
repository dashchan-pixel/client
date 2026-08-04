package com.mishiranu.dashchan.ui.preference

import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Pair
import android.view.View
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan
import chan.content.ChanManager
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.BuildConfig
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.LocaleManager
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.HttpHolderTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.net.CaptchaSolving
import com.mishiranu.dashchan.content.net.ProxyProvider
import com.mishiranu.dashchan.text.style.MonospaceSpan
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.MultipleEditPreference
import com.mishiranu.dashchan.ui.preference.core.Preference
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ProgressDialog

class GeneralFragment :
    PreferenceFragment(),
    FragmentHandler.Callback,
    ChanMultiChoiceDialog.Callback {
    private var captchaSolvingPreference: MultipleEditPreference<Map<String, String>>? = null
    private var captchaSolvingCheckDialog: ProgressDialog? = null

    private var proxyProviderPreference: MultipleEditPreference<Map<String, String>>? = null
    private var proxyProviderCheckDialog: ProgressDialog? = null

    /** Repository-URI keys currently showing a custom-value edit field rather than the [Default, Another] list. */
    private val anotherUriKeys = HashSet<String>()

    override fun getPreferences(): SharedPreferences = Preferences.prefs

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.getStringArrayList(EXTRA_ANOTHER_URI_KEYS)?.let { anotherUriKeys.addAll(it) }

        addList(
            Preferences.KEY_LOCALE,
            LocaleManager.VALUES_LOCALE,
            LocaleManager.DEFAULT_LOCALE,
            R.string.language,
            LocaleManager.ENTRIES_LOCALE,
        ).setOnAfterChangeListener { requireActivity().recreate() }

        addHeader(R.string.navigation)
        addCheck(
            true,
            Preferences.KEY_CLOSE_ON_BACK,
            Preferences.DEFAULT_CLOSE_ON_BACK,
            R.string.close_pages,
            R.string.close_pages__summary,
        )
        addCheck(
            true,
            Preferences.KEY_REMEMBER_HISTORY,
            Preferences.DEFAULT_REMEMBER_HISTORY,
            R.string.remember_history,
            0,
        )
        if (ChanManager.getInstance().hasMultipleAvailableChans()) {
            addCheck(
                true,
                Preferences.KEY_MERGE_CHANS,
                Preferences.DEFAULT_MERGE_CHANS,
                R.string.merge_pages,
                R.string.merge_pages__summary,
            )
        }
        addCheck(
            true,
            Preferences.KEY_INTERNAL_BROWSER,
            Preferences.DEFAULT_INTERNAL_BROWSER,
            R.string.internal_browser,
            R.string.internal_browser__sumamry,
        )
        addCheck(
            true,
            Preferences.KEY_EPHEMERAL_BROWSING,
            Preferences.DEFAULT_EPHEMERAL_BROWSING,
            R.string.ephemeral_browsing,
            R.string.ephemeral_browsing__summary,
        )
        addDependency(Preferences.KEY_EPHEMERAL_BROWSING, Preferences.KEY_INTERNAL_BROWSER, true)

        addHeader(R.string.services)

        val captchaSolvingPreference =
            addMultipleEdit(
                Preferences.KEY_CAPTCHA_SOLVING,
                R.string.captcha_solving,
                { configureCaptchaSolvingSummary(false) },
                listOf<CharSequence>("Endpoint", "Token", getString(R.string.timeout_sec)),
                listOf(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_CLASS_NUMBER,
                ),
                MultipleEditPreference.MapValueCodec(Preferences.KEYS_CAPTCHA_SOLVING),
            )
        this.captchaSolvingPreference = captchaSolvingPreference
        captchaSolvingPreference.setOnAfterChangeListener { configureCaptchaSolvingSummary(true) }
        captchaSolvingPreference.setDescription(getString(R.string.captcha_solving_info__sentence))
        configureCaptchaSolvingNeutralButton()

        val proxyProviderPreference =
            addMultipleEdit(
                Preferences.KEY_PROXY_PROVIDER,
                R.string.proxy_provider,
                { configureProxyProviderSummary(false) },
                listOf<CharSequence?>(
                    getString(R.string.api_key),
                    getString(R.string.country),
                    null,
                ),
                listOf(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    0,
                ),
                MultipleEditPreference.MapValueCodec(Preferences.KEYS_PROXY_PROVIDER),
            )
        this.proxyProviderPreference = proxyProviderPreference
        proxyProviderPreference.setValues(
            Preferences.KEYS_PROXY_PROVIDER.indexOf(Preferences.SUB_KEY_PROXY_PROVIDER_TYPE),
            Preferences.ENTRIES_PROXY_TYPE,
            Preferences.VALUES_PROXY_TYPE,
        )
        proxyProviderPreference.setOnAfterChangeListener { configureProxyProviderSummary(true) }
        proxyProviderPreference.setDescription(getString(R.string.proxy_provider_info__sentence))
        configureProxyProviderNeutralButton()

        addList(
            Preferences.KEY_FIREWALL_RESOLUTION_METHOD,
            enumList(Preferences.FirewallResolutionMethod.values()) { v -> v.value },
            Preferences.DEFAULT_FIREWALL_RESOLUTION_METHOD.value,
            R.string.firewall_resolution_method,
            enumResList(Preferences.FirewallResolutionMethod.values()) { v -> v.titleResId },
        )

        addHeader(R.string.connection)
        addButton(0, R.string.specific_to_internal_services__sentence).setSelectable(false)
        addCheck(
            true,
            Preferences.KEY_USE_HTTPS_GENERAL,
            Preferences.DEFAULT_USE_HTTPS,
            R.string.secure_connection,
            R.string.secure_connection__summary,
        )
        addCheck(
            true,
            Preferences.KEY_VERIFY_CERTIFICATE,
            Preferences.DEFAULT_VERIFY_CERTIFICATE,
            R.string.verify_certificate,
            R.string.verify_certificate__summary,
        )

        addHeader(R.string.repositories)
        addRepositoryUri(Preferences.KEY_URI_UPDATES, R.string.client, BuildConfig.URI_UPDATES)
        addRepositoryUri(
            Preferences.KEY_URI_UPDATES_EXTENSIONS,
            R.string.extensions,
            BuildConfig.URI_UPDATES_EXTENSIONS,
        )
        addRepositoryUri(Preferences.KEY_URI_THEMES, R.string.themes, BuildConfig.URI_THEMES)

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.general), null)
        val viewModel = ViewModelProvider(this).get(CheckViewModel::class.java)
        if (viewModel.showDialog) {
            displayCaptchaSolvingCheckDialog()
        }
        viewModel.observe(viewLifecycleOwner) { result ->
            result!!
            viewModel.showDialog = false
            viewModel.errorItem = result.first
            viewModel.extraMap = result.second
            captchaSolvingPreference.invalidate()
            if (captchaSolvingCheckDialog != null) {
                captchaSolvingCheckDialog?.dismiss()
                captchaSolvingCheckDialog = null
                if (result.second != null) {
                    ClickableToast.show(R.string.validation_completed)
                } else {
                    ClickableToast.show(result.first)
                    captchaSolvingPreference.performClick()
                }
            }
        }

        val proxyProviderViewModel = ViewModelProvider(this).get(ProxyProviderCheckViewModel::class.java)
        if (proxyProviderViewModel.showDialog) {
            displayProxyProviderCheckDialog()
        }
        proxyProviderViewModel.observe(viewLifecycleOwner) { result ->
            result!!
            proxyProviderViewModel.showDialog = false
            proxyProviderViewModel.errorItem = result.first
            proxyProviderViewModel.extraMap = result.second
            proxyProviderPreference.invalidate()
            if (proxyProviderCheckDialog != null) {
                proxyProviderCheckDialog?.dismiss()
                proxyProviderCheckDialog = null
                if (result.second != null) {
                    ClickableToast.show(R.string.validation_completed)
                } else {
                    ClickableToast.show(result.first)
                    proxyProviderPreference.performClick()
                }
            }
        }
    }

    /**
     * Repository-source preference in the dvach-domain style: a [Default, Another] list that swaps to a free-text
     * edit field when "Another" is chosen. An empty stored value means "use the built-in default" (see
     * [Preferences.getUriUpdates] and friends), so the default URI is shown as the first list entry and as the
     * edit field's hint.
     */
    private fun addRepositoryUri(
        key: String,
        titleResId: Int,
        default: String,
    ) {
        val stored = Preferences.prefs.getString(key, "")
        if (anotherUriKeys.contains(key) || !stored.isNullOrEmpty()) {
            anotherUriKeys.add(key)
            addAnotherUri(key, titleResId, default)
        } else {
            val entries = listOf<CharSequence>(default, getString(R.string.another))
            val values = listOf("", VALUE_CUSTOM_URI)
            val listPreference = addList(key, values, "", titleResId, entries)
            listPreference.setOnBeforeChangeListener { _, value ->
                if (VALUE_CUSTOM_URI == value) {
                    anotherUriKeys.add(key)
                    val editPreference = addAnotherUri(key, titleResId, default)
                    movePreference(editPreference, listPreference)
                    removePreference(listPreference)
                    editPreference.performClick()
                    false
                } else {
                    true
                }
            }
        }
    }

    private fun addAnotherUri(
        key: String,
        titleResId: Int,
        default: String,
    ): Preference<String> =
        addEdit(
            key,
            "",
            titleResId,
            default,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )

    override fun onDestroyView() {
        super.onDestroyView()

        captchaSolvingPreference = null
        captchaSolvingCheckDialog?.let {
            it.dismiss()
            captchaSolvingCheckDialog = null
        }
        proxyProviderPreference = null
        proxyProviderCheckDialog?.let {
            it.dismiss()
            proxyProviderCheckDialog = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(EXTRA_ANOTHER_URI_KEYS, ArrayList(anotherUriKeys))
    }

    override fun onChansChanged(
        changed: Collection<String>,
        removed: Collection<String>,
    ) {
        configureCaptchaSolvingNeutralButton()
        configureProxyProviderNeutralButton()
    }

    override fun onChansSelected(
        chanNames: Collection<String>,
        target: String?,
    ) {
        when (target) {
            TARGET_PROXY_PROVIDER -> {
                Preferences.proxyProviderChans = HashSet(chanNames)
                // The selection only means anything once the port has been written to the forums it
                // now covers -- and taken off the ones it no longer does, which the check does too
                configureProxyProviderSummary(true)
                proxyProviderPreference?.invalidate()
            }

            else -> {
                Preferences.captchaSolvingChans = HashSet(chanNames)
            }
        }
    }

    private fun configureCaptchaSolvingNeutralButton() {
        val captchaSolvingPreference = captchaSolvingPreference!!
        if (hasAvailableChans()) {
            captchaSolvingPreference.setNeutralButton(getString(R.string.forums)) {
                ChanMultiChoiceDialog(Preferences.captchaSolvingChans, TARGET_CAPTCHA_SOLVING).show(this)
            }
        } else {
            captchaSolvingPreference.setNeutralButton(null, null)
        }
    }

    private fun configureProxyProviderNeutralButton() {
        val proxyProviderPreference = proxyProviderPreference!!
        if (hasAvailableChans()) {
            proxyProviderPreference.setNeutralButton(getString(R.string.forums)) {
                ChanMultiChoiceDialog(Preferences.proxyProviderChans, TARGET_PROXY_PROVIDER).show(this)
            }
        } else {
            proxyProviderPreference.setNeutralButton(null, null)
        }
    }

    private fun hasAvailableChans(): Boolean =
        ChanManager
            .getInstance()
            .availableChans
            .iterator()
            .hasNext()

    private fun configureCaptchaSolvingSummary(resetAndShowDialog: Boolean): CharSequence {
        val viewModel = ViewModelProvider(this).get(CheckViewModel::class.java)
        if (resetAndShowDialog) {
            viewModel.showDialog = false
            viewModel.extraMap = null
            viewModel.errorItem = null
            viewModel.attach(null)
            viewModel.handleResult(null)
        }
        if (CaptchaSolving.getInstance().hasConfiguration()) {
            if (resetAndShowDialog) {
                viewModel.showDialog = true
                displayCaptchaSolvingCheckDialog()
            }
            if (viewModel.extraMap == null && viewModel.errorItem == null && !viewModel.hasTaskOrValue()) {
                val task = CheckCaptchaSolvingTask(viewModel)
                task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
                viewModel.attach(task)
            }
            return buildCheckSummary(viewModel.extraMap, viewModel.errorItem)
        } else {
            return getString(R.string.captcha_solving__summary)
        }
    }

    /** What a checked service reports about itself, or why the check failed. */
    private fun buildCheckSummary(
        extraMap: Map<String, String>?,
        errorItem: ErrorItem?,
    ): CharSequence =
        if (extraMap != null) {
            val builder = SpannableStringBuilder()
            builder.append(getString(R.string.validation_completed))
            if (extraMap.isNotEmpty()) {
                for (entry in extraMap.entries) {
                    builder.append('\n')
                    val start = builder.length
                    builder.append(entry.key).append(": ").append(entry.value)
                    val end = builder.length
                    builder.setSpan(
                        MonospaceSpan(false),
                        start,
                        end,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            builder
        } else if (errorItem != null) {
            val builder = SpannableStringBuilder(errorItem.toString())
            builder.setSpan(
                ForegroundColorSpan(
                    ResourceUtils.getColor(
                        requireContext(),
                        R.attr.colorTextError,
                    ),
                ),
                0,
                builder.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder
        } else {
            getString(R.string.loading__ellipsis)
        }

    private fun configureProxyProviderSummary(resetAndShowDialog: Boolean): CharSequence {
        val viewModel = ViewModelProvider(this).get(ProxyProviderCheckViewModel::class.java)
        if (resetAndShowDialog) {
            viewModel.showDialog = false
            viewModel.extraMap = null
            viewModel.errorItem = null
            viewModel.attach(null)
            viewModel.handleResult(null)
        }
        if (ProxyProvider.hasConfiguration()) {
            if (resetAndShowDialog) {
                viewModel.showDialog = true
                displayProxyProviderCheckDialog()
            }
            if (viewModel.extraMap == null && viewModel.errorItem == null && !viewModel.hasTaskOrValue()) {
                val task = CheckProxyProviderTask(viewModel)
                task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
                viewModel.attach(task)
            }
            return buildCheckSummary(viewModel.extraMap, viewModel.errorItem)
        } else {
            if (resetAndShowDialog) {
                // The settings that put the provider's proxy into the forums are gone: take it back
                ProxyProvider.clearFromChans()
            }
            return getString(R.string.proxy_provider__summary)
        }
    }

    private fun displayProxyProviderCheckDialog() {
        if (proxyProviderCheckDialog == null) {
            val dialog = ProgressDialog(requireContext(), null)
            proxyProviderCheckDialog = dialog
            dialog.setMessage(getString(R.string.loading__ellipsis))
            dialog.setOnCancelListener {
                proxyProviderCheckDialog = null
                val viewModel = ViewModelProvider(this).get(ProxyProviderCheckViewModel::class.java)
                viewModel.showDialog = false
                viewModel.attach(null)
                viewModel.handleResult(Pair<ErrorItem, Map<String, String>>(ErrorItem(ErrorItem.Type.UNKNOWN), null))
            }
            dialog.show()
        }
    }

    private fun displayCaptchaSolvingCheckDialog() {
        if (captchaSolvingCheckDialog == null) {
            val dialog = ProgressDialog(requireContext(), null)
            captchaSolvingCheckDialog = dialog
            dialog.setMessage(getString(R.string.loading__ellipsis))
            dialog.setOnCancelListener {
                captchaSolvingCheckDialog = null
                val viewModel = ViewModelProvider(this).get(CheckViewModel::class.java)
                viewModel.showDialog = false
                viewModel.attach(null)
                viewModel.handleResult(Pair<ErrorItem, Map<String, String>>(ErrorItem(ErrorItem.Type.UNKNOWN), null))
            }
            dialog.show()
        }
    }

    class CheckViewModel : TaskViewModel<CheckCaptchaSolvingTask, Pair<ErrorItem, Map<String, String>>?>() {
        internal var showDialog = false
        internal var extraMap: Map<String, String>? = null
        internal var errorItem: ErrorItem? = null
    }

    class CheckCaptchaSolvingTask(
        private val viewModel: CheckViewModel,
    ) : HttpHolderTask<Unit?, Pair<ErrorItem, Map<String, String>>>(Chan.getFallback()) {
        override fun run(holder: HttpHolder): Pair<ErrorItem, Map<String, String>> =
            try {
                val extra = CaptchaSolving.getInstance().checkService(holder)
                Pair<ErrorItem, Map<String, String>>(null, extra)
            } catch (e: HttpException) {
                Pair<ErrorItem, Map<String, String>>(e.getErrorItemAndHandle(), null)
            } catch (e: CaptchaSolving.UnsupportedServiceException) {
                Pair<ErrorItem, Map<String, String>>(ErrorItem(ErrorItem.Type.UNSUPPORTED_SERVICE), null)
            } catch (e: CaptchaSolving.InvalidTokenException) {
                Pair<ErrorItem, Map<String, String>>(ErrorItem(ErrorItem.Type.INVALID_AUTHORIZATION_DATA), null)
            }

        override fun onComplete(result: Pair<ErrorItem, Map<String, String>>) {
            viewModel.handleResult(result)
        }
    }

    class ProxyProviderCheckViewModel : TaskViewModel<CheckProxyProviderTask, Pair<ErrorItem, Map<String, String>>?>() {
        internal var showDialog = false
        internal var extraMap: Map<String, String>? = null
        internal var errorItem: ErrorItem? = null
    }

    class CheckProxyProviderTask(
        private val viewModel: ProxyProviderCheckViewModel,
    ) : HttpHolderTask<Unit?, Pair<ErrorItem, Map<String, String>>>(Chan.getFallback()) {
        override fun run(holder: HttpHolder): Pair<ErrorItem, Map<String, String>> =
            try {
                val extra = ProxyProvider.checkService(holder)
                Pair<ErrorItem, Map<String, String>>(null, extra)
            } catch (e: HttpException) {
                Pair<ErrorItem, Map<String, String>>(e.getErrorItemAndHandle(), null)
            } catch (e: ProxyProvider.ServiceException) {
                Pair<ErrorItem, Map<String, String>>(e.errorItem, null)
            }

        override fun onComplete(result: Pair<ErrorItem, Map<String, String>>) {
            viewModel.handleResult(result)
        }
    }

    companion object {
        private const val VALUE_CUSTOM_URI = "custom_uri\n"
        private const val EXTRA_ANOTHER_URI_KEYS = "anotherUriKeys"

        /** Both service rows open the forum selection; this is how their answers are told apart. */
        private const val TARGET_CAPTCHA_SOLVING = "captchaSolving"
        private const val TARGET_PROXY_PROVIDER = "proxyProvider"
    }
}
