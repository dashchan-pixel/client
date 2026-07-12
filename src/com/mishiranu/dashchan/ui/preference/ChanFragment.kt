package com.mishiranu.dashchan.ui.preference

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanManager
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.http.HttpClient
import chan.http.HttpException
import chan.http.HttpHolder
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.HttpHolderTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.MultipleEditPreference
import com.mishiranu.dashchan.ui.preference.core.Preference
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ProgressDialog

class ChanFragment : PreferenceFragment, FragmentHandler.Callback {
	internal var captchaPassPreference: Preference<List<String>>? = null
	internal var userAuthorizationPreference: Preference<List<String>>? = null
	private var cookiePreference: Preference<*>? = null

	private var anotherDomainMode = false

	constructor()

	constructor(chanName: String?) {
		val args = Bundle()
		args.putString(EXTRA_CHAN_NAME, chanName)
		arguments = args
	}

	private fun getChanName(): String = requireArguments().getString(EXTRA_CHAN_NAME)!!

	override fun getPreferences(): SharedPreferences = Preferences.PREFERENCES

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val chanName = getChanName()
		val chan = Chan.get(chanName)
		val board = chan.configuration.safe().obtainBoard(null)
		val deleting = if (board.allowDeleting) chan.configuration.safe().obtainDeleting(null) else null

