package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ProgressDialog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class GoogleImageSearchDialog {

	public static void show(String imageUrl, FragmentManager fragmentManager) {
		new InstanceDialog(fragmentManager, null, (provider -> {
			GoogleImageSearchViewModel viewModel = provider.getViewModel(GoogleImageSearchViewModel.class);
			viewModel.getGoogleImageSearchUriForImage(imageUrl);

			ProgressDialog progressDialog = new ProgressDialog(provider.getContext(), null);
			progressDialog.setMessage(provider.getContext().getString(R.string.please_wait));
			progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, provider.getContext().getString(android.R.string.cancel), ((dialog, which) -> viewModel.cancel()));

			viewModel.getResult().observe(provider.getLifecycleOwner(), (result -> {
				if (!result.isFailure && result.googleImageSearchUri != null) {
					NavigationUtils.handleUri(provider.getContext(), null, result.googleImageSearchUri, NavigationUtils.BrowserType.EXTERNAL);
				} else {
					ClickableToast.show(R.string.unable_to_get_search_link);
				}
				progressDialog.dismiss();
			}));

			return progressDialog;
		}));
	}

	public static class GoogleImageSearchViewModel extends ViewModel {
		private final MutableLiveData<Result> result = new MutableLiveData<>();
		private final String LOG_TAG = "GoogleImageSearch";
		private boolean running = false;
		private WebView webView;

		private void getGoogleImageSearchUriForImage(String imageUrl) {
			if (!running) {
				running = true;
				initializeWebView();
				String googleLensSearchUrl = buildGoogleLensSearchUrl(imageUrl);
				webView.loadUrl(googleLensSearchUrl);
			}
		}

		@SuppressLint("AddJavascriptInterface")
		private void initializeWebView() {
			webView = new WebView(MainApplication.getInstance());
			configureWebViewSettings();
			setWebViewClient();
			webView.addJavascriptInterface(this, "AndroidBridge");
		}

		@SuppressLint("SetJavaScriptEnabled")
		private void configureWebViewSettings() {
			WebSettings settings = webView.getSettings();
			settings.setJavaScriptEnabled(true);
			String iphoneUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 14_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0.3 Mobile/15E148 Safari/604.1";
			settings.setUserAgentString(iphoneUserAgent);
		}

		private void setWebViewClient() {
			webView.setWebViewClient(new WebViewClient() {
				private boolean googleImageSearchUrlExtractorJavascriptInjected = false;

				@Override
				public void onPageFinished(WebView view, String url) {
					if (cookiesPageLoaded(url)) {
						injectRejectAllButtonClickJavaScript();
					} else if (!googleImageSearchUrlExtractorJavascriptInjected && googleLensSearchResultsPageLoaded(url)) {
						injectGoogleImageSearchUrlExtractorJavascript();
						googleImageSearchUrlExtractorJavascriptInjected = true;
					}
				}

				private boolean cookiesPageLoaded(String loadedPageUrl) {
					return Uri.parse(loadedPageUrl).getHost().equals("consent.google.com");
				}

				private void injectRejectAllButtonClickJavaScript() {
					String rejectAllButtonSelector = ".VfPpkd-LgbsSe.VfPpkd-LgbsSe-OWXEXe-k8QpJ.VfPpkd-LgbsSe-OWXEXe-dgl2Hf.nCP5yc.AjY5Oe.DuMIQc.LQeN7.Nc7WLe";
					String rejectAllButtonClickJavaScript = String.format("document.querySelector('%s').click();", rejectAllButtonSelector);
					injectJavaScript(rejectAllButtonClickJavaScript);
				}

				private boolean googleLensSearchResultsPageLoaded(String loadedPageUrl) {
					Uri uri = Uri.parse(loadedPageUrl);
					return uri.getHost().equals("lens.google.com") && uri.getPathSegments().contains("search");
				}

				private void injectGoogleImageSearchUrlExtractorJavascript() {
					try {
						String googleImageSearchUrlExtractorJavascript = readGoogleImageSearchUrlExtractorJavascriptFromAssets();
						injectJavaScript(googleImageSearchUrlExtractorJavascript);
					} catch (IOException e) {
						Log.e(LOG_TAG, "Error when reading javascript from assets", e);
						result.setValue(Result.failure());
					}
				}

				@SuppressWarnings("CharsetObjectCanBeUsed")
				private String readGoogleImageSearchUrlExtractorJavascriptFromAssets() throws IOException {
					StringBuilder sb = new StringBuilder();
					InputStream is = webView.getContext().getAssets().open("google_image_search_url_extractor.js");
					Charset charset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ? StandardCharsets.UTF_8 : Charset.forName("UTF-8");
					BufferedReader br = new BufferedReader(new InputStreamReader(is, charset));
					String s;
					while ((s = br.readLine()) != null) {
						sb.append(s);
					}
					br.close();
					return sb.toString();
				}

				private void injectJavaScript(String javaScript) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
						webView.evaluateJavascript(javaScript, null);
					} else {
						webView.loadUrl("javascript:(function f() { " + javaScript + "} )()");
					}
				}

				@Override
				public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
					Log.e(LOG_TAG, String.format("Error code: %d, description: %s", errorCode, description));
					result.setValue(Result.failure());
				}

			});
		}

		private String buildGoogleLensSearchUrl(String imageUrl) {
			return new Uri.Builder()
					.scheme("https")
					.authority("lens.google.com")
					.appendPath("uploadbyurl")
					.appendQueryParameter("url", imageUrl)
					.toString();
		}

		@JavascriptInterface
		public void handleFindImageSourceUrl(String findImageSourceUrl) {
			result.postValue(Result.success(Uri.parse(findImageSourceUrl)));
		}

		@JavascriptInterface
		public void handleNoImageAtThatUrl() {
			Log.e(LOG_TAG, "No image at that URL");
			result.postValue(Result.failure());
		}

		@Override
		protected void onCleared() {
			cancel();
		}

		private void cancel() {
			if (webView != null) {
				webView.destroy();
				webView = null;
			}
		}

		private LiveData<Result> getResult() {
			return result;
		}

		private static final class Result {
			boolean isFailure;
			Uri googleImageSearchUri;

			private Result(boolean isFailure, Uri googleImageSearchUri) {
				this.isFailure = isFailure;
				this.googleImageSearchUri = googleImageSearchUri;
			}

			private static Result success(Uri googleImageSearchUri) {
				return new Result(false, googleImageSearchUri);
			}

			private static Result failure() {
				return new Result(true, null);
			}

		}

	}

}