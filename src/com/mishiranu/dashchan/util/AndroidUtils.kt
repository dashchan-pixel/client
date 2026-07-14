package com.mishiranu.dashchan.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object AndroidUtils {
    fun interface OnReceiveListener {
        fun onReceive(
            receiver: BroadcastReceiver,
            context: Context,
            intent: Intent,
        )
    }

    @JvmStatic
    fun createReceiver(listener: OnReceiveListener): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                listener.onReceive(this, context, intent)
            }
        }

    @JvmStatic
    fun getApplicationLabel(context: Context): String = context.applicationInfo.loadLabel(context.packageManager).toString()

    @JvmStatic
    fun createHeadsUpNotificationChannel(
        id: String,
        name: CharSequence,
    ): NotificationChannel =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            vibrationPattern = longArrayOf(0)
        }
}
