package com.mishiranu.dashchan.content.service.webview

import android.os.Parcel
import android.os.Parcelable

interface WebViewExtra : Parcelable {
	fun getInjectJavascript(): String? = null

	override fun describeContents(): Int = 0

	override fun writeToParcel(dest: Parcel, flags: Int) {
		dest.writeString(javaClass.name)
	}

	companion object {
		@JvmField
		val CREATOR: Parcelable.Creator<WebViewExtra> = object : Parcelable.Creator<WebViewExtra> {
			override fun createFromParcel(source: Parcel): WebViewExtra {
				val className = source.readString()
				val creator = try {
					@Suppress("UNCHECKED_CAST")
					Class.forName(className!!).getField("CREATOR").get(null)
							as Parcelable.Creator<WebViewExtra>
				} catch (e: Exception) {
					throw RuntimeException(e)
				}
				return creator.createFromParcel(source)
			}

			override fun newArray(size: Int): Array<WebViewExtra?> = arrayOfNulls(size)
		}
	}
}
