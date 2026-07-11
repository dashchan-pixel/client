package com.mishiranu.dashchan.content

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import chan.content.ChanManager
import chan.content.ChanManager.Fingerprints
import chan.util.DataFile
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.content.service.DownloadService.DownloadItem
import com.mishiranu.dashchan.ui.StateActivity
import com.mishiranu.dashchan.util.IOUtils.copyStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class UpdaterActivity : StateActivity() {
    private var index = 0

    private val files: MutableList<String?>?
        get() = getIntent().getStringArrayListExtra(EXTRA_FILES)

    private val installStatusReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirmIntent =
                    intent.getParcelableExtra<Intent?>(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmIntent != null) {
                    startActivity(confirmIntent)
                } else {
                    finish()
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                index++
                performInstallation()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerReceiver(
            installStatusReceiver, IntentFilter(ACTION_INSTALL_STATUS),
            RECEIVER_NOT_EXPORTED
        )
        if (savedInstanceState == null) {
            performInstallation()
        } else {
            index = savedInstanceState.getInt(EXTRA_INDEX)
        }
    }

    protected override fun onDestroy() {
        unregisterReceiver(installStatusReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    private fun performInstallation() {
        val files = this.files
        if (files != null && files.size > index) {
            val file: File? = FileProvider.Companion.getUpdatesFile(files.get(index))
            if (file == null) {
                index++
                performInstallation()
            } else {
                Thread(Runnable { commitInstallSession(file) }).start()
            }
        } else {
            finish()
        }
    }

    private fun commitInstallSession(file: File) {
        val installer = getPackageManager().getPackageInstaller()
        var sessionId = -1
        try {
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(file.getName(), 0, file.length()).use { output ->
                    FileInputStream(file).use { input ->
                        copyStream(input, output)
                        session.fsync(output)
                    }
                }
                val statusIntent: Intent =
                    Intent(ACTION_INSTALL_STATUS).setPackage(getPackageName())
                val pendingIntent = PendingIntent.getBroadcast(
                    this, sessionId, statusIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                session.commit(pendingIntent.getIntentSender())
            }
        } catch (e: IOException) {
            e.printStackTrace()
            if (sessionId >= 0) {
                try {
                    installer.abandonSession(sessionId)
                } catch (abandonException: RuntimeException) {
                    // Ignore
                }
            }
            runOnUiThread(Runnable { this.finish() })
        } catch (e: RuntimeException) {
            e.printStackTrace()
            if (sessionId >= 0) {
                try {
                    installer.abandonSession(sessionId)
                } catch (abandonException: RuntimeException) {
                }
            }
            runOnUiThread(Runnable { this.finish() })
        }
    }

    private class Connection(
        private val context: Context,
        private val downloadItems: MutableList<DownloadItem>
    ) : ServiceConnection, DownloadService.Callback {
        private val status = HashMap<String?, Boolean?>()

        private var binder: DownloadService.Binder? = null

        init {
            context.bindService(
                Intent(context, DownloadService::class.java),
                this,
                BIND_AUTO_CREATE
            )
        }

        override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
            this.binder = binder as DownloadService.Binder?
            this.binder!!.register(this)
            this.binder!!.downloadDirect(DataFile.Target.UPDATES, null, true, downloadItems)
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            if (this === activeConnection) {
                activeConnection = null
                if (binder != null) {
                    binder!!.unregister(this)
                    binder = null
                }
            }
        }

        fun finish(): Boolean {
            if (this === activeConnection) {
                activeConnection = null
                if (binder != null) {
                    binder!!.unregister(this)
                    binder = null
                    context.unbindService(this)
                    return true
                }
            }
            return false
        }

        override fun onFinishDownloading(
            success: Boolean,
            target: DataFile.Target?,
            path: String?,
            name: String?
        ) {
            if (target == DataFile.Target.UPDATES && isEmpty(path)) {
                status.put(name, success)
            }
            if (success) {
                var successAll = true
                for (downloadItem in downloadItems) {
                    val status = this.status.get(downloadItem.name)
                    if (status == null || !status) {
                        successAll = false
                        break
                    }
                }
                if (successAll) {
                    if (finish()) {
                        val files = ArrayList<String?>(downloadItems.size)
                        val directory: File? = FileProvider.Companion.updatesDirectory
                        for (downloadItem in downloadItems) {
                            if (!File(directory, downloadItem.name).exists()) {
                                break
                            }
                            files.add(downloadItem.name)
                        }
                        if (files.size == downloadItems.size) {
                            context.startActivity(
                                Intent(context, UpdaterActivity::class.java)
                                    .putStringArrayListExtra(EXTRA_FILES, files)
                                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }
        }

        override fun onCleanup() {
            finish()
        }

        fun cancel() {
            if (binder != null) {
                binder!!.unregister(this)
                binder = null
                context.unbindService(this)
            }
        }
    }

    class Request(
        val extensionName: String?, val versionName: String?, val uri: Uri?,
        val sha256sum: ByteArray?, val checkFingerprints: Fingerprints?
    )

    companion object {
        private const val EXTRA_FILES = "files"

        private const val EXTRA_INDEX = "index"

        private const val ACTION_INSTALL_STATUS = "com.mishiranu.dashchan.action.INSTALL_STATUS"

        private var activeConnection: Connection? = null

        fun startUpdater(requests: MutableList<Request>) {
            var clientDownloadItem: DownloadItem? = null
            val downloadItems = ArrayList<DownloadItem>()
            for (request in requests) {
                val name = request.extensionName + "-" + request.versionName + ".apk"
                val downloadItem = DownloadItem(
                    null,
                    request.uri, name, request.sha256sum, request.checkFingerprints
                )
                if (ChanManager.EXTENSION_NAME_CLIENT == request.extensionName) {
                    clientDownloadItem = downloadItem
                } else {
                    downloadItems.add(downloadItem)
                }
            }
            if (clientDownloadItem != null) {
                downloadItems.add(clientDownloadItem)
            }
            if (activeConnection != null) {
                activeConnection!!.cancel()
            }
            activeConnection = Connection(MainApplication.getInstance(), downloadItems)
        }
    }
}
