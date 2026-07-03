package chan.http

import chan.annotation.Public
import com.mishiranu.dashchan.content.model.ErrorItem
import java.net.HttpURLConnection

@Public
class HttpException : Exception, ErrorItem.Holder {
	private val responseCode: Int
	private val responseText: String?
	private val errorItemType: ErrorItem.Type?

	private val httpException: Boolean
	private val socketException: Boolean

	constructor(errorItemType: ErrorItem.Type?, httpException: Boolean, socketException: Boolean) {
		this.responseCode = 0
		this.errorItemType = errorItemType
		this.responseText = null
		this.httpException = httpException
		this.socketException = socketException
	}

	constructor(errorItemType: ErrorItem.Type?, httpException: Boolean, socketException: Boolean,
			throwable: Throwable?) : super(throwable) {
		this.responseCode = 0
		this.errorItemType = errorItemType
		this.responseText = null
		this.httpException = httpException
		this.socketException = socketException
	}

	@Public
	constructor(responseCode: Int, responseText: String?) {
		this.responseCode = responseCode
		this.errorItemType = null
		this.responseText = responseText
		this.httpException = true
		this.socketException = false
	}

	@Public
	fun getResponseCode(): Int = responseCode

	@Public
	fun isHttpException(): Boolean = httpException

	@Public
	fun isSocketException(): Boolean = socketException

	override fun getErrorItemAndHandle(): ErrorItem {
		if (!responseText.isNullOrEmpty()) {
			return ErrorItem(responseCode, responseText)
		}
		return ErrorItem(errorItemType)
	}

	companion object {
		@Public
		@JvmStatic
		fun createNotFoundException(): HttpException {
			return HttpException(HttpURLConnection.HTTP_NOT_FOUND, "Not Found")
		}
	}
}
