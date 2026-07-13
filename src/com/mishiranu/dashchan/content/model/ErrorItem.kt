package com.mishiranu.dashchan.content.model

import android.os.Parcel
import android.os.Parcelable
import chan.content.ApiException
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication

class ErrorItem private constructor(@JvmField val type: Type?, @JvmField val specialType: Int,
		@JvmField val httpResponseCode: Int, @JvmField val message: String?,
		@JvmField val resId: Int) : Parcelable {
	enum class Type {
		UNKNOWN,
		API,
		SSL,
		DOWNLOAD,
		READ_TIMEOUT,
		CONNECT_TIMEOUT,
		CONNECTION_RESET,
		INVALID_CERTIFICATE,
		UNSAFE_REDIRECT,
		UNSUPPORTED_SCHEME,
		CAPTCHA_EXPIRED,
		EMPTY_RESPONSE,
		INVALID_RESPONSE,
		INVALID_DATA_FORMAT,
		BOARD_NOT_EXISTS,
		THREAD_NOT_EXISTS,
		POST_NOT_FOUND,
		NO_ACCESS_TO_MEMORY,
		INSUFFICIENT_SPACE,
		EXTENSION,
		FIREWALL_BLOCK,
		UNSUPPORTED_SERVICE,
		INVALID_AUTHORIZATION_DATA,
		UNSUPPORTED_RECAPTCHA
	}

	fun interface Holder {
		fun getErrorItemAndHandle(): ErrorItem
	}

	constructor(type: Type?, specialType: Int) : this(type, specialType, 0, null, 0)

	constructor(type: Type?) : this(type, 0)

	constructor(httpResponseCode: Int, message: String?) :
			this(null, 0, httpResponseCode, StringUtils.removeSingleDot(message), 0)

	constructor(message: String?) : this(0, message)

	constructor(resId: Int) : this(null, 0, 0, null, resId)

	override fun toString(): String {
		if (!message.isNullOrEmpty()) {
			return if (httpResponseCode != 0) "HTTP $httpResponseCode: $message" else message
		}
		if (httpResponseCode != 0) {
			// HTTP/2 responses carry no reason phrase; still better than "unknown error"
			return "HTTP $httpResponseCode"
		}
		if (resId != 0) {
			return MainApplication.getInstance().localizedContext.getString(resId)
		}
		var resId = when (type ?: Type.UNKNOWN) {
			Type.API -> ApiException.getResId(specialType)
			Type.SSL -> R.string.ssl_https_error
			Type.DOWNLOAD -> R.string.unable_to_download_data
			Type.READ_TIMEOUT -> R.string.read_timeout_expired
			Type.CONNECT_TIMEOUT -> R.string.connect_timeout_expired
			Type.CONNECTION_RESET -> R.string.connection_was_reset
			Type.INVALID_CERTIFICATE -> R.string.invalid_certificate
			Type.UNSAFE_REDIRECT -> R.string.unsafe_redirect
			Type.UNSUPPORTED_SCHEME -> R.string.scheme_is_not_supported
			Type.CAPTCHA_EXPIRED -> R.string.captcha_expired
			Type.EMPTY_RESPONSE -> R.string.empty_response
			Type.INVALID_RESPONSE -> R.string.invalid_server_response
			Type.INVALID_DATA_FORMAT -> R.string.invalid_data_format
			Type.BOARD_NOT_EXISTS -> R.string.board_doesnt_exist
			Type.THREAD_NOT_EXISTS -> R.string.thread_doesnt_exist
			Type.POST_NOT_FOUND -> R.string.post_is_not_found
			Type.NO_ACCESS_TO_MEMORY -> R.string.no_access_to_memory
			Type.INSUFFICIENT_SPACE -> R.string.insufficient_device_space
			Type.EXTENSION -> R.string.extension_error
			Type.FIREWALL_BLOCK -> R.string.ddos_protection_bypass_failed
			Type.UNSUPPORTED_SERVICE -> R.string.unsupported_service
			Type.INVALID_AUTHORIZATION_DATA -> R.string.invalid_authorization_data
			Type.UNSUPPORTED_RECAPTCHA -> R.string.this_recaptcha_is_not_supported
			Type.UNKNOWN -> 0
		}
		if (resId == 0) {
			resId = R.string.unknown_error
		}
		return MainApplication.getInstance().localizedContext.getString(resId)
	}

	override fun describeContents(): Int = 0

	override fun writeToParcel(dest: Parcel, flags: Int) {
		dest.writeString(type?.name)
		dest.writeInt(specialType)
		dest.writeInt(httpResponseCode)
		dest.writeString(message)
		dest.writeInt(resId)
	}

	companion object {
		@JvmField
		val CREATOR = object : Parcelable.Creator<ErrorItem> {
			override fun createFromParcel(source: Parcel): ErrorItem {
				val typeString = source.readString()
				val type = typeString?.let { Type.valueOf(it) }
				val specialType = source.readInt()
				val httpResponseCode = source.readInt()
				val message = source.readString()
				val resId = source.readInt()
				return ErrorItem(type, specialType, httpResponseCode, message, resId)
			}

			override fun newArray(size: Int): Array<ErrorItem?> = arrayOfNulls(size)
		}
	}
}
