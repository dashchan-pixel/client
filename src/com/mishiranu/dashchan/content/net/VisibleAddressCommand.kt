package com.mishiranu.dashchan.content.net

import chan.content.Chan
import chan.http.HttpClient
import com.mishiranu.dashchan.content.CommandRunner
import com.mishiranu.dashchan.content.storage.CommandsStorage

/**
 * The command the app runs when a forum needs to be seen at another address, and the two things the app
 * does around it.
 *
 * Changing that address is a proxy service's business and every service goes about it differently --
 * one buys a port, one rewrites a session token in the credentials it already gave out, one has an
 * endpoint that answers to a request and moves the address behind it. None of that is the app's to
 * know, so it is written as a [CommandsStorage.UseIn.APP] command flagged
 * [autoRun][CommandsStorage.CommandItem.autoRun], and the app's part is to find the one that belongs to
 * a forum and to run it at the moment it is wanted -- the button on the forum's own settings screen, or
 * the one offered when a post comes back banned.
 *
 * The user is who wires the two together, by scoping the command to their forums and flagging it. The
 * screens that run one name it, so which command the button will run is on the screen holding the
 * button rather than in a preference of its own.
 */
object VisibleAddressCommand {
    /**
     * The command that changes the address [chan] is seen at, or `null` when the user has written none
     * for it -- which is what a forum whose proxy is set by hand, or has none, looks like.
     */
    fun forChan(chan: Chan): CommandsStorage.CommandItem? = CommandsStorage.getInstance().getAddressCommand(chan.name)

    /**
     * Runs [forChan]'s command for [chan], answering the run so the screen that started it can drop it,
     * or `null` when there is no command to run. Main thread, like every command run.
     *
     * A success drops the pooled connections whatever the script did: a service that moves the address
     * behind an endpoint that stays put leaves nothing here to change, and a connection kept alive
     * through it would go on being seen at the old address -- which is the one thing the caller cannot
     * be expected to know to do. A script that changed the proxy itself has had them dropped already
     * (see [ProxyConnection.set]); dropping them twice costs a pool that was empty anyway.
     */
    fun run(
        chan: Chan,
        callback: (CommandRunner.AppResult) -> Unit,
    ): CommandRunner.Run? {
        val item = forChan(chan) ?: return null
        return CommandRunner.runApp(item, chan.name) { result ->
            if (result is CommandRunner.AppResult.Success) {
                HttpClient.getInstance().dropCachedConnections()
            }
            callback(result)
        }
    }
}
