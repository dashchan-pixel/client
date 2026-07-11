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

class GeneralFragment : PreferenceFragment(), FragmentHandler.Callback, ChanMultiChoiceDialog.Callback {
	private var captchaSolvingPreference: MultipleEditPreference<Map<String, String>>? = null
	private var captchaSolvingCheckDialog: ProgressDialog? = null

	/** Repository-URI keys currently showing a custom-value edit field rather than the [Default, Another] list. */
	private val anotherUriKeys = HashSet<String>()

	override fun getPreferences(): SharedPreferences = Preferences.PREFERENCES

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		savedInstanceState?.getStringArrayList(EXTRA_ANOTHER_URI_KEYS)?.let { anotherUriKeys.addAll(it) }

		addList(Preferences.KEY_LOCALE, LocaleManager.VALUES_LOCALE, LocaleManager.DEFAULT_LOCALE,
				R.string.language, LocaleManager.ENTRIES_LOCALE)
				.setOnAfterChangeListener { requireActivity().recreate() }

		addHeader(R.string.navigation)
		addCheck(true, Preferences.KEY_CLOSE_ON_BACK, Preferences.DEFAULT_CLOSE_ON_BACK,
				R.string.close_pages, R.string.close_pages__summary)
		addCheck(true, Preferences.KEY_REMEMBER_HISTORY, Preferences.DEFAULT_REMEMBER_HISTORY,
				R.string.remember_history, 0)
		if (ChanManager.getInstance().hasMultipleAvailableChans()) {
			addCheck(true, Preferences.KEY_MERGE_CHANS, Preferences.DEFAULT_MERGE_CHANS,
					R.string.merge_pages, R.string.merge_pages__summary)
		}
		addCheck(true, Preferences.KEY_INTERNAL_BROWSER, Preferences.DEFAULT_INTERNAL_BROWSER,
				R.string.internal_browser, R.string.internal_browser__sumamry)

		addHeader(R.string.services)
		addCheck(true, Preferences.KEY_RECAPTCHA_JAVASCRIPT, Preferences.DEFAULT_RECAPTCHA_JAVASCRIPT,
				R.string.use_javascript_for_recaptcha, R.string.use_javascript_for_recaptcha__summary)

		captchaSolvingPreference = addMultipleEdit(Preferences.KEY_CAPTCHA_SOLVING, R.string.captcha_solving,
				{ configureCaptchaSolvingSummary(false) },
				listOf<CharSequence>("Endpoint", "Token", getString(R.string.timeout_sec)),
				listOf(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
						InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
						InputType.TYPE_CLASS_NUMBER),
				MultipleEditPreference.MapValueCodec(Preferences.KEYS_CAPTCHA_SOLVING))
		captchaSolvingPreference!!.setOnAfterChangeListener { configureCaptchaSolvingSummary(true) }
		captchaSolvingPreference!!.setDescription(getString(R.string.captcha_solving_info__sentence))
		configureCaptchaSolvingNeutralButton()
		addList(Preferences.KEY_FIREWALL_RESOLUTION_METHOD,
				enumList(Preferences.FirewallResolutionMethod.values()) { v -> v.value },
				Preferences.DEFAULT_FIREWALL_RESOLUTION_METHOD.value, R.string.firewall_resolution_method,
				enumResList(Preferences.FirewallResolutionMethod.values()) { v -> v.titleResId })

		addHeader(R.string.connection)
		addButton(0, R.string.specific_to_internal_services__sentence).setSelectable(false)
		addCheck(true, Preferences.KEY_USE_HTTPS_GENERAL, Preferences.DEFAULT_USE_HTTPS,
				R.string.secure_connection, R.string.secure_connection__summary)
		addCheck(true, Preferences.KEY_VERIFY_CERTIFICATE, Preferences.DEFAULT_VERIFY_CERTIFICATE,
				R.string.verify_certificate, R.string.verify_certificate__summary)