		if (!chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
			addEdit(Preferences.KEY_DEFAULT_BOARD_NAME.bind(chanName), null,
					R.string.default_starting_board, { p ->
						var text = p!!.value
						if (!StringUtils.isEmpty(text)) {
							val boardName = StringUtils.validateBoardName(text!!)
							text = if (boardName != null) {
								StringUtils.formatBoardTitle(chanName, boardName,
										Chan.get(chanName).configuration.getBoardTitle(boardName))
							} else {
								null
							}
						}
						text
					}, null, InputType.TYPE_CLASS_TEXT)
		}
		if (board.allowCatalog) {
			addCheck(true, Preferences.KEY_LOAD_CATALOG.bind(chanName), Preferences.DEFAULT_LOAD_CATALOG,
					R.string.load_catalog, R.string.load_catalog__summary)
		}
		if (deleting != null && deleting.password) {
			Preferences.getPassword(chan) // Ensure password existence
			addEdit(Preferences.KEY_PASSWORD.bind(chanName), null,
					R.string.password_for_removal, R.string.password_for_removal__summary,
					getString(R.string.password), InputType.TYPE_CLASS_TEXT or
					InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
					.setOnAfterChangeListener { p ->
						val value = p!!.value
						if (StringUtils.isEmpty(value)) {
							p!!.value = Preferences.getPassword(Chan.get(chanName))
							ClickableToast.show(R.string.new_password_was_generated)
						}
					}
		}
		val captchaTypes = chan.configuration.getSupportedCaptchaTypes()
		if (captchaTypes != null && captchaTypes.size > 1) {
			addList(Preferences.KEY_CAPTCHA.bind(chanName), Preferences.getCaptchaTypeValues(captchaTypes),
					Preferences.getCaptchaTypeDefaultValue(chan), R.string.captcha_type,
					Preferences.getCaptchaTypeEntries(chan, captchaTypes))
		}
		if (chan.configuration.getOption(ChanConfiguration.OPTION_ALLOW_CAPTCHA_PASS)) {
			val authorization = chan.configuration.safe().obtainCaptchaPass()
			if (authorization != null && authorization.fieldsCount > 0) {
				captchaPassPreference = addMultipleEdit(Preferences.KEY_CAPTCHA_PASS.bind(chanName),
						R.string.captcha_pass, R.string.captcha_pass__summary,
						authorization.hints?.asList(),
						createInputTypes(authorization.fieldsCount,
								InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
						MultipleEditPreference.ListValueCodec(authorization.fieldsCount))
				captchaPassPreference!!.setOnAfterChangeListener { p ->
					val values = p!!.value
					if (Preferences.checkHasMultipleValues(values)) {
						val dialog = AuthorizationDialog(getChanName(), AuthorizationType.CAPTCHA_PASS, values)
						dialog.show(childFragmentManager, AuthorizationDialog::class.java.name)
					}
				}
			}
		}
		if (chan.configuration.getOption(ChanConfiguration.OPTION_ALLOW_USER_AUTHORIZATION)) {
			val authorization = chan.configuration.safe().obtainUserAuthorization()
			if (authorization != null && authorization.fieldsCount > 0) {
				userAuthorizationPreference = addMultipleEdit(Preferences.KEY_USER_AUTHORIZATION.bind(chanName),
						R.string.user_authorization, 0,
						authorization.hints?.asList(),
						createInputTypes(authorization.fieldsCount,
								InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
						MultipleEditPreference.ListValueCodec(authorization.fieldsCount))
				userAuthorizationPreference!!.setOnAfterChangeListener { p ->
					val values = p!!.value
					if (Preferences.checkHasMultipleValues(values)) {
						val dialog = AuthorizationDialog(getChanName(), AuthorizationType.USER, values)
						dialog.show(childFragmentManager, AuthorizationDialog::class.java.name)
					}
				}
			}
		}
		val customPreferences = chan.configuration.customPreferences
		if (customPreferences != null) {
			for (preferenceHolder in customPreferences.entries) {
				val key = preferenceHolder.key
				val defaultValue = preferenceHolder.value
				val customPreference = chan.configuration.safe().obtainCustomPreference(key)
				if (customPreference != null && customPreference.title != null) {
					val preference = addCheck(false, key!!, defaultValue!!,
							customPreference.title, customPreference.summary)
					preference.value = chan.configuration.get(null, key, defaultValue!!)
					preference.setOnAfterChangeListener { p ->
						val callbackChan = Chan.get(chanName)
						callbackChan.configuration.set(null, preference.key, p!!.value)
						callbackChan.configuration.commit()
					}
				}
			}
		}
		cookiePreference = addButton(R.string.manage_cookies, 0)
		cookiePreference!!.setOnClickListener {
			(requireActivity() as FragmentHandler).pushFragment(CookiesFragment(chanName))
		}

		val domains = chan.locator.getChanHosts(true)
		val localMode = chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE) || domains.isEmpty()
		val httpsConfigurable = chan.locator.isHttpsConfigurable
		val canReadThreadPartially = chan.configuration.getOption(ChanConfiguration.OPTION_READ_THREAD_PARTIALLY)
		val aiAgentsPostingSupport = chan.configuration.getOption(ChanConfiguration.OPTION_AI_POSTING)
		if (!localMode || httpsConfigurable || canReadThreadPartially) {
			addHeader(R.string.connection)
		}
		if (!localMode) {
			anotherDomainMode = !domains.contains(chan.locator.preferredHost) || domains.size == 1 ||
					savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_ANOTHER_DOMAIN_MODE)
			if (anotherDomainMode) {
				addAnotherDomainPreference(domains[0])
			} else {
				val entries = ArrayList<CharSequence>(domains)
				entries.add(getString(R.string.another))
				val values = ArrayList<String>(domains)
				values.add(VALUE_CUSTOM_DOMAIN)
				values[0] = ""
				val domainPreference = addList(Preferences.KEY_DOMAIN.bind(chanName), values,
						values[0], R.string.domain_name, entries)
				domainPreference.setOnBeforeChangeListener { _, value ->
					if (VALUE_CUSTOM_DOMAIN == value) {
						anotherDomainMode = true
						val newDomainPreference = addAnotherDomainPreference(domains[0])
						movePreference(newDomainPreference, domainPreference)
						removePreference(domainPreference)
						newDomainPreference.performClick()
						false
					} else {
						true
					}
				}
			}
		}
		if (httpsConfigurable) {
			addCheck(true, Preferences.KEY_USE_HTTPS.bind(chanName), Preferences.DEFAULT_USE_HTTPS,
					R.string.secure_connection, R.string.secure_connection__summary)
		}
		if (!localMode) {
			val proxyPreference = addMultipleEdit<Map<String, String>>(
					Preferences.KEY_PROXY.bind(chanName), R.string.proxy, "%s:%s",
					listOf<CharSequence?>(getString(R.string.address), getString(R.string.port), null),
					listOf(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
							InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0),
					MultipleEditPreference.MapValueCodec(Preferences.KEYS_PROXY))
			proxyPreference.setValues(Preferences.KEYS_PROXY.indexOf(Preferences.SUB_KEY_PROXY_TYPE),
					Preferences.ENTRIES_PROXY_TYPE, Preferences.VALUES_PROXY_TYPE)
			proxyPreference.setOnAfterChangeListener { p ->
				val success = HttpClient.getInstance().checkProxyValid(p.value)
				if (!success) {
					ClickableToast.show(R.string.enter_valid_data)
					proxyPreference.performClick()
				}
			}
		}
		if (canReadThreadPartially) {
			addCheck(true, Preferences.KEY_PARTIAL_THREAD_LOADING.bind(chanName),
					Preferences.DEFAULT_PARTIAL_THREAD_LOADING, R.string.partial_thread_loading,
					R.string.partial_thread_loading__summary)
		}

		if (aiAgentsPostingSupport) {
			addHeader(R.string.ai_settings)
			addCheck(true, Preferences.KEY_HIDE_AI_POSTS.bind(chanName),
					Preferences.DEFAULT_HIDE_AI_POSTS, R.string.hide_ai_posts, 0)
		}

		addHeader(R.string.additional)
		addButton(R.string.uninstall_extension, 0).setOnClickListener {
			val innerChan = Chan.get(chanName)
			if (innerChan.name != null) {
				val intent = Intent(Intent.ACTION_DELETE)
						.setData(Uri.parse("package:" + innerChan.packageName))
				startActivity(intent)
			}
		}

		(requireActivity() as FragmentHandler).setTitleSubtitle(chan.configuration.getTitle(), null)
	}

