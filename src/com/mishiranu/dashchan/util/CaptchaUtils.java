package com.mishiranu.dashchan.util;

import com.mishiranu.dashchan.content.Preferences;

/*
    A special class for working with some additional captcha features,
    for example TTL if it is enabled
 */

public class CaptchaUtils {

    private static final CaptchaUtils INSTANCE = new CaptchaUtils();

    private String currentTTLId = "";
    private int currentTTL;
    private Callback callback;
    private boolean lockCallback = false;

    public interface Callback {
        void onTTLChange(int ttl);
        void onCaptchaTimeout(boolean needReload);
    }

    public static CaptchaUtils getInstance() {
        return INSTANCE;
    }

    private final Runnable refreshCaptchaTTL = () -> {
        if (callback != null) {
            callback.onTTLChange(currentTTL);
            queueNextCaptchaTTLUpdate();
            if (currentTTL > 0)
                currentTTL--;
        }
    };

    private void queueNextCaptchaTTLUpdate() {
        if (currentTTL > 0) {
            ConcurrentUtils.HANDLER.removeCallbacks(refreshCaptchaTTL);
            ConcurrentUtils.HANDLER.postDelayed(refreshCaptchaTTL, 1000);
        } else {
            if (!lockCallback && callback != null) {
                callback.onCaptchaTimeout(Preferences.isCaptchaAutoReload());
            }
        }
    }

    /*
        The functionality will work only if Chan has obtained the captcha configuration and the feature is activated in the settings.
        CaptchaUtils bind to a specific CaptchaForm object, remembering the chan, board, and thread names.

        If the posting form was closed and reopened, a new CaptchaForm object is created,
        but the image is not reloaded, but restored from the state.
        For this reason, we remember the "identifier"(id) and in this case do not restart the timer.

     */
    public void registerCaptchaTTL(String chan, String board, String thread, int ttl, Callback callback) {
        if (!lockCallback) {
            if (isCaptchaTTLEnabled() && ttl != -1) {
                currentTTLId = toHolderId(chan, board, thread);
                currentTTL = ttl;
                this.callback = callback;
                queueNextCaptchaTTLUpdate();
            }
        } else {
            /*
                If the captcha was blocked, we will check if the ID matches or not.
                This will either restart the timer (with the possibility of calling timeout immediately)
                or initialize a new one for the new posting form
             */
            String captchaID = toHolderId(chan, board, thread);
            lockCallback = false;
            if (captchaID.equals(currentTTLId)) {
                registerCaptchaTTL(chan, board, thread, currentTTL, callback);
            } else {
                registerCaptchaTTL(chan, board, thread, ttl, callback);
            }
        }
    }

    public void clear() {
        ConcurrentUtils.HANDLER.removeCallbacks(refreshCaptchaTTL);
        currentTTLId = "";
        lockCallback = false;
    }

    public void lockCallback() {
        lockCallback = true;
    }

    private static String toHolderId(String chan, String board, String thread) {
        return chan + "-" + board + "-" + thread;
    }

    public static boolean isCaptchaTTLEnabled() {
        return Preferences.isCaptchaTTL() && Preferences.isHugeCaptcha();
    }

}
