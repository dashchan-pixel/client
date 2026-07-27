package com.mishiranu.dashchan.content

import android.net.Uri
import chan.content.Chan
import chan.http.HttpHolder
import chan.http.HttpRequest
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.util.ConcurrentUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns the [libraries][CommandsStorage.LibraryItem] a command selected into the JavaScript that runs
 * ahead of its body (see [CommandRunner]).
 *
 * A [snippet][CommandsStorage.LibraryItem.Kind.SNIPPET] is used as it is stored. A
 * [URL][CommandsStorage.LibraryItem.Kind.URL] library is downloaded over HTTP and kept in memory for
 * the rest of the process, so a command that runs on every thread it opens doesn't refetch its
 * dependencies each time; the Libraries screen's refresh drops the cache ([clearCache]) when the
 * remote script has changed. Sources are concatenated in the order the Libraries screen lists them,
 * which is what lets one library build on another.
 *
 * The download needs a network round trip, so [load] is asynchronous — but it stays synchronous (the
 * callback runs before it returns) when nothing has to be fetched, which is every run of a
 * snippet-only command and every run after the first of a URL one.
 */
object CommandLibraries {
    /** The outcome of resolving a command's libraries. */
    sealed interface Result {
        /** [source] is the JavaScript to run before the command's body; empty when it uses none. */
        data class Success(
            val source: String,
        ) : Result

        /** A library is missing or could not be downloaded; [message] says which and why. */
        data class Failure(
            val message: String,
        ) : Result
    }

    // url -> downloaded source. Written from the loader thread, read from the main thread.
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Resolves [names] and delivers the result to [callback] on the main thread. Safe to call from the
     * main thread; nothing is downloaded on it.
     */
    fun load(
        names: Set<String>?,
        callback: (Result) -> Unit,
    ) {
        val storage = CommandsStorage.getInstance()
        val items = storage.librariesFor(names)
        if (items.isEmpty() && names.isNullOrEmpty()) {
            callback(Result.Success(""))
            return
        }
        // A command that names a library nobody defined would otherwise fail deep inside the script
        // with a "not defined" for whatever the library was supposed to declare.
        val missing = names.orEmpty().filter { name -> items.none { it.name == name } }
        if (missing.isNotEmpty()) {
            callback(Result.Failure("Library \"${missing.first()}\" is not defined"))
            return
        }
        // Taken out of the cache once, up front: the run then builds from these, and a refresh part
        // way through it can't leave a library out of the script it is already assembling.
        val downloaded = HashMap<String, String>()
        var needsDownload = false
        for (item in items) {
            if (item.kind == CommandsStorage.LibraryItem.Kind.URL) {
                val source = cache[item.content]
                if (source != null) {
                    downloaded[item.content] = source
                } else {
                    needsDownload = true
                }
            }
        }
        if (!needsDownload) {
            callback(Result.Success(build(items, downloaded)))
            return
        }
        ConcurrentUtils.PARALLEL_EXECUTOR.execute {
            var result: Result? = null
            for (item in items) {
                if (item.kind == CommandsStorage.LibraryItem.Kind.URL && !downloaded.containsKey(item.content)) {
                    try {
                        val source = download(item.content)
                        cache[item.content] = source
                        downloaded[item.content] = source
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Named, because the command names several libraries and the address alone
                        // doesn't say which of them the user has to go and fix.
                        result = Result.Failure("Library \"${item.name}\": ${e.message ?: e.javaClass.simpleName}")
                        break
                    }
                }
            }
            val outcome = result ?: Result.Success(build(items, downloaded))
            ConcurrentUtils.HANDLER.post { callback(outcome) }
        }
    }

    /** Forgets every downloaded script, so the next run fetches the current version again. */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Concatenates the sources. Each is introduced by a comment naming the library — the sources end
     * up inside one compiled function, so that comment is the only thing tying a line of it back to
     * the library it came from. A `;` closes each one off, so a library that ends in an expression
     * without a semicolon can't swallow the start of the next.
     */
    private fun build(
        items: List<CommandsStorage.LibraryItem>,
        downloaded: Map<String, String>,
    ): String =
        buildString {
            for (item in items) {
                val source =
                    when (item.kind) {
                        CommandsStorage.LibraryItem.Kind.SNIPPET -> item.content

                        // load() only builds once every URL library has been downloaded.
                        CommandsStorage.LibraryItem.Kind.URL -> downloaded[item.content].orEmpty()
                    }
                append("// library: ").append(item.name.replace('\n', ' ')).append('\n')
                append(source).append("\n;\n")
            }
        }

    /**
     * Downloads a library's script. Runs on the loader thread. Anything other than HTTP is refused
     * rather than guessed at: the address is meant to point at a script on the web, and a scheme the
     * app would have to hold a permission for (a document the user picked, say) wouldn't survive being
     * read again days later anyway.
     */
    private fun download(url: String): String {
        val chan = Chan.getFallback()
        val uri = chan.locator.setSchemeIfEmpty(Uri.parse(url), null) ?: Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Library address is not HTTP: $url" }
        val holder = HttpHolder(chan)
        return holder.use().use {
            HttpRequest(uri, holder).perform()?.readString() ?: error("Empty library response: $url")
        }
    }
}
