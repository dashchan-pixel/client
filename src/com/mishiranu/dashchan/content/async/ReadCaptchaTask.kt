package com.mishiranu.dashchan.content.async

import android.graphics.Bitmap
import android.util.Pair
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.http.HttpException
import chan.http.HttpHolder
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.net.CaptchaSolving
import com.mishiranu.dashchan.content.net.RecaptchaReader
import com.mishiranu.dashchan.util.GraphicsUtils

class ReadCaptchaTask(private val callback: Callback, captchaReader: CaptchaReader?,
		private val captchaType: String?, private val requirement: String?, private val captchaPass: List<String>?,
		private val mayShowLoadButton: Boolean, private val allowSolveAutomatically: Boolean,
		private val chan: Chan, private val boardName: String?, private val threadNumber: String?) :
		ExecutorTask<Void, Pair<ErrorItem?, ReadCaptchaTask.Result?>?>() {
	private val chanHolder = HttpHolder(chan)
	private val fallbackHolder = HttpHolder(Chan.getFallback())

	private val captchaReader: CaptchaReader = captchaReader ?: ChanCaptchaReader(chan)

	interface Callback {
		fun onReadCaptchaSuccess(result: Result)
		fun onReadCaptchaError(errorItem: ErrorItem)
	}

	class Result(@JvmField val captchaState: CaptchaState?, @JvmField val captchaData: ChanPerformer.CaptchaData?,
			@JvmField val captchaType: String?, @JvmField val input: ChanConfiguration.Captcha.Input?,
			@JvmField val validity: ChanConfiguration.Captcha.Validity?, @JvmField val image: Bitmap?,
			@JvmField val large: Boolean, @JvmField val blackAndWhite: Boolean)

	enum class CaptchaState { CAPTCHA, SKIP, PASS, NEED_LOAD, MAY_LOAD, MAY_LOAD_SOLVING }

	class RemoteResult(@JvmField val result: ChanPerformer.ReadCaptchaResult,
			@JvmField val challengeExtra: Any?, @JvmField val allowSolveAutomatically: Boolean)

	fun interface CaptchaReader {
		@Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
		fun onReadCaptcha(data: ChanPerformer.ReadCaptchaData): RemoteResult
	}

	private class ChanCaptchaReader(private val chan: Chan) : CaptchaReader {
		override fun onReadCaptcha(data: ChanPerformer.ReadCaptchaData): RemoteResult {
			val result = chan.performer.safe().onReadCaptcha(data)
					?: throw ExtensionException(RuntimeException("Captcha result is null"))
			return RemoteResult(result, null, allowSolveAutomatically(chan.name))
		}
	}

	private enum class ForegroundCaptcha { RECAPTCHA_2, RECAPTCHA_2_INVISIBLE, HCAPTCHA }

	override fun run(): Pair<ErrorItem?, Result?>? {
		val result: RemoteResult
		try {
			chanHolder.use().use {
				result = captchaReader.onReadCaptcha(ChanPerformer.ReadCaptchaData(captchaType,
						@Suppress("UNCHECKED_CAST") (CommonUtils.toArray(captchaPass, String::class.java) as Array<String?>?), mayShowLoadButton,
						requirement, boardName, threadNumber, chanHolder))
			}
		} catch (e: ExtensionException) {
			return Pair(e.getErrorItemAndHandle(), null)
		} catch (e: HttpException) {
			return Pair(e.getErrorItemAndHandle(), null)
		} catch (e: InvalidResponseException) {
			return Pair(e.getErrorItemAndHandle(), null)
		} finally {
			chan.configuration.commit()
		}
		var captchaState: CaptchaState? = null
		val chanCaptchaState = result.result.captchaState
		if (chanCaptchaState != null) {
			captchaState = when (chanCaptchaState) {
				ChanPerformer.CaptchaState.CAPTCHA -> CaptchaState.CAPTCHA
				ChanPerformer.CaptchaState.SKIP -> CaptchaState.SKIP
				ChanPerformer.CaptchaState.PASS -> CaptchaState.PASS
				ChanPerformer.CaptchaState.NEED_LOAD -> CaptchaState.NEED_LOAD
			}
		}
		val captchaData = result.result.captchaData
		val loadedCaptchaType = result.result.captchaType
		val input = result.result.input
		val validity = result.result.validity
		var image: Bitmap? = null
		var large = false
		var blackAndWhite = false
		if (captchaState == CaptchaState.CAPTCHA) {
			if (isCancelled()) {
				return null
			}
			val foregroundCaptcha = checkForegroundCaptcha(loadedCaptchaType ?: this.captchaType)
			if (foregroundCaptcha != null) {
				try {
					fallbackHolder.use().use {
						captchaState = if (CaptchaSolving.getInstance().checkActive(fallbackHolder))
								CaptchaState.MAY_LOAD_SOLVING else CaptchaState.MAY_LOAD
						if (!mayShowLoadButton) {
							val response = readForegroundCaptcha(fallbackHolder, captchaData!!, foregroundCaptcha,
									result.challengeExtra, allowSolveAutomatically && result.allowSolveAutomatically)
							captchaData!!.put(RECAPTCHA_SKIP_RESPONSE, response)
							captchaState = CaptchaState.SKIP
						}
					}
				} catch (e: RecaptchaReader.CancelException) {
					// Ignore
				} catch (e: HttpException) {
					return Pair(e.getErrorItemAndHandle(), null)
				} catch (e: InterruptedException) {
					return if (isCancelled()) null else Pair(ErrorItem(ErrorItem.Type.UNKNOWN), null)
				}
			} else if (result.result.image != null) {
				image = result.result.image
				blackAndWhite = GraphicsUtils.isBlackAndWhiteCaptchaImage(image)
				image = if (blackAndWhite) GraphicsUtils.handleBlackAndWhiteCaptchaImage(image).first else image
				large = result.result.large
			} else {
				return Pair(ErrorItem(ErrorItem.Type.UNKNOWN), null)
			}
		}
		return Pair(null, Result(captchaState, captchaData, loadedCaptchaType,
				input, validity, image, large, blackAndWhite))
	}

	override fun cancel() {
		super.cancel()
		chanHolder.interrupt()
		fallbackHolder.interrupt()
	}

	override fun onComplete(result: Pair<ErrorItem?, Result?>?) {
		if (result!!.second != null) {
			callback.onReadCaptchaSuccess(result.second!!)
		} else {
			callback.onReadCaptchaError(result.first!!)
		}
	}

	companion object {
		const val RECAPTCHA_SKIP_RESPONSE = "recaptcha_skip_response"

		private fun allowSolveAutomatically(chanName: String?): Boolean {
			val chanNames = Preferences.captchaSolvingChans
			return chanNames!!.isEmpty() || chanNames!!.contains(chanName)
		}

		private fun checkForegroundCaptcha(captchaType: String?): ForegroundCaptcha? {
			return when (StringUtils.emptyIfNull(captchaType)) {
				ChanConfiguration.CAPTCHA_TYPE_RECAPTCHA_2 -> ForegroundCaptcha.RECAPTCHA_2
				ChanConfiguration.CAPTCHA_TYPE_RECAPTCHA_2_INVISIBLE -> ForegroundCaptcha.RECAPTCHA_2_INVISIBLE
				ChanConfiguration.CAPTCHA_TYPE_HCAPTCHA -> ForegroundCaptcha.HCAPTCHA
				else -> null
			}
		}

		@JvmStatic
		@Throws(HttpException::class, InterruptedException::class)
		fun readForegroundCaptcha(holder: HttpHolder, chanName: String?,
				captchaData: ChanPerformer.CaptchaData, captchaType: String?): String? {
			val foregroundCaptcha = checkForegroundCaptcha(captchaType) ?: return null
			return try {
				readForegroundCaptcha(holder, captchaData, foregroundCaptcha, null,
						allowSolveAutomatically(chanName))
			} catch (e: RecaptchaReader.CancelException) {
				null
			}
		}

		@Throws(HttpException::class, RecaptchaReader.CancelException::class, InterruptedException::class)
		private fun readForegroundCaptcha(holder: HttpHolder, captchaData: ChanPerformer.CaptchaData,
				foregroundCaptcha: ForegroundCaptcha, challengeExtra: Any?,
				allowSolveAutomatically: Boolean): String? {
			val apiKey = captchaData.get(ChanPerformer.CaptchaData.API_KEY)
			val referer = captchaData.get(ChanPerformer.CaptchaData.REFERER)
			val recaptchaReader = RecaptchaReader.getInstance()
			var recaptchaChallengeExtra = challengeExtra as? RecaptchaReader.ChallengeExtra
			if (recaptchaChallengeExtra == null) {
				recaptchaChallengeExtra = if (foregroundCaptcha == ForegroundCaptcha.HCAPTCHA) {
					recaptchaReader.getChallengeHcaptcha(holder,
							apiKey!!, referer, false, allowSolveAutomatically)
				} else {
					val invisible = foregroundCaptcha == ForegroundCaptcha.RECAPTCHA_2_INVISIBLE
					recaptchaReader.getChallenge2(holder,
							apiKey!!, invisible, referer, Preferences.isRecaptchaJavascript,
							false, allowSolveAutomatically)
				}
			}
			return recaptchaChallengeExtra!!.getResponse(holder)
		}
	}
}
