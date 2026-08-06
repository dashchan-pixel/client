package com.mishiranu.dashchan.content

import chan.content.ApiException
import chan.content.Chan
import chan.http.HttpHolder
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.net.VisibleIp
import com.mishiranu.dashchan.util.ConcurrentUtils

/**
 * The bans the forums handed out, as caught while posting: the extensions parse them out of the
 * failed response, this keeps them so their expiration can be tracked afterwards. Shown per forum
 * by [com.mishiranu.dashchan.ui.preference.BanLogFragment].
 *
 * Bans are assigned to an IP, so the row also records the one the forum saw at the time --
 * resolved off the posting thread, since the post has already failed and nothing waits for it.
 */
object BanLog {
    fun record(
        chan: Chan,
        boardName: String?,
        threadNumber: String?,
        extra: ApiException.Extra?,
    ) {
        val chanName = chan.name ?: return
        val banExtra = extra as? ApiException.BanExtra
        val rowId =
            ChanDatabase.getInstance().addBan(
                chanName,
                boardName,
                threadNumber,
                banExtra?.id,
                banExtra?.message,
                banExtra?.startDate ?: 0L,
                banExtra?.expireDate ?: 0L,
            )
        if (rowId >= 0) {
            resolveIp(chan, rowId)
        }
    }

    private fun resolveIp(
        chan: Chan,
        rowId: Long,
    ) {
        ConcurrentUtils.PARALLEL_EXECUTOR.execute {
            val holder = HttpHolder(chan)
            val ip = holder.use().use { VisibleIp.resolve(chan, holder)?.ip }
            if (ip != null) {
                ChanDatabase.getInstance().setBanAddress(rowId, ip)
            }
        }
    }
}