	override fun onDestroyView() {
		super.onDestroyView()

		captchaPassPreference = null
		userAuthorizationPreference = null
		cookiePreference = null
	}

	override fun onResume() {
		super.onResume()

		if (!ChanManager.getInstance().isExistingChanName(getChanName())) {
			(requireActivity() as FragmentHandler).removeFragment()
		} else {
			// Check every time returned from cookies fragment
			removeCookiePreferenceIfNotNeeded()
		}
	}

	override fun onChansChanged(changed: Collection<String>, removed: Collection<String>) {
		if (changed.contains(getChanName()) || removed.contains(getChanName())) {
			// Don't bother with updating fragment
			(requireActivity() as FragmentHandler).removeFragment()
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putBoolean(EXTRA_ANOTHER_DOMAIN_MODE, anotherDomainMode)
	}

	private fun removeCookiePreferenceIfNotNeeded() {
		val cookiePreference = this.cookiePreference
		if (cookiePreference != null) {
			if (!ChanDatabase.getInstance().hasCookies(getChanName())) {
				removePreference(cookiePreference)
				this.cookiePreference = null
			}
		}
	}

	private fun addAnotherDomainPreference(primaryDomain: String): Preference<String> {
		val preference = addEdit(Preferences.KEY_DOMAIN.bind(getChanName()), "",
				R.string.domain_name, primaryDomain, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
		preference.setOnBeforeChangeListener { p, value ->
			if (primaryDomain == value) {
				p!!.value = ""
				false
			} else {
				true
			}
		}
		return preference
	}

	enum class AuthorizationType { CAPTCHA_PASS, USER }

	class AuthorizationDialog : DialogFragment {
		constructor()

		constructor(chanName: String, authorizationType: AuthorizationType, authorizationData: List<String>?) {
			val args = Bundle()
			args.putString(EXTRA_CHAN_NAME, chanName)
			args.putString(EXTRA_AUTHORIZATION_TYPE, authorizationType.name)
			args.putStringArrayList(EXTRA_AUTHORIZATION_DATA,
					if (authorizationData != null) ArrayList(authorizationData) else null)
			arguments = args
		}

		override fun onCreateDialog(savedInstanceState: Bundle?): ProgressDialog {
			val dialog = ProgressDialog(requireContext(), null)
			dialog.setMessage(getString(R.string.loading__ellipsis))
			return dialog
		}

		// View-less DialogFragment: onViewStateRestored never runs, so start the task from onStart.
		private var dialogInitialized = false

		override fun onStart() {
			if (!dialogInitialized) {
				dialogInitialized = true
				initializeDialog()
			}
			super.onStart()
		}

		private fun initializeDialog() {
			val viewModel = ViewModelProvider(this).get(CheckAuthorizationViewModel::class.java)
			if (!viewModel.hasTaskOrValue()) {
				val args = requireArguments()
				val chan = Chan.get(args.getString(EXTRA_CHAN_NAME))
				val task = CheckAuthorizationTask(viewModel, chan,
						AuthorizationType.valueOf(args.getString(EXTRA_AUTHORIZATION_TYPE)!!),
						args.getStringArrayList(EXTRA_AUTHORIZATION_DATA))
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
				viewModel.attach(task)
			}
			viewModel.observe(this) { result ->
				dismiss()
				if (result === CheckAuthorizationTask.SUCCESS) {
					ClickableToast.show(R.string.validation_completed)
				} else {
					ClickableToast.show(result)
					val chanFragment = parentFragment as ChanFragment
					val preference = when (AuthorizationType.valueOf(
							requireArguments().getString(EXTRA_AUTHORIZATION_TYPE)!!)) {
						AuthorizationType.CAPTCHA_PASS -> chanFragment.captchaPassPreference
						AuthorizationType.USER -> chanFragment.userAuthorizationPreference
					}
					preference?.performClick()
				}
			}
		}

		companion object {
			private const val EXTRA_CHAN_NAME = "chanName"
			private const val EXTRA_AUTHORIZATION_TYPE = "authorizationType"
			private const val EXTRA_AUTHORIZATION_DATA = "authorizationData"
		}
	}

	class CheckAuthorizationViewModel : TaskViewModel<CheckAuthorizationTask, ErrorItem>()

	class CheckAuthorizationTask(
			private val viewModel: CheckAuthorizationViewModel,
			private val chan: Chan,
			private val authorizationType: AuthorizationType,
			private val authorizationData: List<String>?
	) : HttpHolderTask<Void, ErrorItem>(chan) {
		override fun run(holder: HttpHolder): ErrorItem {
			try {
				val type = when (authorizationType) {
					AuthorizationType.CAPTCHA_PASS -> ChanPerformer.CheckAuthorizationData.TYPE_CAPTCHA_PASS
					AuthorizationType.USER -> ChanPerformer.CheckAuthorizationData.TYPE_USER_AUTHORIZATION
				}
				val result = chan.performer.safe()
						.onCheckAuthorization(ChanPerformer.CheckAuthorizationData(type,
								CommonUtils.toArray(authorizationData, String::class.java), holder))
				return if (result != null && result.success) SUCCESS
						else ErrorItem(ErrorItem.Type.INVALID_AUTHORIZATION_DATA)
			} catch (e: ExtensionException) {
				return e.getErrorItemAndHandle()
			} catch (e: HttpException) {
				return e.getErrorItemAndHandle()
			} catch (e: InvalidResponseException) {
				return e.getErrorItemAndHandle()
			} finally {
				chan.configuration.commit()
			}
		}

		override fun onComplete(result: ErrorItem) {
			viewModel.handleResult(result)
		}

		companion object {
			@JvmField val SUCCESS = ErrorItem("")
		}
	}

	companion object {
		private const val EXTRA_CHAN_NAME = "chanName"

		private const val VALUE_CUSTOM_DOMAIN = "custom_domain\n"
		private const val EXTRA_ANOTHER_DOMAIN_MODE = "anotherDomainMode"
	}
}
