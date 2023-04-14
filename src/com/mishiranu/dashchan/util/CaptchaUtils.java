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

    public interface Callback {
        void onTTLChange(int ttl);
        void onCaptchaTimeout(boolean needReload);
    }

    public static CaptchaUtils getInstance() {
        return INSTANCE;
    }

    private int getCurrentTTL(){
        return currentTTL;
    }

    private final Runnable refreshCaptchaTTL = () -> {
        if (callback != null) {
            callback.onTTLChange(getCurrentTTL());
            queueNextCaptchaTTLUpdate();
            currentTTL--;
        }
    };

    private void queueNextCaptchaTTLUpdate() {
        if (currentTTL > 0) {
            ConcurrentUtils.HANDLER.removeCallbacks(refreshCaptchaTTL);
            ConcurrentUtils.HANDLER.postDelayed(refreshCaptchaTTL, 1000);
        } else {
            callback.onCaptchaTimeout(Preferences.isCaptchaAutoReload());
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
        if (ttl > 0 && isCaptchaTTLEnabled()) {
            if (!currentTTLId.equals(toHolderId(chan, board, thread))) {
                currentTTLId = toHolderId(chan, board, thread);
                currentTTL = ttl;
                this.callback = callback;
                queueNextCaptchaTTLUpdate();
            } else {
                this.callback = callback;
            }
        }
    }

    public void clear() {
        ConcurrentUtils.HANDLER.removeCallbacks(refreshCaptchaTTL);
        currentTTLId = "";
        callback = null;
    }

    private static String toHolderId(String chan, String board, String thread) {
        return chan + "-" + board + "-" + thread;
    }

    public static boolean isCaptchaTTLEnabled() {
        if (Preferences.isCaptchaTTL() && Preferences.isHugeCaptcha()) {
            return true;
        }
        return false;
    }

}
