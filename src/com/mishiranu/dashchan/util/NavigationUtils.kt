package com.mishiranu.dashchan.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Browser
import chan.content.Chan
import chan.content.ChanManager
import chan.http.FirewallResolver
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.AdvancedPreferences
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.service.AudioPlayerService
import com.mishiranu.dashchan.ui.MainActivity
import com.mishiranu.dashchan.widget.ClickableToast
import java.io.File
import kotlin.system.exitProcess

object NavigationUtils {
	enum class BrowserType { AUTO, INTERNAL, EXTERNAL }

	@JvmStatic
	fun handleUri(context: Context, chanName: String?, uri: Uri, browserType: BrowserType) {
		var targetUri = uri
		if (chanName != null) {
			targetUri = Chan.get(chanName).locator.convert(targetUri)!!
		}
		val isWeb = Chan.getFallback().locator.isWebScheme(targetUri)
		var intent: Intent
		var internalBrowser = isWeb && (browserType == BrowserType.INTERNAL ||
				browserType == BrowserType.AUTO && Preferences.isUseInternalBrowser)
		if (internalBrowser && browserType != BrowserType.INTERNAL) {
			val manager = ChanManager.getInstance()
			val packageManager = context.packageManager
			val names = HashSet<ComponentName>()
			var infos = packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, targetUri),
					PackageManager.MATCH_DEFAULT_ONLY)
			for (info in infos) {
				val activityInfo = info.activityInfo
				val packageName = activityInfo.applicationInfo.packageName
				if (!manager.isExtensionPackage(packageName)) {
					names.add(ComponentName(packageName, activityInfo.name))
				}
			}
			infos = packageManager.queryIntentActivities(
					Intent(Intent.ACTION_VIEW, Uri.parse("http://google.com")),
					PackageManager.MATCH_DEFAULT_ONLY)
			for (info in infos) {
				val activityInfo = info.activityInfo
				names.remove(ComponentName(activityInfo.applicationInfo.packageName, activityInfo.name))
			}
			internalBrowser = names.isEmpty()
		}
		if (internalBrowser) {
			intent = Intent(context, MainActivity::class.java).setAction(C.ACTION_BROWSER).setData(targetUri)
		} else {
			intent = Intent(Intent.ACTION_VIEW, targetUri)
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
			intent.putExtra(C.EXTRA_FROM_CLIENT, true)
			if (chanName != null) {
				val chan = Chan.get(chanName)
				if (chan.locator.safe(false).isAttachmentUri(targetUri)) {
					val userAgent = AdvancedPreferences.getUserAgent(chanName)
					val resolverIdentifier = FirewallResolver.Identifier(userAgent, true, targetUri.host)
					val cookieBuilder = FirewallResolver.Implementation.getInstance()
							.collectCookies(chan, targetUri, resolverIdentifier, true)
					if (!cookieBuilder.isEmpty) {
						// For MX Player, see https://sites.google.com/site/mxvpen/api
						intent.putExtra("headers", arrayOf("User-Agent", userAgent,
								"Cookie", cookieBuilder.build()))
					}
				}
			}
			if (!isWeb) {
				intent = Intent.createChooser(intent, null)
			}
		}
		try {
			context.startActivity(intent)
		} catch (e: ActivityNotFoundException) {
			ClickableToast.show(R.string.unknown_address)
		} catch (e: Exception) {
			ClickableToast.show(e.message)
		}
	}

	@JvmStatic
	fun handleUriInternal(context: Context, chanName: String?, uri: Uri) {
		var targetChanName = chanName
		val uriChan = Chan.getPreferred(null, uri)
		if (uriChan.name != null) {
			targetChanName = uriChan.name
		}
		val locator = Chan.getFallback().locator
		var handled = false
		if (targetChanName != null && locator.safe(false).isAttachmentUri(uri)) {
			val internalUri = locator.convert(uri)
			val fileName = locator.createAttachmentFileName(internalUri!!)
			if (locator.isImageUri(internalUri) ||
					locator.isVideoUri(internalUri) && isOpenableVideoPath(fileName)) {
				openImageVideo(context, internalUri!!)
				handled = true
			} else if (locator.isAudioUri(internalUri)) {
				AudioPlayerService.start(context, targetChanName, internalUri, fileName)
				handled = true
			}
		}
		if (!handled && locator.isWebScheme(uri)) {
			val path = uri.path
			if (locator.isImageExtension(path) ||
					locator.isVideoExtension(path) && isOpenableVideoPath(path)) {
				openImageVideo(context, uri)
				handled = true
			}
		}
		if (!handled) {
			handleUri(context, targetChanName, uri, BrowserType.AUTO)
		}
	}

	@JvmStatic
	fun openImageVideo(context: Context, uri: Uri) {
		context.startActivity(Intent(context, MainActivity::class.java)
				.setAction(C.ACTION_GALLERY).setData(uri))
	}

	@JvmStatic
	fun isOpenableVideoPath(path: String?): Boolean {
		return isOpenableVideoExtension(StringUtils.getFileExtension(path))
	}

	@JvmStatic
	fun isOpenableVideoExtension(extension: String?): Boolean {
		return Preferences.isUseVideoPlayer && C.OPENABLE_VIDEO_EXTENSIONS.contains(extension)
	}

	@JvmStatic
	fun shareText(context: Context, subject: String?, text: String?, uri: Uri?) {
		var intent = Intent(Intent.ACTION_SEND)
		intent.type = "text/plain"
		intent.putExtra(Intent.EXTRA_SUBJECT, subject)
		intent.putExtra(Intent.EXTRA_TEXT, text)
		intent = Intent.createChooser(intent, null)
		if (uri != null) {
			val activities = context.packageManager.queryIntentActivities(
					Intent(Intent.ACTION_VIEW).setData(uri), PackageManager.MATCH_DEFAULT_ONLY)
			if (activities.isNotEmpty()) {
				val filterPackageNames = HashSet<String>()
				filterPackageNames.add(context.packageName)
				for (extensionItem in ChanManager.getInstance().extensionItems) {
					filterPackageNames.add(extensionItem.packageName)
				}
				val browserIntents = ArrayList<Intent>()
				for (resolveInfo in activities) {
					if (!filterPackageNames.contains(resolveInfo.activityInfo.packageName)) {
						browserIntents.add(Intent(Intent.ACTION_VIEW).setData(uri)
								.setComponent(ComponentName(resolveInfo.activityInfo.packageName,
										resolveInfo.activityInfo.name)))
					}
				}
				if (browserIntents.isNotEmpty()) {
					intent.putExtra(Intent.EXTRA_INITIAL_INTENTS,
							CommonUtils.toArray(browserIntents, Intent::class.java))
				}
			}
		}
		context.startActivity(intent)
	}

	@JvmStatic
	fun shareLink(context: Context, subject: String?, uri: Uri) {
		shareText(context, subject, uri.toString(), uri)
	}

	@JvmStatic
	fun shareFile(context: Context, file: File, fileName: String?) {
		val data = CacheManager.getInstance().prepareFileForShare(file, fileName)
		if (data == null) {
			ClickableToast.show(R.string.cache_is_unavailable)
			return
		}
		context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
				.setType(data.second).setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				.putExtra(Intent.EXTRA_STREAM, data.first), null))
	}

	@JvmStatic
	fun restartApplication(context: Context) {
		val intent = Intent(context, MainActivity::class.java)
				.setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		val pendingIntent = PendingIntent.getActivity(context, 0, intent,
				PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
		val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		val `when` = SystemClock.elapsedRealtime() + 1000
		alarmManager.setExact(AlarmManager.ELAPSED_REALTIME, `when`, pendingIntent)
		exitProcess(0)
	}
}
