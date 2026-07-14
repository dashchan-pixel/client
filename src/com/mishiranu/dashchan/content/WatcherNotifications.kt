package com.mishiranu.dashchan.content

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import chan.util.StringUtils.clearHtml
import chan.util.StringUtils.formatHex
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.database.PagesDatabase.InsertResult.Reply
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.ui.MainActivity
import com.mishiranu.dashchan.util.ConcurrentUtils.newSingleThreadPool
import com.mishiranu.dashchan.util.Hasher.Companion.getInstanceSha256
import java.util.concurrent.Executor

object WatcherNotifications {
    private val EXECUTOR: Executor = newSingleThreadPool(1000, "WatcherNotifications", null)

    @JvmStatic
    fun configure(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                C.NOTIFICATION_CHANNEL_REPLIES,
                context.getString(R.string.replies),
                NotificationManager.IMPORTANCE_HIGH,
            )
        channel.enableLights(true)
        channel.enableVibration(true)
        notificationManager.createNotificationChannel(channel)
    }

    fun notifyReplies(
        context: Context,
        color: Int,
        important: Boolean,
        sound: Boolean,
        vibration: Boolean,
        title: String?,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        replies: List<Reply>,
    ) {
        EXECUTOR.execute(
            Task(
                context,
                color,
                important,
                sound,
                vibration,
                title,
                chanName,
                boardName,
                threadNumber,
                replies,
                mutableListOf(),
            ),
        )
    }

    fun cancelReplies(
        context: Context,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumbers: Collection<PostNumber>,
    ) {
        EXECUTOR.execute(
            WatcherNotifications.Task(
                context,
                0,
                false,
                false,
                false,
                null,
                chanName,
                boardName,
                threadNumber,
                listOf<Reply>(),
                postNumbers,
            ),
        )
    }

    private class Task(
        context: Context,
        val color: Int,
        val important: Boolean,
        val sound: Boolean,
        val vibration: Boolean,
        val title: String?,
        val chanName: String?,
        val boardName: String?,
        val threadNumber: String?,
        val replies: List<Reply>,
        val removePostNumbers: Collection<PostNumber>,
    ) : Runnable {
        val context: Context

        init {
            this.context = context.getApplicationContext()
        }

        override fun run() {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!replies.isEmpty()) {
                notifyReplies(notificationManager)
            }
            if (!removePostNumbers.isEmpty()) {
                cancelReplies(notificationManager)
            }
        }

        fun notifyReplies(notificationManager: NotificationManager) {
            val title = context.getString(R.string.reply_in_thread__format, this.title)
            for (reply in replies) {
                val builder = NotificationCompat.Builder(context, C.NOTIFICATION_CHANNEL_REPLIES)
                val comment = clearHtml(reply.comment)
                val text = comment.replace('\n', ' ').replace(" {2,}".toRegex(), " ")
                builder.setContentTitle(title)
                builder.setContentText(text)
                if (important) {
                    builder.setTicker((title + "\n" + text).trim { it <= ' ' })
                }
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText(buildLongComment(comment)),
                )
                builder.setWhen(reply.timestamp)
                configureNotification(builder, color)
                builder.setGroup(GROUP_REPLIES)
                builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)

                val tag: String? = makeTag(chanName, boardName, threadNumber, reply.postNumber)
                val intent =
                    Intent(context, MainActivity::class.java)
                        .setAction(tag)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(C.EXTRA_CHAN_NAME, chanName)
                        .putExtra(C.EXTRA_BOARD_NAME, boardName)
                        .putExtra(C.EXTRA_THREAD_NUMBER, threadNumber)
                        .apply {
                            // reply.postNumber is nullable, and a bare toString() writes the
                            // literal string "null" into the extra. The reader
                            // (MainActivity.parseNullable) then gets a non-null "null" and
                            // tries to parse it as a post number. Omit the extra instead.
                            reply.postNumber?.let { putExtra(C.EXTRA_POST_NUMBER, it.toString()) }
                        }
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                notificationManager.notify(tag, C.NOTIFICATION_ID_REPLIES, builder.build())
            }
            val builder = NotificationCompat.Builder(context, C.NOTIFICATION_CHANNEL_REPLIES)
            configureNotification(builder, color)
            builder.setGroup(GROUP_REPLIES)
            builder.setGroupSummary(true)
            notificationManager.notify(C.NOTIFICATION_ID_REPLIES, builder.build())
        }

        fun cancelReplies(notificationManager: NotificationManager) {
            var tags: MutableSet<String?>? = null
            val notifications = notificationManager.getActiveNotifications()
            if (notifications != null && notifications.size > 0) {
                tags = HashSet<String?>()
                for (notification in notifications) {
                    val tag = notification.getTag()
                    if (tag != null && notification.getId() == C.NOTIFICATION_ID_REPLIES) {
                        tags.add(tag)
                    }
                }
            }

            for (postNumber in removePostNumbers) {
                val tag: String? = makeTag(chanName, boardName, threadNumber, postNumber)
                notificationManager.cancel(tag, C.NOTIFICATION_ID_REPLIES)
                if (tags != null) {
                    tags.remove(tag)
                }
            }
            if (tags != null && tags.isEmpty()) {
                notificationManager.cancel(C.NOTIFICATION_ID_REPLIES)
            }
        }

        companion object {
            private const val GROUP_REPLIES = "replies"

            private fun makeTag(
                chanName: String?,
                boardName: String?,
                threadNumber: String?,
                postNumber: PostNumber?,
            ): String? =
                formatHex(
                    getInstanceSha256().calculate(
                        chanName + "/" +
                            boardName + "/" + threadNumber + "/" + postNumber,
                    ),
                )

            private fun configureNotification(
                builder: NotificationCompat.Builder,
                color: Int,
            ) {
                builder.setSmallIcon(R.drawable.ic_notification)
                builder.setColor(color)
            }

            private fun buildLongComment(comment: String): String {
                val builder = StringBuilder()
                var nextNewLine = false
                for (line in comment
                    .split("\n".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()) {
                    var currentLine = line
                    if (!currentLine.isEmpty()) {
                        currentLine = currentLine.trim { it <= ' ' }
                        if (!currentLine.isEmpty()) {
                            currentLine = currentLine.replace(" {2,}".toRegex(), " ")
                            val newLine = nextNewLine
                            nextNewLine =
                                !currentLine.contains(">>") ||
                                !currentLine
                                    .replace(">>\\d+".toRegex(), "")
                                    .trim { it <= ' ' }
                                    .isEmpty()
                            if (builder.length > 0) {
                                builder.append(if (newLine) '\n' else ' ')
                            }
                            builder.append(currentLine)
                        }
                    }
                }
                return builder.toString()
            }
        }
    }
}
