package com.mishiranu.dashchan.content.net.firewall;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.mishiranu.dashchan.ui.WebViewDialog;
import com.mishiranu.dashchan.util.WebViewUtils;

import java.util.Map;

import chan.http.FirewallResolver;


public abstract class FirewallResolutionDialog<T> extends WebViewDialog {
	private FirewallResolutionDialogRequest<T> request;
	private boolean firewallResolutionFinished = false;

	public FirewallResolutionDialog() {
	}

	public FirewallResolutionDialog(FirewallResolutionDialogRequest<T> request) {
		Bundle args = new Bundle();
		setArguments(args);
		this.request = request;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		FirewallResolutionViewModel<T> viewModel = new ViewModelProvider(this).get(FirewallResolutionViewModel.class);
		if (request != null) {
			viewModel.setRequest(request);
		} else {
			request = viewModel.getRequest();
		}
		if (request == null) {
			Log.e("FirewallResolverDialog", "onCreate: request is null, dismissing");
		}
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		WebViewUtils.clearAll(webView);
		setupWebViewSettings();
		webView.setWebViewClient(new WebViewClientImpl());
		setWebChromeClient(new WebChromeClientImpl());
		WebViewUtils.setProxy(requireActivity(), request.getProxyData(), () -> webView.loadUrl(request.getUrl()));
	}

	@SuppressLint("SetJavaScriptEnabled")
	private void setupWebViewSettings() {
		WebSettings webViewSettings = webView.getSettings();
		webViewSettings.setUserAgentString(request.getUserAgent());
		webViewSettings.setJavaScriptEnabled(true);
		webViewSettings.setDomStorageEnabled(true);
	}

	@Override
	public void onDismiss(@NonNull DialogInterface dialog) {
		super.onDismiss(dialog);
		boolean checkFirewallResolutionResultAfterDismiss = request != null && !requireActivity().isChangingConfigurations();
		if (checkFirewallResolutionResultAfterDismiss) {
			WebViewUtils.setProxy(requireContext(), null, null);
			FirewallResolver.WebViewClient<T> client = request.getClient();
			T firewallResolutionResult = client.getResult();
			if (firewallResolutionResult == null || !firewallResolutionFinished) {
				checkFirewallResolutionFinished(webView.getUrl(), webView.getTitle());
				firewallResolutionResult = client.getResult();
			}
			onFirewallResolutionFinished(firewallResolutionResult);
		}
	}

	protected abstract void onFirewallResolutionFinished(T firewallResolutionResult);

	private void checkFirewallAndDismissDialogIfResolutionFinished(String url, String title) {
		if (!firewallResolutionFinished) {
			firewallResolutionFinished = checkFirewallResolutionFinished(url, title);
			if (firewallResolutionFinished) {
				webView.stopLoading();
				dismiss();
			}
		}
	}

	private boolean checkFirewallResolutionFinished(String url, String title) {
		Map<String, String> cookies = FirewallUtils.parseCookies(CookieManager.getInstance().getCookie(url));
		Uri uri = Uri.parse(url);
		return firewallResolutionFinished = request.getClient().onPageFinished(uri, cookies, title);
	}

	public static class FirewallResolutionViewModel<T> extends ViewModel {
		private FirewallResolutionDialogRequest<T> request;

		public FirewallResolutionDialogRequest<T> getRequest() {
			return request;
		}

		void setRequest(FirewallResolutionDialogRequest<T> request) {
			this.request = request;
		}

		@Override
		protected void onCleared() {
			super.onCleared();
			request = null;
		}
	}

	private class WebViewClientImpl extends WebViewClient {

		@Override
		public void onPageCommitVisible(WebView view, String url) {
			checkFirewallAndDismissDialogIfResolutionFinished(url, view.getTitle());
		}

		@Override
		public void onPageFinished(WebView view, String url) {
			checkFirewallAndDismissDialogIfResolutionFinished(url, view.getTitle());
		}
	}

	private class WebChromeClientImpl extends WebChromeClient {

		@Override
		public void onReceivedTitle(WebView view, String title) {
			checkFirewallAndDismissDialogIfResolutionFinished(view.getUrl(), title);
		}

		@Override
		public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
			String message = consoleMessage.message();
			if (message != null && message.contains("SyntaxError")) {
				firewallResolutionFinished = true;
				dismiss();
				return true;
			}
			return false;
		}

	}

}
