package com.mishiranu.dashchan.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AndroidUtils {
	public interface OnReceiveListener {
		void onReceive(BroadcastReceiver receiver, Context context, Intent intent);
	}

	public static BroadcastReceiver createReceiver(OnReceiveListener listener) {
		return new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				listener.onReceive(this, context, intent);
			}
		};
	}

	public static String getApplicationLabel(Context context) {
		return context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
	}

	public static NotificationChannel createHeadsUpNotificationChannel(String id, CharSequence name) {
		NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
		channel.setSound(null, null);
		channel.setVibrationPattern(new long[] {0});
		return channel;
	}
}
