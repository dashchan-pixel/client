package com.mishiranu.dashchan.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import chan.content.Chan.Companion.getFallback
import chan.content.Chan.Companion.getPreferred
import chan.content.ChanLocator.NavigationData
import chan.util.StringUtils.copyToClipboard
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.isEmptyOrWhitespace
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences.isVerifyCertificate
import com.mishiranu.dashchan.util.AnimationUtils.lerp
import com.mishiranu.dashchan.util.NavigationUtils.openImageVideo
import com.mishiranu.dashchan.util.NavigationUtils.shareLink
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.util.WebViewUtils.clearAll
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import com.mishiranu.dashchan.widget.ExpandedLayout
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.getTheme
import java.util.UUID
import kotlin.math.min

class BrowserFragment : ContentFragment, DownloadListener {
    constructor()

    constructor(uri: Uri?) {
        val args = Bundle()
        args.putParcelable(EXTRA_URI, uri)
        setArguments(args)
    }

    private var webView: WebView? = null
    private var progressView: ProgressView? = null

    private var navigationDrawerLocker: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = ExpandedLayout(container.getContext(), true)
        webView = WebView(layout.getContext().getApplicationContext())
        layout.addView(
            webView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        progressView = ProgressView(layout.getContext())
        val density = obtainDensity(this)
        layout.addView(
            progressView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            (3f * density + 0.5f).toInt()
        )
        return layout
    }

    @SuppressLint("SetJavaScriptEnabled")
    public override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        navigationDrawerLocker = "browser-" + UUID.randomUUID()
        (requireActivity() as FragmentHandler).setNavigationAreaLocked(
            navigationDrawerLocker!!,
            true
        )

