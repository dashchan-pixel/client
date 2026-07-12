package com.mishiranu.dashchan.content.async

import chan.content.ApiException
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.content.RedirectException
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.MultipartEntity
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.text.HtmlParser
import com.mishiranu.dashchan.text.SimilarTextEstimator

class SendPostTask<Key>(private val key: Key, private val callback: Callback<Key>?,
		private val chan: Chan, private val data: ChanPerformer.SendPostData) :
		ExecutorTask<LongArray, Boolean>() {
	private val chanHolder = HttpHolder(chan)
	private val fallbackHolder = HttpHolder(Chan.getFallback())

	private val progressMode = data.attachments != null

	private var result: ChanPerformer.SendPostResult? = null
	private var errorItem: ErrorItem? = null
	private var extra: ApiException.Extra? = null
	private var captchaError = false
	private var keepCaptcha = false

	private val progressHandler = object : TimedProgressHandler() {
		override fun onProgressChange(progress: Long, progressMax: Long) {
			if (!progressMode) {
				notifyProgress(longArrayOf(0L, progress, progressMax))
			}
		}

		private val completeOpenables = ArrayList<MultipartEntity.Openable>()
		private var lastOpenable: MultipartEntity.Openable? = null

		override fun onProgressChange(openable: MultipartEntity.Openable, progress: Long, progressMax: Long) {
			if (progressMode) {
				if (lastOpenable !== openable) {
					val lastOpenable = lastOpenable
					if (lastOpenable != null) {
						completeOpenables.add(lastOpenable)
						if (completeOpenables.size == data.attachments!!.size) {
							completeOpenables.clear()
						}
					}
					this.lastOpenable = openable
				}
				notifyProgress(longArrayOf(completeOpenables.size.toLong(), progress, progressMax))
			}
		}
	}

	interface Callback<Key> {
		fun onSendPostChangeProgressState(key: Key, progressState: ProgressState,
				attachmentIndex: Int, attachmentsCount: Int)
		fun onSendPostChangeProgressValue(key: Key, progress: Long, progressMax: Long)
		fun onSendPostSuccess(key: Key, data: ChanPerformer.SendPostData,
				chanName: String?, threadNumber: String?, postNumber: PostNumber?)
		fun onSendPostFail(key: Key, data: ChanPerformer.SendPostData, chanName: String?, errorItem: ErrorItem?,
				extra: ApiException.Extra?, captchaError: Boolean, keepCaptcha: Boolean)
	}

	init {
		if (progressMode) {
			for (attachment in data.attachments) {
				attachment!!.listener = progressHandler
			}
		}
	}

	fun isProgressMode(): Boolean = progressMode

	enum class ProgressState { CONNECTING, SENDING, PROCESSING }

	private var lastProgressState = ProgressState.CONNECTING

	private fun switchProgressState(progressState: ProgressState, attachmentIndex: Int, force: Boolean) {
		if (lastProgressState != progressState || force) {
			lastProgressState = progressState
			callback?.onSendPostChangeProgressState(key, progressState, attachmentIndex,
					if (progressMode) data.attachments!!.size else 0)
		}
	}

	private fun updateProgressValue(index: Int, progress: Long, progressMax: Long) {
		if (progress == 0L) {
			switchProgressState(ProgressState.SENDING, index, true)
		} else if (progress == progressMax) {
			switchProgressState(ProgressState.PROCESSING, 0, false)
		}
		if (progressMode && callback != null) {
			callback.onSendPostChangeProgressValue(key, progress, progressMax)
		}
	}

	override fun run(): Boolean {
		try {
			chanHolder.use().use {
				val data = this.data
				if (data.captchaNeedLoad) {
					var success = false
					if (data.captchaData != null) {
						try {
							fallbackHolder.use().use {
								val response = ReadCaptchaTask.readForegroundCaptcha(fallbackHolder,
										chan.name, data.captchaData, data.captchaType)
								if (response != null) {
									data.captchaData.put(ChanPerformer.CaptchaData.INPUT, response)
									success = true
								}
							}
						} catch (e: InterruptedException) {
							errorItem = ErrorItem(ErrorItem.Type.UNKNOWN)
							return false
						}
					}
					if (!success) {
						// Don't switch captchaError
						errorItem = ErrorItem(ErrorItem.Type.API, ApiException.SEND_ERROR_CAPTCHA)
						return false
					}
				} else if (data.captchaData != null &&
						(ChanConfiguration.CAPTCHA_TYPE_RECAPTCHA_2 == data.captchaType ||
								ChanConfiguration.CAPTCHA_TYPE_RECAPTCHA_2_INVISIBLE == data.captchaType ||
								ChanConfiguration.CAPTCHA_TYPE_HCAPTCHA == data.captchaType)) {
					data.captchaData.put(ChanPerformer.CaptchaData.INPUT,
							data.captchaData.get(ReadCaptchaTask.RECAPTCHA_SKIP_RESPONSE))
				}
				if (isCancelled()) {
					return false
				}
				data.holder = chanHolder
				data.listener = progressHandler
				var result = chan.performer.safe().onSendPost(data)
				if (data.threadNumber == null && (result == null || result.threadNumber == null)) {
					// New thread created with undefined number
					val readThreadsResult = try {
						chan.performer.safe().onReadThreads(ChanPerformer
								.ReadThreadsData(data.boardName, 0, data.holder, null))
					} catch (e: RedirectException) {
						null
					}
					val threads = readThreadsResult?.threads
					if (threads != null && threads.isNotEmpty()) {
						var postComment = data.comment
						val commentEditor = chan.markup.safe().obtainCommentEditor(data.boardName)
						if (commentEditor != null && postComment != null) {
							postComment = commentEditor.removeTags(postComment)
						}
						val estimator = SimilarTextEstimator(Int.MAX_VALUE, true)
						val wordsData1 = estimator.getWords<Void>(postComment)
						for (thread in threads) {
							val post = thread!!!!!!!!!!!!!!!!!!!!!!.posts[0]
							val comment = HtmlParser.clear(post!!.comment)
							val wordsData2 = estimator.getWords<Void>(comment)
							if (estimator.checkSimiliar(wordsData1, wordsData2)
									|| wordsData1 == null && wordsData2 == null) {
								result = ChanPerformer.SendPostResult(thread!!.threadNumber, null)
								break
							}
						}
					}
				}
				this.result = result
				return true
			}
		} catch (e: ExtensionException) {
			errorItem = e.getErrorItemAndHandle()
			return false
		} catch (e: HttpException) {
			errorItem = e.getErrorItemAndHandle()
			return false
		} catch (e: InvalidResponseException) {
			errorItem = e.getErrorItemAndHandle()
			return false
		} catch (e: ApiException) {
			errorItem = e.errorItem
			extra = e.extra
			val errorType = e.errorType
			captchaError = errorType == ApiException.SEND_ERROR_CAPTCHA
			keepCaptcha = !captchaError && e.checkFlag(ApiException.FLAG_KEEP_CAPTCHA)
			return false
		} finally {
			chan.configuration.commit()
		}
	}

	override fun cancel() {
		super.cancel()
		chanHolder.interrupt()
		fallbackHolder.interrupt()
	}

	override fun onProgress(progress: LongArray) {
		val values = progress
		val index = values[0].toInt()
		val progress = values[1]
		val progressMax = values[2]
		updateProgressValue(index, progress, progressMax)
	}

	override fun onComplete(result: Boolean) {
		val success = result
		if (callback != null) {
			if (success) {
				callback.onSendPostSuccess(key, data, chan.name,
						result?.threadNumber, result?.postNumber)
			} else {
				callback.onSendPostFail(key, data, chan.name, errorItem, extra, captchaError, keepCaptcha)
			}
		}
	}
}
