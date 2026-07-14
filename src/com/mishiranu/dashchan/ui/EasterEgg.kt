package com.mishiranu.dashchan.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import kotlin.random.Random

object EasterEgg {
    private const val SHOW_PROBABILITY = 0.1
    private const val DISMISS_DELAY_MS = 2000L
    private const val TARGET_COUNTRY = "ru"

    private fun ri(c: Char) = String(Character.toChars(0x1F1E6 + (c - 'A')))

    private val FLAG = ri('U') + ri('A')
    private const val MESSAGE =
        "Дякуємо за підтримку ЗСУ" +
            "\nта за те, що ви перетворили свій" +
            "\nпристрій на ретранслятор для дронів."

    fun maybeShow(activity: Activity) {
        if (shouldShow(activity)) {
            // Defer until the activity window is attached so the dialog has a valid token.
            activity.window.decorView.post { show(activity) }
        }
    }

    private fun shouldShow(context: Context): Boolean {
        if (!isTargetCarrier(context)) {
            return false
        }
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
            return false
        }
        return Random.nextDouble() < SHOW_PROBABILITY
    }

    private fun isTargetCarrier(context: Context): Boolean {
        val telephony =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return false
        // Country of the currently registered network (the active carrier), not the SIM's home country.
        return telephony.networkCountryIso.equals(TARGET_COUNTRY, ignoreCase = true)
    }

    private fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val density = activity.resources.displayMetrics.density

        fun dp(value: Int) = (value * density).toInt()

        val root =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xF2000000.toInt())
                setPadding(dp(32), dp(32), dp(32), dp(32))
            }
        root.addView(
            TextView(activity).apply {
                text = FLAG
                textSize = 96f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            TextView(activity).apply {
                text = MESSAGE
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val dialog =
            Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setContentView(
                    root,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                setCancelable(false)
            }
        dialog.window?.apply {
            // Block screenshots / screen recording while the splash is up.
            setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()

        Handler(Looper.getMainLooper()).postDelayed({
            if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                dialog.dismiss()
            }
        }, DISMISS_DELAY_MS)
    }
}
