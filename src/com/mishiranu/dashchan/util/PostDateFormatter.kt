package com.mishiranu.dashchan.util

import android.content.Context
import chan.util.StringUtils
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

class PostDateFormatter(
    context: Context,
) {
    private val instance: String
    private val dateFormat: DateFormat
    private val timeFormat: DateFormat

    init {
        val systemFormat =
            android.text.format.DateFormat
                .getDateFormat(context) as SimpleDateFormat
        val systemPattern = systemFormat.toPattern()
        val dayFormat = if (systemPattern.contains("dd")) "dd" else "d"
        val monthFormat = if (systemPattern.contains("MM")) "MM" else "M"
        val index = StringUtils.nearestIndexOf(systemPattern, 0, '.', '/', '-')
        val divider = if (index >= 0) systemPattern[index] else '.'
        val shortDateFormat =
            if (systemPattern.indexOf('d') > systemPattern.indexOf('M')) {
                "$monthFormat$divider$dayFormat"
            } else {
                "$dayFormat$divider$monthFormat"
            }
        val longDateFormat =
            if (systemPattern.indexOf('d') > systemPattern.indexOf('y')) {
                "yy$divider$shortDateFormat"
            } else {
                "$shortDateFormat${divider}yy"
            }
        val timeFormat =
            if (android.text.format.DateFormat
                    .is24HourFormat(context)
            ) {
                "HH:mm:ss"
            } else {
                "hh:mm:ss aa"
            }
        this.instance = longDateFormat + timeFormat + Locale.getDefault().toString()
        this.dateFormat = SimpleDateFormat(longDateFormat, Locale.getDefault())
        this.timeFormat = SimpleDateFormat(timeFormat, Locale.US)
    }

    fun formatDate(timestamp: Long): String = dateFormat.format(timestamp)

    fun formatDateTime(timestamp: Long): String = dateFormat.format(timestamp) + " " + timeFormat.format(timestamp)

    fun formatDateTime(
        timestamp: Long,
        holder: Holder?,
    ): Holder {
        if (holder != null && holder.instance == instance) {
            return holder
        }
        return Holder(formatDateTime(timestamp), instance)
    }

    class Holder internal constructor(
        @JvmField val text: String,
        internal val instance: String,
    )
}
