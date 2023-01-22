package com.mishiranu.dashchan.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputFilter;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import chan.content.ChanConfiguration;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;

import java.util.Timer;
import java.util.TimerTask;

public class CaptchaForm implements View.OnClickListener, View.OnLongClickListener,
		TextView.OnEditorActionListener {
	public enum CaptchaViewType {LOADING, IMAGE, SKIP, SKIP_LOCK, ERROR}

	private final Callback callback;
	private final boolean hideInput;
	private final boolean applyHeight;
	private final View blockParentView;
	private final View blockView;
	private final View skipBlockView;
	private final TextView skipTextView;
	private final View loadingView;
	private final ImageView imageView;
	private final View inputParentView;
	private final EditText inputView;
	private final View cancelView;

	private final TextView captchaTTL;
	private final int ttl;
	private final Timer captchaTTLDecreaseTimer;
	private CaptchaTTLDecreaseTask captchaTTLDecreaseTask;
	private final Timer captchaExpiredTimer;
	private TimerTask captchaExpiredTask;

	private ChanConfiguration.Captcha.Input captchaInput;

	public interface Callback {
		void onRefreshCaptcha(boolean forceRefresh);
		void onConfirmCaptcha();
	}

	static class CaptchaTTLDecreaseTask extends TimerTask {

		private int currentTTL;
		private final Handler handler;

		public CaptchaTTLDecreaseTask(int ttl, Handler handler) {
			this.currentTTL = ttl;
			this.handler = handler;
		}

		@Override
		public void run() {
			handler.obtainMessage(currentTTL).sendToTarget();
			currentTTL--;
		}

	}

	static class CaptchaExpiredMessage  {

		public ReadCaptchaTask.CaptchaState state;
		public ChanConfiguration.Captcha.Input input;
		public boolean large;
		public boolean invertColors;

		public CaptchaExpiredMessage(ReadCaptchaTask.CaptchaState state, ChanConfiguration.Captcha.Input input,
			 	boolean large, boolean invertColors) {
			this.state = state;
			this.input = input;
			this.large = large;
			this.invertColors = invertColors;
		}

	}

	public CaptchaForm(Callback callback, boolean hideInput, boolean applyHeight,
			View container, View inputParentView, EditText inputView, ChanConfiguration.Captcha captcha) {
		this.callback = callback;
		this.hideInput = hideInput;
		this.applyHeight = applyHeight;
		blockParentView = container.findViewById(R.id.captcha_block_parent);
		blockView = container.findViewById(R.id.captcha_block);
		imageView = container.findViewById(R.id.captcha_image);
		loadingView = container.findViewById(R.id.captcha_loading);
		skipBlockView = container.findViewById(R.id.captcha_skip_block);
		skipTextView = container.findViewById(R.id.captcha_skip_text);
		captchaTTL = container.findViewById(R.id.captcha_ttl);
		captchaTTL.setVisibility(View.GONE);
		ttl = captcha.ttl;
		captchaTTLDecreaseTimer = new Timer(true);
		captchaExpiredTimer = new Timer(true);
		this.inputParentView = inputParentView;
		this.inputView = inputView;
		if (hideInput) {
			inputView.setVisibility(View.GONE);
		}
		ImageView cancelView = container.findViewById(R.id.captcha_cancel);
		this.cancelView = cancelView;
		if (C.API_LOLLIPOP) {
			cancelView.setImageTintList(ResourceUtils.getColorStateList(cancelView.getContext(),
					android.R.attr.textColorPrimary));
			skipTextView.setAllCaps(true);
			skipTextView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
			ViewUtils.setTextSizeScaled(skipTextView, 12);
		}
		updateCaptchaHeight(false);
		captchaInput = captcha.input;
		if (captchaInput == null) {
			captchaInput = ChanConfiguration.Captcha.Input.ALL;
		}
		updateCaptchaInput(captchaInput);
		inputView.setFilters(new InputFilter[] {new InputFilter.LengthFilter(50)});
		inputView.setOnEditorActionListener(this);
		cancelView.setOnClickListener(this);
		blockParentView.setOnClickListener(this);
		blockParentView.setOnLongClickListener(this);
		if (inputParentView != null) {
			inputParentView.setOnClickListener(this);
		}
	}

	private void updateCaptchaInput(ChanConfiguration.Captcha.Input input) {
		switch (input) {
			case ALL: {
				inputView.setInputType(InputType.TYPE_CLASS_TEXT);
				break;
			}
			case LATIN: {
				inputView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
				break;
			}
			case NUMERIC: {
				inputView.setInputType(InputType.TYPE_CLASS_NUMBER);
				break;
			}
		}
	}

	private void updateCaptchaHeight(boolean large) {
		if (applyHeight) {
			float density = ResourceUtils.obtainDensity(inputView);
			int height = (int) ((Preferences.isHugeCaptcha() || large ? 96f : 48f) * density);
			ViewGroup.LayoutParams layoutParams = blockView.getLayoutParams();
			if (height != layoutParams.height) {
				layoutParams.height = height;
				blockView.requestLayout();
			}
		}
	}

	private void calculateCaptchaTTLPadding(boolean large) {
		if (imageView.getDrawable() == null)
			return;
		int imageViewWidth = imageView.getDrawable().getIntrinsicWidth();
		float density = ResourceUtils.obtainDensity(imageView);
		int calculated = blockView.getWidth() / 2  - imageViewWidth / 2
				- (int) (96f * density) / 2;
		captchaTTL.setPadding(0,0, calculated,0);
	}

	private void scheduleTimerTask(boolean large, boolean invertColors) {
		cancelTTLDecreaseTask(false);
		Handler captchaHandler = new Handler(Looper.getMainLooper()) {
			@Override
			public void handleMessage(@NonNull Message msg) {
				captchaTTL.setText(String.valueOf(msg.what));
			}
		};
		captchaTTLDecreaseTask = new CaptchaTTLDecreaseTask(ttl, captchaHandler);
		captchaTTLDecreaseTimer.scheduleAtFixedRate(captchaTTLDecreaseTask, 0, 1000);
		Handler captchaExpiredHandler = new Handler(Looper.getMainLooper()) {
			@Override
			public void handleMessage(@NonNull Message msg) {
				if (Preferences.isCaptchaAutoReload()) {
					callback.onRefreshCaptcha(false);
				} else {
					CaptchaExpiredMessage message = (CaptchaExpiredMessage) msg.obj;
					showCaptcha(message.state, message.input, null, message.large, message.invertColors);
				}
			}
		};
		captchaExpiredTask = new TimerTask() {
			@Override
			public void run() {
				cancelTTLDecreaseTask(true);
				CaptchaExpiredMessage msg = new CaptchaExpiredMessage(
						ReadCaptchaTask.CaptchaState.NEED_LOAD,
						captchaInput,
						large,
						invertColors
				);
				captchaExpiredHandler.obtainMessage(1, msg).sendToTarget();
			}
		};
		captchaExpiredTimer.schedule( captchaExpiredTask, ttl * 1000);
	}

	private void cancelTTLDecreaseTask(boolean calledByTimer) {
		if (captchaTTLDecreaseTask != null)
			captchaTTLDecreaseTask.cancel();
		if (!calledByTimer && captchaExpiredTask != null)
			captchaExpiredTask.cancel();

	}

	@Override
	public void onClick(View v) {
		if (v == cancelView) {
			callback.onRefreshCaptcha(true);
		} else if (v == blockParentView && v.isClickable()) {
			callback.onRefreshCaptcha(false);
		} else if (inputParentView != null && v == inputParentView) {
			inputView.requestFocus();
			InputMethodManager inputMethodManager = (InputMethodManager) v.getContext()
					.getSystemService(Context.INPUT_METHOD_SERVICE);
			if (inputMethodManager != null) {
				inputMethodManager.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT);
			}
		}
	}

	@Override
	public boolean onLongClick(View v) {
		if (v == blockParentView) {
			callback.onRefreshCaptcha(true);
			return true;
		}
		return false;
	}

	@Override
	public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
		callback.onConfirmCaptcha();
		return true;
	}

	public void showCaptcha(ReadCaptchaTask.CaptchaState captchaState, ChanConfiguration.Captcha.Input input,
			Bitmap image, boolean large, boolean invertColors) {
		switch (captchaState) {
			case CAPTCHA: {
				imageView.setImageBitmap(image);
				imageView.setColorFilter(invertColors ? GraphicsUtils.INVERT_FILTER : null);
				switchToCaptchaView(CaptchaViewType.IMAGE, input, large, invertColors);
				break;
			}
			case NEED_LOAD:
			case MAY_LOAD:
			case MAY_LOAD_SOLVING: {
				skipTextView.setText(R.string.load_captcha);
				cancelView.setVisibility(captchaState == ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING
						? View.VISIBLE : View.GONE);
				switchToCaptchaView(CaptchaViewType.SKIP, null, false, invertColors);
				break;
			}
			case SKIP: {
				skipTextView.setText(R.string.captcha_is_not_required);
				cancelView.setVisibility(View.VISIBLE);
				switchToCaptchaView(CaptchaViewType.SKIP_LOCK, null, false, invertColors);
				break;
			}
			case PASS: {
				skipTextView.setText(R.string.captcha_pass);
				cancelView.setVisibility(View.VISIBLE);
				switchToCaptchaView(CaptchaViewType.SKIP, null, false, invertColors);
				break;
			}
		}
	}

	public void showError() {
		imageView.setImageResource(android.R.color.transparent);
		skipTextView.setText(R.string.load_captcha);
		cancelView.setVisibility(View.GONE);
		switchToCaptchaView(CaptchaViewType.ERROR, null, false, false);
	}

	public void showLoading() {
		inputView.setText(null);
		switchToCaptchaView(CaptchaViewType.LOADING, null, false, false);
	}

	private void setInputEnabled(boolean enabled, boolean switchVisibility) {
		inputView.setEnabled(enabled);
		if (hideInput && switchVisibility) {
			inputView.setVisibility(enabled ? View.VISIBLE : View.GONE);
		}
	}

	private void switchToCaptchaView(CaptchaViewType captchaViewType,
			ChanConfiguration.Captcha.Input input, boolean large, boolean invertColors) {
		switch (captchaViewType) {
			case LOADING: {
				blockParentView.setClickable(true);
				blockView.setVisibility(View.VISIBLE);
				imageView.setVisibility(View.GONE);
				imageView.setImageResource(android.R.color.transparent);
				loadingView.setVisibility(View.VISIBLE);
				skipBlockView.setVisibility(View.GONE);
				setInputEnabled(false, false);
				updateCaptchaHeight(false);
				captchaTTL.setVisibility(View.GONE);
				break;
			}
			case ERROR: {
				blockParentView.setClickable(true);
				blockView.setVisibility(View.VISIBLE);
				imageView.setVisibility(View.VISIBLE);
				loadingView.setVisibility(View.GONE);
				skipBlockView.setVisibility(View.VISIBLE);
				setInputEnabled(false, false);
				updateCaptchaHeight(false);
				captchaTTL.setVisibility(View.GONE);
				break;
			}
			case IMAGE: {
				blockParentView.setClickable(true);
				blockView.setVisibility(View.VISIBLE);
				imageView.setVisibility(View.VISIBLE);
				loadingView.setVisibility(View.GONE);
				skipBlockView.setVisibility(View.GONE);
				setInputEnabled(true, true);
				updateCaptchaInput(input != null ? input : captchaInput);
				updateCaptchaHeight(large);
				if (Preferences.isCaptchaTTL() && ttl > 0) {
					if (Preferences.isHugeCaptcha() || large) {
						calculateCaptchaTTLPadding(large);
						captchaTTL.setVisibility(View.VISIBLE);
					}
					scheduleTimerTask(large, invertColors);
				} else {
					captchaTTL.setVisibility(View.GONE);
				}
				break;
			}
			case SKIP:
			case SKIP_LOCK: {
				blockParentView.setClickable(captchaViewType != CaptchaViewType.SKIP_LOCK);
				blockView.setVisibility(View.INVISIBLE);
				imageView.setVisibility(View.VISIBLE);
				imageView.setImageResource(android.R.color.transparent);
				loadingView.setVisibility(View.GONE);
				skipBlockView.setVisibility(View.VISIBLE);
				setInputEnabled(false, true);
				updateCaptchaHeight(false);
				captchaTTL.setVisibility(View.GONE);
				break;
			}
		}
	}

	public void setText(String text) {
		inputView.setText(text);
	}

	public String getInput() {
		return inputView.getText().toString();
	}
}