		addHeader(R.string.repositories)
		addRepositoryUri(Preferences.KEY_URI_UPDATES, R.string.updates, BuildConfig.URI_UPDATES)
		addRepositoryUri(Preferences.KEY_URI_UPDATES_EXTENSIONS, R.string.updates_extensions,
				BuildConfig.URI_UPDATES_EXTENSIONS)
		addRepositoryUri(Preferences.KEY_URI_THEMES, R.string.themes, BuildConfig.URI_THEMES)
		addRepositoryUri(Preferences.KEY_URI_METADATA, R.string.metadata, BuildConfig.GITHUB_URI_METADATA)

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
			captchaSolvingPreference!!.invalidate()
			if (captchaSolvingCheckDialog != null) {
				captchaSolvingCheckDialog!!.dismiss()
				captchaSolvingCheckDialog = null
				if (result.second != null) {
					ClickableToast.show(R.string.validation_completed)
				} else {
					ClickableToast.show(result.first)
					captchaSolvingPreference!!.performClick()
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
	private fun addRepositoryUri(key: String, titleResId: Int, default: String) {
		val stored = Preferences.PREFERENCES.getString(key, "")
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

	private fun addAnotherUri(key: String, titleResId: Int, default: String): Preference<String> {
		return addEdit(key, "", titleResId, default,
				InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
	}

	override fun onDestroyView() {
		super.onDestroyView()

		captchaSolvingPreference = null
		captchaSolvingCheckDialog?.let {
			it.dismiss()
			captchaSolvingCheckDialog = null
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putStringArrayList(EXTRA_ANOTHER_URI_KEYS, ArrayList(anotherUriKeys))
	}

	override fun onChansChanged(changed: Collection<String>, removed: Collection<String>) {
		configureCaptchaSolvingNeutralButton()
	}

	override fun onChansSelected(chanNames: Collection<String>) {
		Preferences.setCaptchaSolvingChans(chanNames)
	}

	private fun configureCaptchaSolvingNeutralButton() {
		if (ChanManager.getInstance().getAvailableChans().iterator().hasNext()) {
			captchaSolvingPreference!!.setNeutralButton(getString(R.string.forums)) {
				ChanMultiChoiceDialog(Preferences.getCaptchaSolvingChans()).show(this)
			}
		} else {
			captchaSolvingPreference!!.setNeutralButton(null, null)
		}
	}

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
			val extraMap = viewModel.extraMap
			val errorItem = viewModel.errorItem
			return if (extraMap != null) {
				val builder = SpannableStringBuilder()
				builder.append(getString(R.string.validation_completed))
				if (extraMap.isNotEmpty()) {
					for (entry in extraMap.entries) {
						builder.append('\n')
						val start = builder.length
						builder.append(entry.key).append(": ").append(entry.value)
						val end = builder.length
						builder.setSpan(MonospaceSpan(false), start, end,
								SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
					}
				}
				builder
			} else if (errorItem != null) {
				val builder = SpannableStringBuilder(errorItem.toString())
				builder.setSpan(ForegroundColorSpan(ResourceUtils.getColor(requireContext(),
						R.attr.colorTextError)), 0, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
				builder
			} else {
				getString(R.string.loading__ellipsis)
			}
		} else {
			return getString(R.string.captcha_solving__summary)
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

	class CheckCaptchaSolvingTask(private val viewModel: CheckViewModel) :
			HttpHolderTask<Void?, Pair<ErrorItem, Map<String, String>>>(Chan.getFallback()) {
		override fun run(holder: HttpHolder): Pair<ErrorItem, Map<String, String>> {
			return try {
				val extra = CaptchaSolving.getInstance().checkService(holder)
				Pair<ErrorItem, Map<String, String>>(null, extra)
			} catch (e: HttpException) {
				Pair<ErrorItem, Map<String, String>>(e.getErrorItemAndHandle(), null)
			} catch (e: CaptchaSolving.UnsupportedServiceException) {
				Pair<ErrorItem, Map<String, String>>(ErrorItem(ErrorItem.Type.UNSUPPORTED_SERVICE), null)
			} catch (e: CaptchaSolving.InvalidTokenException) {
				Pair<ErrorItem, Map<String, String>>(ErrorItem(ErrorItem.Type.INVALID_AUTHORIZATION_DATA), null)
			}
		}

		override fun onComplete(result: Pair<ErrorItem, Map<String, String>>) {
			viewModel.handleResult(result)
		}
	}

	companion object {
		private const val VALUE_CUSTOM_URI = "custom_uri\n"
		private const val EXTRA_ANOTHER_URI_KEYS = "anotherUriKeys"
	}
}