        val settings = webView!!.getSettings()
        settings.setBuiltInZoomControls(true)
        settings.setDisplayZoomControls(false)
        settings.setUseWideViewPort(true)
        settings.setLoadWithOverviewMode(true)
        settings.setJavaScriptEnabled(true)
        settings.setDomStorageEnabled(true)
        webView!!.setWebViewClient(CustomWebViewClient())
        webView!!.setWebChromeClient(CustomWebChromeClient())
        webView!!.setDownloadListener(this)
        webView!!.setOnLongClickListener(OnLongClickListener { v: View? ->
            val hitTestResult = webView!!.getHitTestResult()
            when (hitTestResult.getType()) {
                HitTestResult.IMAGE_TYPE, HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val chan = getFallback()
                    val uri = Uri.parse(hitTestResult.getExtra())
                    if (chan.locator.isWebScheme(uri) && chan.locator.isImageExtension(uri.getPath())) {
                        openImageVideo(requireContext(), uri)
                    }
                    return@OnLongClickListener true
                }
            }
            false
        })

        if (savedInstanceState != null) {
            webView!!.restoreState(savedInstanceState)
        }

        (requireActivity() as FragmentHandler).setTitleSubtitle(
            getString(R.string.web_browser),
            null
        )
        if (savedInstanceState == null) {
            clearAll(webView)
            webView!!.loadUrl(
                BundleCompat.getParcelable<Uri?>(
                    requireArguments(),
                    EXTRA_URI,
                    Uri::class.java
                ).toString()
            )
        }
    }

    public override fun onDestroyView() {
        super.onDestroyView()

        (requireActivity() as FragmentHandler).setNavigationAreaLocked(
            navigationDrawerLocker!!,
            false
        )
        webView!!.stopLoading()
        webView!!.destroy()
        // Remove references to fragment and parent view since WebView bugs may cause memory leaks
        webView!!.setOnLongClickListener(null)
        ViewUtils.removeFromParent(webView!!)
        webView = null
        progressView = null
    }

    override fun onPause() {
        super.onPause()
        webView!!.onPause()
    }

    public override fun onResume() {
        super.onResume()
        webView!!.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (webView != null) {
            webView!!.saveState(outState)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, primary: Boolean) {
        menu.add(0, R.id.menu_reload, 0, R.string.reload)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionRefresh))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, R.id.menu_copy_link, 0, R.string.copy_link)
        menu.add(0, R.id.menu_share_link, 0, R.string.share_link)
    }

    public override fun onMenuItemSelected(item: MenuItem): Boolean {
        val switchItemId0 = item.getItemId()
        if (switchItemId0 == R.id.menu_reload) {
            webView!!.reload()
        } else if (switchItemId0 == R.id.menu_copy_link) {
            copyToClipboard(requireContext(), webView!!.getUrl())
        } else if (switchItemId0 == R.id.menu_share_link) {
            val uriString = webView!!.getUrl()
            if (!isEmpty(uriString)) {
                shareLink(requireContext(), null, Uri.parse(uriString))
            }
        }
        return true
    }

    override fun onHomePressed(): Boolean {
        return false
    }

    override fun onBackPressed(): Boolean {
        if (webView!!.canGoBack()) {
            webView!!.goBack()
            return true
        }
        return false
    }

    override val isBackHandled: Boolean
        get() = webView != null && webView!!.canGoBack()

    override fun onDownloadStart(
        url: String?, userAgent: String?, contentDisposition: String?, mimetype: String?,
        contentLength: Long
    ) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setData(Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            show(R.string.unknown_address)
        }
    }

    private inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.getUrl()
            val chan = getPreferred(null, uri)
            if (chan.name != null) {
                val navigationData: NavigationData?
                if (chan.locator.safe(true).isBoardUri(uri)) {
                    navigationData = NavigationData(
                        NavigationData.Target.THREADS,
                        chan.locator.safe(true).getBoardName(uri), null, null, null
                    )
                } else if (chan.locator.safe(true).isThreadUri(uri)) {
                    navigationData = NavigationData(
                        NavigationData.Target.POSTS,
                        chan.locator.safe(true).getBoardName(uri),
                        chan.locator.safe(true).getThreadNumber(uri),
                        chan.locator.safe(true).getPostNumber(uri),
                        null
                    )
                } else {
                    navigationData = chan.locator.safe(true).handleUriClickSpecial(uri)
                }
                if (navigationData != null) {
                    val dialog = LinkDialog(chan.name, navigationData)
                    dialog.show(getChildFragmentManager(), LinkDialog::class.java.getName())
                    return true
                }
            }
            if (!chan.locator.isWebScheme(uri)) {
                val intent = Intent(Intent.ACTION_VIEW).setData(uri)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!requireContext().getPackageManager().queryIntentActivities(
                        intent,
                        PackageManager.MATCH_DEFAULT_ONLY
                    ).isEmpty()
                ) {
                    requireContext().startActivity(intent)
                    return true
                }
            }
            view.loadUrl(uri.toString())
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val title = view.getTitle()
            (requireActivity() as FragmentHandler).setTitleSubtitle(
                if (isEmptyOrWhitespace(title))
                    getString(R.string.web_browser)
                else
                    title, null
            )
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            // canGoBack() may have changed - re-sync back gesture interception.
            notifyBackHandledChanged()
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler,
            error: SslError?
        ) {
            if (isVerifyCertificate) {
                show(R.string.invalid_certificate)
                super.onReceivedSslError(view, handler, error)
            } else {
                handler.proceed()
            }
        }
    }

    class LinkDialog : DialogFragment {
        constructor()

        constructor(chanName: String?, navigationData: NavigationData?) {
            val args = Bundle()
            args.putString(EXTRA_CHAN_NAME, chanName)
            args.putParcelable(EXTRA_NAVIGATION_DATA, navigationData)
            setArguments(args)
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val chanName = requireArguments().getString(EXTRA_CHAN_NAME)
            val navigationData = BundleCompat.getParcelable<NavigationData?>(
                requireArguments(),
                EXTRA_NAVIGATION_DATA,
                NavigationData::class.java
            )
            return AlertDialog.Builder(requireContext())
                .setMessage(R.string.follow_the_link__sentence)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                    android.R.string.ok,
                    DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                        (requireActivity() as FragmentHandler).navigateTargetAllowReturn(
                            chanName,
                            navigationData!!
                        )
                    })
                .create()
        }

        companion object {
            private const val EXTRA_CHAN_NAME = "chanName"
            private const val EXTRA_NAVIGATION_DATA = "navigationData"
        }
    }

    private inner class CustomWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progressView!!.setProgress(newProgress)
        }
    }

    private class ProgressView(context: Context?) : View(context) {
        private val paint = Paint()

        private var progressSetTime: Long = 0
        private var transientProgress = 0f
        private var progress = 0

        init {
            val color = getTheme(context)!!.accent
            paint.setColor(Color.BLACK or color)
        }

        fun setProgress(progress: Int) {
            transientProgress = calculateTransient()
            progressSetTime = SystemClock.elapsedRealtime()
            this.progress = progress
            invalidate()
        }

        val time: Float
            get() = min(
                (SystemClock.elapsedRealtime() - progressSetTime).toFloat() / TRANSIENT_TIME,
                1f
            )

        fun calculateTransient(): Float {
            return lerp(
                transientProgress, progress.toFloat(),
                this.time
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val paint = this.paint
            val progress = this.progress
            val transientProgress = calculateTransient()
            var alpha = 0xff
            var needInvalidate = transientProgress != progress.toFloat()
            if (progress == 100) {
                val t = this.time
                alpha = (0xff * (1f - t)).toInt()
                needInvalidate = needInvalidate or (t < 1f)
            }
            paint.setAlpha(alpha)
            if (transientProgress > 0 && alpha > 0x00) {
                val width = getWidth() * transientProgress / 100
                canvas.drawRect(0f, 0f, width, getHeight().toFloat(), paint)
            }
            if (needInvalidate) {
                invalidate()
            }
        }

        companion object {
            private const val TRANSIENT_TIME = 200
        }
    }

    companion object {
        private const val EXTRA_URI = "uri"
    }
}
