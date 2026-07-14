package com.mishiranu.dashchan.content.net

import android.app.Application
import android.webkit.WebSettings
import androidx.annotation.MainThread

class UserAgentProvider private constructor() {
    private var userAgent: String? = null

    fun getUserAgent(): String? = userAgent

    companion object {
        private val INSTANCE = UserAgentProvider()

        @JvmStatic
        @MainThread
        fun initialize(appContext: Application) {
            INSTANCE.userAgent = WebSettings.getDefaultUserAgent(appContext)
        }

        @JvmStatic
        fun getInstance(): UserAgentProvider {
            checkNotNull(INSTANCE.userAgent) { "UserAgentProvider is not initialized" }
            return INSTANCE
        }
    }
}
