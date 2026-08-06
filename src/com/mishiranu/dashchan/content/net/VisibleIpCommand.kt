package com.mishiranu.dashchan.content.net

import chan.content.Chan
import com.mishiranu.dashchan.content.CommandRunner
import com.mishiranu.dashchan.content.storage.CommandsStorage

/**
 * The command the app runs when a forum needs to be seen at another IP, and the two things the app
 * does around it.
 *
 * Changing that IP is a proxy service's business and every service goes about it differently --
 * one buys a port, one rewrites a session token in the credentials it already gave out, one has an
 * endpoint that answers to a request and moves the IP behind it. None of that is the app's to
 * know, so it is written as a [CommandsStorage.UseIn.APP] command flagged
 * [autoRun][CommandsStorage.CommandItem.autoRun], and the app's part is to find the one that belongs to
 * a forum and to run it at the moment it is wanted -- the button on the forum's own settings screen, the
 * one offered when a post comes back banned, or another command asking for it through
 * `chan.changeVisibleIp()` (see [com.mishiranu.dashchan.content.CommandApp]), which is the same run
 * waited for rather than watched.
 *
 * The user is who wires the two together, by scoping the command to their forums and flagging it. The
 * screens that run one name it, so which command the button will run is on the screen holding the
 * button rather than in a preference of its own.
 */
object VisibleIpCommand {
    /**
     * The command that changes the IP [chan] is seen at, or `null` when the user has written none
     * for it -- which is what a forum whose proxy is set by hand, or has none, looks like.
     */
    fun forChan(chan: Chan): CommandsStorage.CommandItem? = CommandsStorage.getInstance().getVisibleIpCommand(chan.name)

    /**
     * Runs [forChan]'s command for [chan], answering the run so the screen that started it can drop it,
     * or `null` when there is no command to run. Main thread, like every command run.
     *
     * A success drops the pooled connections whatever the script did: a service that moves the IP
     * behind an endpoint that stays put leaves nothing here to change, and a connection kept alive
     * through it would go on being seen at the old IP -- which is the one thing the caller cannot
     * be expected to know to do. A script that changed the proxy itself has had them dropped already
     * (see [ProxyConnection.set]); dropping them twice costs a pool that was empty anyway.
     *
     * The drop happens off this thread and [callback] waits for it (see
     * [ProxyConnection.dropPooledConnections]) -- a command's result is delivered on the main thread,
     * where closing a TLS connection is network I/O StrictMode kills the app for, and where the first
     * thing a caller does with the result is ask for the IP again.
     */
    fun run(
        chan: Chan,
        callback: (CommandRunner.AppResult) -> Unit,
    ): CommandRunner.Run? {
        val item = forChan(chan) ?: return null
        return run(item, chan.name, callback = callback)
    }

    /**
     * Runs [item] -- a command the caller has already picked out, rather than the one [forChan] finds --
     * with the same drop of the pooled connections after it. That is what a run by hand from a page's ⌘
     * menu is: the user asking for the IP change the app would otherwise have asked for itself, which is
     * only half done if the connections that were open at the old IP stay open.
     */
    fun run(
        item: CommandsStorage.CommandItem,
        chanName: String?,
        threadNumber: String? = null,
        boardName: String? = null,
        callback: (CommandRunner.AppResult) -> Unit,
    ): CommandRunner.Run =
        CommandRunner.runApp(item, chanName, threadNumber, boardName) { result ->
            if (result is CommandRunner.AppResult.Success) {
                ProxyConnection.dropPooledConnections { callback(result) }
            } else {
                callback(result)
            }
        }
}
