package com.mishiranu.dashchan.content.net

import android.net.Uri
import android.os.SystemClock
import android.util.Pair
import chan.content.Chan
import chan.content.InvalidResponseException
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.http.SimpleEntity
import chan.util.StringUtils
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import org.json.JSONException
import org.json.JSONObject
import java.util.LinkedHashMap

class CaptchaSolving private constructor() {
    class UnsupportedServiceException : Exception()

    class InvalidTokenException : Exception()

    private class TimeoutException(
        message: String,
    ) : Exception(message)

    private class Configuration(
        val endpoint: String,
        val token: String,
        val timeout: Int,
    )

    private fun createUri(endpoint: String): Uri {
        var uri = Uri.parse(endpoint)
        if (StringUtils.isEmpty(uri.scheme)) {
            uri = uri.buildUpon().scheme(if (Chan.getFallback().locator.isUseHttps()) "https" else "http").build()
        }
        return uri
    }

    private fun getConfiguration(): Configuration? {
        val map = Preferences.captchaSolving ?: return null
        val endpoint = map[Preferences.SUB_KEY_CAPTCHA_SOLVING_ENDPOINT]
        val token = map[Preferences.SUB_KEY_CAPTCHA_SOLVING_TOKEN]
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(token)) {
            return null
        }
        val timeoutString = map[Preferences.SUB_KEY_CAPTCHA_SOLVING_TIMEOUT]
        var timeout = -1
        if (timeoutString != null) {
            try {
                timeout = timeoutString.toInt()
            } catch (e: NumberFormatException) {
                // Ignore
            }
        }
        return Configuration(endpoint!!, token!!, timeout)
    }

    fun hasConfiguration(): Boolean = getConfiguration() != null

    @Throws(HttpException::class, UnsupportedServiceException::class, InvalidTokenException::class)
    fun checkService(holder: HttpHolder): Map<String, String> {
        val configuration = getConfiguration() ?: throw UnsupportedServiceException()
        val extra = LinkedHashMap<String, String>()
        try {
            checkServiceInternal(holder, configuration.endpoint, configuration.token, extra)
        } catch (e: UnsupportedServiceException) {
            // Check for HTTP exception
            HttpRequest(createUri(configuration.endpoint), holder)
                .setHeadMethod()
                .setSuccessOnly(false)
                .perform()!!
                .cleanupAndDisconnect()
            throw e
        }
        return extra
    }

    @Throws(HttpException::class)
    fun checkActive(holder: HttpHolder): Boolean {
        val configuration = getConfiguration() ?: return false
        return try {
            checkServiceInternal(holder, configuration.endpoint, configuration.token, null)
            true
        } catch (e: HttpException) {
            if (!e.isHttpException() && !e.isSocketException()) {
                throw e
            } else {
                false
            }
        } catch (e: UnsupportedServiceException) {
            false
        } catch (e: InvalidTokenException) {
            false
        }
    }

    @Throws(HttpException::class, UnsupportedServiceException::class, InvalidTokenException::class)
    private fun checkServiceInternal(
        holder: HttpHolder,
        endpoint: String,
        token: String,
        outExtra: MutableMap<String, String>?,
    ): Service {
        val service = checkService(holder, endpoint) ?: throw UnsupportedServiceException()
        outExtra?.put("protocol", service.name())
        if (!checkServiceAuthorization(holder, service, endpoint, token, outExtra)) {
            throw InvalidTokenException()
        }
        return service
    }

    private var lastService: Pair<String, Service>? = null

    @Throws(HttpException::class)
    private fun checkService(
        holder: HttpHolder,
        endpoint: String,
    ): Service? {
        synchronized(this) {
            val lastService = this.lastService
            if (lastService != null && endpoint == lastService.first) {
                return lastService.second
            }
        }
        val endpointUri = createUri(endpoint)
        for (service in SERVICES) {
            var success = false
            try {
                success = service.checkService(holder, endpointUri)
            } catch (e: HttpException) {
                if (!e.isHttpException() && !e.isSocketException()) {
                    throw e
                }
            }
            if (success) {
                synchronized(this) {
                    lastService = Pair(endpoint, service)
                }
                return service
            }
        }
        return null
    }

    private var lastAuthorization: Pair<String, String>? = null

    @Throws(HttpException::class)
    private fun checkServiceAuthorization(
        holder: HttpHolder,
        service: Service,
        endpoint: String,
        token: String,
        outExtra: MutableMap<String, String>?,
    ): Boolean {
        val endpointUri = createUri(endpoint)
        val authorization = Pair(endpoint, token)
        if (outExtra == null) {
            synchronized(this) {
                if (authorization == lastAuthorization) {
                    return true
                }
            }
        }
        if (service.checkAuth(holder, endpointUri, token, outExtra)) {
            synchronized(this) {
                lastAuthorization = authorization
            }
            return true
        }
        return false
    }

    enum class CaptchaType { RECAPTCHA_2, RECAPTCHA_2_INVISIBLE, HCAPTCHA, RECAPTCHA_3 }

    /** What a service needs to be told about the captcha, as opposed to about itself. */
    private class Challenge(
        val captchaType: CaptchaType,
        val apiKey: String?,
        val referer: String?,
        /** Only [CaptchaType.RECAPTCHA_3] binds its token to an action. */
        val action: String?,
    )

    @Throws(HttpException::class)
    @JvmOverloads
    fun solveCaptcha(
        holder: HttpHolder,
        captchaType: CaptchaType,
        apiKey: String?,
        referer: String?,
        /** Only [CaptchaType.RECAPTCHA_3] binds its token to an action. */
        action: String? = null,
    ): String? {
        try {
            val configuration = getConfiguration() ?: return null
            val service: Service
            try {
                service = checkServiceInternal(holder, configuration.endpoint, configuration.token, null)
            } catch (e: UnsupportedServiceException) {
                return null
            } catch (e: InvalidTokenException) {
                return null
            }
            val endpointUri = createUri(configuration.endpoint)
            val challenge = Challenge(captchaType, apiKey, referer, action)
            while (true) {
                try {
                    return service.solveCaptcha(
                        holder,
                        endpointUri,
                        configuration.token,
                        configuration.timeout,
                        challenge,
                    )
                } catch (e: TimeoutException) {
                    e.printStackTrace()
                }
            }
        } catch (e: HttpException) {
            if (e.isHttpException() || e.isSocketException()) {
                e.printStackTrace()
                return null
            } else {
                throw e
            }
        }
    }

    private interface Service {
        fun name(): String

        @Throws(HttpException::class)
        fun checkService(
            holder: HttpHolder,
            endpointUri: Uri,
        ): Boolean

        @Throws(HttpException::class)
        fun checkAuth(
            holder: HttpHolder,
            endpointUri: Uri,
            token: String,
            outExtra: MutableMap<String, String>?,
        ): Boolean

        @Throws(HttpException::class, TimeoutException::class)
        fun solveCaptcha(
            holder: HttpHolder,
            endpointUri: Uri,
            token: String,
            timeout: Int,
            challenge: Challenge,
        ): String?
    }

    private class AntigateLegacyService : Service {
        override fun name(): String = "Antigate Legacy"

        @Throws(HttpException::class)
        override fun checkService(
            holder: HttpHolder,
            endpointUri: Uri,
        ): Boolean {
            val uri =
                endpointUri
                    .buildUpon()
                    .appendPath("res.php")
                    .appendQueryParameter("key", "")
                    .appendQueryParameter("action", "getbalance")
                    .build()
            val response = HttpRequest(uri, holder).setSuccessOnly(false).perform()!!.readString()
            return response != null &&
                (
                    response.startsWith("OK|") ||
                        (response.startsWith("ERROR_") && response.contains("KEY"))
                )
        }

        @Throws(HttpException::class)
        override fun checkAuth(
            holder: HttpHolder,
            endpointUri: Uri,
            token: String,
            outExtra: MutableMap<String, String>?,
        ): Boolean {
            val uri =
                endpointUri
                    .buildUpon()
                    .appendPath("res.php")
                    .appendQueryParameter("key", token)
                    .appendQueryParameter("action", "getbalance")
                    .build()
            val response = HttpRequest(uri, holder).setSuccessOnly(true).perform()!!.readString()
            if (response != null && response.startsWith("OK|")) {
                outExtra?.put("balance", response.substring(3))
                return true
            } else if (response != null && response.startsWith("ERROR_") && response.contains("KEY")) {
                return false
            } else {
                return try {
                    StringUtils.emptyIfNull(response).toFloat()
                    if (response != null) {
                        outExtra?.put("balance", response)
                    }
                    true
                } catch (e: NumberFormatException) {
                    throw createInvalidResponse(response)
                }
            }
        }

        @Throws(HttpException::class, TimeoutException::class)
        override fun solveCaptcha(
            holder: HttpHolder,
            endpointUri: Uri,
            token: String,
            timeout: Int,
            challenge: Challenge,
        ): String? {
            val builder = endpointUri.buildUpon().appendPath("in.php")
            builder.appendQueryParameter("key", token)
            val apiKey = challenge.apiKey
            when (challenge.captchaType) {
                CaptchaType.RECAPTCHA_2 -> {
                    builder.appendQueryParameter("method", "userrecaptcha")
                    builder.appendQueryParameter("googlekey", apiKey)
                    builder.appendQueryParameter("invisible", "0")
                }

                CaptchaType.RECAPTCHA_2_INVISIBLE -> {
                    builder.appendQueryParameter("method", "userrecaptcha")
                    builder.appendQueryParameter("googlekey", apiKey)
                    builder.appendQueryParameter("invisible", "1")
                }

                CaptchaType.HCAPTCHA -> {
                    builder.appendQueryParameter("method", "hcaptcha")
                    builder.appendQueryParameter("sitekey", apiKey)
                }

                CaptchaType.RECAPTCHA_3 -> {
                    builder.appendQueryParameter("method", "userrecaptcha")
                    builder.appendQueryParameter("version", "v3")
                    builder.appendQueryParameter("googlekey", apiKey)
                    if (!StringUtils.isEmpty(challenge.action)) {
                        builder.appendQueryParameter("action", challenge.action)
                    }
                }
            }
            builder.appendQueryParameter("pageurl", challenge.referer)
            var response = HttpRequest(builder.build(), holder).perform()!!.readString()
            if (response != null && response.startsWith("OK|")) {
                response = response.substring(3)
            } else {
                throw createInvalidResponse(response)
            }
            val uri =
                endpointUri
                    .buildUpon()
                    .appendPath("res.php")
                    .appendQueryParameter("key", token)
                    .appendQueryParameter("action", "get")
                    .appendQueryParameter("id", response)
                    .build()
            val start = SystemClock.elapsedRealtime()
            var wait = 0
            while (true) {
                if (wait < 5) {
                    wait++
                }
                waitOrThrow(start, timeout, wait * 1000)
                response = HttpRequest(uri, holder).perform()!!.readString()
                if (response != null && response.startsWith("OK|")) {
                    return response.substring(3)
                } else if ("CAPCHA_NOT_READY" != response) {
                    throw createInvalidResponse(response)
                }
            }
        }
    }

    private class AntigateModernService : Service {
        override fun name(): String = "Antigate Modern"

        @Throws(HttpException::class, InvalidResponseException::class)
        private fun run(
            holder: HttpHolder,
            endpointUri: Uri,
            method: String,
            token: String,
            task: JSONObject?,
            taskId: Long?,
        ): JSONObject {
            val request = JSONObject()
            try {
                request.put("clientKey", token)
                if (task != null) {
                    request.put("task", task)
                }
                if (taskId != null) {
                    request.put("taskId", taskId)
                }
            } catch (e: JSONException) {
                throw RuntimeException(e)
            }
            val entity = SimpleEntity()
            entity.setContentType("application/json")
            entity.setData(request.toString())
            val responseText =
                HttpRequest(endpointUri.buildUpon().appendPath(method).build(), holder)
                    .setPostMethod(entity)
                    .setSuccessOnly(false)
                    .perform()!!
                    .readString()
            return try {
                // An empty body is not valid JSON: surface it as InvalidResponseException
                // (which callers handle) rather than the NPE the Java would have thrown.
                JSONObject(responseText.orEmpty())
            } catch (e: JSONException) {
                throw InvalidResponseException()
            }
        }

        @Throws(HttpException::class)
        override fun checkService(
            holder: HttpHolder,
            endpointUri: Uri,
        ): Boolean {
            val response: JSONObject =
                try {
                    run(holder, endpointUri, "getBalance", "", null, null)
                } catch (e: InvalidResponseException) {
                    return false
                }
            if (!response.has("errorId")) {
                return false
            }
            return if (response.has("balance")) {
                true
            } else {
                val errorCode = response.optString("errorCode")
                errorCode.startsWith("ERROR_") && errorCode.contains("KEY")
            }
        }

        @Throws(HttpException::class)
        override fun checkAuth(
            holder: HttpHolder,
            endpointUri: Uri,
            token: String,
            outExtra: MutableMap<String, String>?,
        ): Boolean {
            val response: JSONObject =
                try {
                    run(holder, endpointUri, "getBalance", token, null, null)
                } catch (e: InvalidResponseException) {
                    throw createInvalidResponse(e)
                }
            val balance = response.optString("balance")
            if (!StringUtils.isEmpty(balance)) {
                outExtra?.put("balance", balance)
                return true
            } else {
                val errorCode = response.optString("errorCode")
                if (errorCode.startsWith("ERROR_") && errorCode.contains("KEY")) {
                    return false
                } else {
                    throw createInvalidResponse(response.toString())
                }
            }
        }

        /** Names the challenge the way this protocol's task objects do. */
        private fun buildTask(challenge: Challenge): JSONObject {
            val task = JSONObject()
            try {
                when (challenge.captchaType) {
                    CaptchaType.RECAPTCHA_2 -> {
                        task.put("type", "NoCaptchaTaskProxyless")
                        task.put("isInvisible", false)
                    }

                    CaptchaType.RECAPTCHA_2_INVISIBLE -> {
                        task.put("type", "NoCaptchaTaskProxyless")
                        task.put("isInvisible", true)
                    }

                    CaptchaType.HCAPTCHA -> {
                        task.put("type", "HCaptchaTaskProxyless")
                    }

                    CaptchaType.RECAPTCHA_3 -> {
                        task.put("type", "RecaptchaV3TaskProxyless")
                        if (!StringUtils.isEmpty(challenge.action)) {
                            task.put("pageAction", challenge.action)
                        }
                    }
                }
                task.put("websiteURL", challenge.referer)
                task.put("websiteKey", challenge.apiKey)
            } catch (e: JSONException) {
                throw RuntimeException(e)
            }
            return task
        }

        @Throws(HttpException::class, TimeoutException::class)
        override fun solveCaptcha(
            holder: HttpHolder,
            endpointUri: Uri,
            token: String,
            timeout: Int,
            challenge: Challenge,
        ): String? {
            val task = buildTask(challenge)
            val response0: JSONObject =
                try {
                    run(holder, endpointUri, "createTask", token, task, null)
                } catch (e: InvalidResponseException) {
                    throw createInvalidResponse(e)
                }
            val taskId: Long =
                try {
                    response0.getLong("taskId")
                } catch (e: JSONException) {
                    throw createInvalidResponse(response0.toString())
                }
            val start = SystemClock.elapsedRealtime()
            var wait = 0
            while (true) {
                if (wait < 5) {
                    wait++
                }
                waitOrThrow(start, timeout, wait * 1000)
                val response: JSONObject =
                    try {
                        run(holder, endpointUri, "getTaskResult", token, null, taskId)
                    } catch (e: InvalidResponseException) {
                        throw createInvalidResponse(e)
                    }
                val status = response.optString("status")
                if ("ready" == status) {
                    try {
                        return response.getJSONObject("solution").getString("gRecaptchaResponse")
                    } catch (e: JSONException) {
                        throw createInvalidResponse(response.toString())
                    }
                } else if ("processing" != status) {
                    throw createInvalidResponse(response.toString())
                }
            }
        }
    }

    companion object {
        private val INSTANCE = CaptchaSolving()

        @JvmStatic
        fun getInstance(): CaptchaSolving = INSTANCE

        private val SERVICES: List<Service> = listOf(AntigateLegacyService(), AntigateModernService())

        @Throws(HttpException::class, TimeoutException::class)
        private fun waitOrThrow(
            start: Long,
            timeout: Int,
            ms: Int,
        ) {
            var waitMs = ms
            if (timeout > 0) {
                val now = SystemClock.elapsedRealtime()
                val cancel = start + timeout * 1000L
                if (now >= cancel) {
                    if (Thread.currentThread().isInterrupted) {
                        throw HttpException(ErrorItem.Type.UNKNOWN, false, false)
                    } else {
                        throw TimeoutException("Timeout after " + (now - start) + " ms")
                    }
                }
                waitMs = Math.max(Math.min(waitMs.toLong(), cancel - now), (waitMs / 2).toLong()).toInt()
            }
            try {
                Thread.sleep(waitMs.toLong())
            } catch (e: InterruptedException) {
                throw HttpException(ErrorItem.Type.UNKNOWN, false, false, e)
            }
        }

        private fun createInvalidResponse(cause: Exception): HttpException = HttpException(ErrorItem.Type.INVALID_RESPONSE, true, false, cause)

        private fun createInvalidResponse(message: String?): HttpException = createInvalidResponse(Exception(message))
    }
}
