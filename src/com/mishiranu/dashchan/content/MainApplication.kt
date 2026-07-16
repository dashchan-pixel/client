package com.mishiranu.dashchan.content

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import chan.content.ChanManager
import chan.http.HttpClient
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.PagesDatabase
import com.mishiranu.dashchan.content.net.UserAgentProvider
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.Logger
import java.io.File

class MainApplication : Application() {
    init {
        instance = this
    }

    private fun checkProcess(suffix: String?): Boolean = CommonUtils.equals(suffix, processSuffix)

    fun isMainProcess(): Boolean = checkProcess(null)

    private var processSuffix: String? = null

    override fun onCreate() {
        super.onCreate()

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = activityManager.runningAppProcesses.orEmpty()
        val pid = Process.myPid()
        for (process in processes) {
            if (process.pid == pid) {
                val index = process.processName.indexOf(':')
                if (index >= 0) {
                    processSuffix = StringUtils.nullIfEmpty(process.processName.substring(index + 1))
                }
                break
            }
        }

        if (isMainProcess()) {
            Logger.init(this)
            UserAgentProvider.initialize(this)
            ChanManager.getInstance()
            HttpClient.getInstance()
            CommonDatabase.getInstance()
            PagesDatabase.getInstance()
            ChanDatabase.getInstance()
            CacheManager.getInstance()
        } else if (checkProcess(PROCESS_WEB_VIEW)) {
            IOUtils.deleteRecursive(getWebViewCacheDir())
        }
    }

    val localizedContext: Context
        get() = LocaleManager.getInstance().applyApplication(this)

    val isLowRam: Boolean
        get() {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            return activityManager != null && activityManager.isLowRamDevice
        }

    fun getSharedPrefsDir(): File = File(getCacheDir().parentFile, "shared_prefs")

    private fun getWebViewCacheDir(): File = File(super.getCacheDir(), "webview")

    override fun getCacheDir(): File {
        if (checkProcess(PROCESS_WEB_VIEW)) {
            val dir = File(getWebViewCacheDir(), "cache")
            dir.mkdirs()
            return dir
        }
        return super.getCacheDir()
    }

    override fun getDir(
        name: String,
        mode: Int,
    ): File =
        if (checkProcess(PROCESS_WEB_VIEW)) {
            val dir = File(getWebViewCacheDir(), name)
            dir.mkdirs()
            dir
        } else {
            super.getDir(name, mode)
        }

    override fun openOrCreateDatabase(
        name: String?,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase =
        if ("http_auth.db" == name) {
            // Create in-memory database for WebView
            SQLiteDatabase.create(factory)
        } else {
            super.openOrCreateDatabase(name, mode, factory)
        }

    companion object {
        private const val PROCESS_WEB_VIEW = "webview"

        private lateinit var instance: MainApplication

        @JvmStatic
        fun getInstance(): MainApplication = instance
    }
}
