package com.mishiranu.dashchan.content

import android.webkit.JavascriptInterface
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.content.storage.DraftsStorage.AttachmentDraft
import com.mishiranu.dashchan.util.ConcurrentUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a user-defined [CommandsStorage.CommandItem] through the [HeadlessJsEngine].
 *
 * The command's [code][CommandsStorage.CommandItem.code] is treated as the body of an **async**
 * JavaScript function whose arguments and expected return value depend on the command's target:
 *
 * - [CommandsStorage.UseIn.COMMENT] (see [run]): `comment`, `attachments`, `thread`, `board`, `env`,
 *   `store`, `app`, `chan`, `dialog`. It should `return` the replacement comment; returning `undefined`/`null`
 *   leaves the draft untouched. To manage the attached files too it may instead return an object, whose `comment` and
 *   `attachments` are each applied only if present — so `return { attachments: [] }` detaches every
 *   file and leaves the text alone. See [CommandAttachments] for what an attachment looks like and
 *   for how a script adds one.
 * - [CommandsStorage.UseIn.THREAD] (see [runThread]): `posts`, `thread`, `board`, `env`, `store`,
 *   `app`, `chan`, `dialog`. `posts` is an array of `{number, name, email, icon, subject, comment, attachments}`, `comment` being the post's
 *   HTML as the chan sent it and `attachments` its attached files (see [CommandPostAttachments]). It
 *   should `return` an object mapping a post's `number` to what to show in place of that post's own —
 *   either the replacement comment HTML (e.g. `{ "123": "<b>decrypted…</b>" }`) or an object whose
 *   `comment` and `attachments` are each applied only if present, so `{ "123": { attachments: [] } }`
 *   hides that post's files and leaves its text alone. Posts absent from the result are left as-is, and
 *   returning `undefined`/`null` changes nothing.
 *
 *   In and out are the same form on purpose: a replacement is parsed exactly like a real comment, so
 *   greentext, spoilers and `>>` links written the way the chan writes them keep working, and a script
 *   that edits only the text it cares about can hand the rest of the post's HTML straight back. The
 *   flip side is that a replacement is HTML, so plain text carrying `<` or `&` has to be escaped.
 *
 *   With [perPost][CommandsStorage.CommandItem.perPost] set the body instead runs once per post — it
 *   receives a single `post` in place of `posts` and returns just that post's replacement (`undefined`/
 *   `null` leaves the post alone), the fold into the result map being supplied here. It runs the body
 *   n times instead of once, sequentially, in exchange for a body that only has to think about one
 *   post. Such a command may also be run over a single post, from that post's context menu — the same
 *   call with a `posts` of one.
 *
 * - [CommandsStorage.UseIn.APP] (see [runApp]): `thread`, `board`, `env`, `store`, `app`, `chan`,
 *   `dialog`, and no input of its own. What it returns is shown to the user as a message rather than
 *   applied to anything; what such a command *does* it does through the objects it was granted — writing
 *   a setting, storing a cookie, giving a forum another proxy.
 *
 * `thread` is the thread number and `board` the board code — plain strings, either `null` when there
 * is no such context. Being async, a body may `await` (e.g.
 * `return await fetch(url).then(r => r.text())`).
 *
 * `store` is the command's own key→value store, `app` the application's settings and `chan` the forum
 * the run belongs to along with that forum's cookies and the IP it sees this device at — so
 * `chan.cookies` and `chan.getVisibleIp()` need no argument to work on the right one. `dialog` puts a
 * question to the user and hands back their answer, the run standing still until it has one. `app` and
 * `chan` answer only to a command the user granted them to; `store` and `dialog` answer to every
 * command. See [CommandApp] and [CommandDialogs] for the whole of it. The runtime's own `alert`,
 * `confirm` and `prompt` are bound to `dialog` here as well, along with `choose` and `chooseMany`, so
 * the names a script reaches for first do what they say rather than being cancelled unanswered.
 *
 * Because it runs on the [HeadlessJsEngine] the code may use `fetch`/`XMLHttpRequest` to reach the
 * network (CORS is disabled there) and the WebCrypto API (`crypto.subtle`, the origin is a secure
 * context), so scripts can encrypt/decrypt or translate posts. A fresh engine is created per run and
 * destroyed once the result arrives — or once the run is given up on, see [Run].
 *
 * The code also receives an `env` object holding the user's shared key→value store
 * ([CommandsStorage.getEnv]), so a script can read a user-provided value with
 * `env.CUSTOM_NAME_HERE`. It is a read-only snapshot taken at run time — assigning to it does not
 * persist; the store is edited from the Commands screen. A command the user took the
 * [environment grant][CommandsStorage.Grant.ENVIRONMENT] away from is handed an empty `env` instead:
 * the object stays, so a body that reads `env.NAME` sees `undefined` rather than throwing on a name
 * that isn't there.
 *
 * A command may also load [libraries][CommandsStorage.LibraryItem] — shared snippets, or scripts
 * downloaded from an address (see [CommandLibraries]). Their sources run once, in an enclosing scope,
 * before the body is created, so whatever they declare at their top level the body can use directly
 * (`const` and `let` included, which is why this is a scope around the body rather than an eval into
 * the global one). They see `thread`, `board` and `env` too. A library that fails to download, or a
 * name with no library behind it, fails the run before the body is reached.
 */
object CommandRunner {
    /**
     * One post handed to a [CommandsStorage.UseIn.THREAD] script as an element of `posts`. [comment] is
     * the post's HTML, not the rendered text — see
     * [Post.comment][chan.content.model.Post.getComment]. [attachments] are the post's attached files,
     * which the script may hand back changed (see [CommandPostAttachments]).
     */
    data class ThreadPost(
        val number: String,
        val name: String?,
        val email: String?,
        val icon: String?,
        val subject: String?,
        val comment: String,
        val attachments: List<Post.Attachment.File>,
    )

    /** Outcome of a [CommandsStorage.UseIn.COMMENT] command. */
    sealed interface Result {
        /**
         * The command ran successfully. [comment] is the replacement text and [attachments] the
         * replacement attachment list; either is `null` when the script asked for no change to that
         * half of the draft, which for both is what returning nothing means.
         */
        data class Success(
            val comment: String?,
            val attachments: List<AttachmentDraft>?,
        ) : Result

        /** The command threw or could not be evaluated; [message] describes what went wrong. */
        data class Failure(
            val message: String,
        ) : Result
    }

    /**
     * What the script returned, before the attachments it asked for have been resolved (see
     * [CommandAttachments.resolve] — a new file may still have to be downloaded). Kept apart from
     * [Result] because that resolution is itself asynchronous, so it can't happen where the bridged
     * JSON is parsed.
     */
    private sealed interface RawResult {
        data class Success(
            val comment: String?,
            val attachments: List<CommandAttachments.Requested>?,
        ) : RawResult

        data class Failure(
            val message: String,
        ) : RawResult
    }

    /**
     * A run in flight, handed back by [run] and [runThread] so the screen that started one can give up
     * on it — the result of a command whose screen is gone is dropped by the caller anyway, and the
     * engine would otherwise be held until the deadline (minutes, for a per-post run over a long
     * thread) still reaching the network.
     *
     * A run settles exactly once: through its result, through its deadline, or through [cancel]. The
     * first of those to arrive owns the outcome and the teardown.
     */
    class Run internal constructor() {
        private val settled = AtomicBoolean(false)

        // Written on the main thread when the engine starts, read from the JS bridge thread.
        @Volatile private var engine: HeadlessJsEngine? = null

        @Volatile private var timeout: Runnable? = null

        @Volatile private var timeoutMs: Long = TIMEOUT_MS

        /** The thread waiting on the user, while one is (see [awaitUser]). */
        @Volatile private var waiting: Thread? = null

        /** Whether the outcome has been decided, so there is nothing left to cancel. */
        val isFinished: Boolean
            get() = settled.get()

        /**
         * Waits on the user for [action] — a dialog the script put up (see [CommandDialogs]) — with the
         * deadline widened to [USER_TIMEOUT_MS] for as long as it takes, and the run's own deadline
         * armed again in full once the answer is in. The deadline is there for a script that never
         * settles, and one the user hasn't got round to answering is not that; it is widened rather
         * than lifted so a dialog nobody ever answers still ends the run, and with it the engine.
         *
         * The waiting thread is remembered so [release] can interrupt it: a run given up on while a
         * dialog is up would otherwise leave the dialog standing there with nothing behind it, and the
         * thread waiting on an answer that is now nobody's. The interrupt is what
         * [ForegroundManager][com.mishiranu.dashchan.ui.ForegroundManager] takes the dialog down on.
         *
         * A run that is already over puts nothing up at all — the thread is registered before that is
         * checked, so a [release] on the other side of the check interrupts this one instead of being
         * missed by it, and either way no dialog outlives the run that asked for it.
         *
         * Called from the JS bridge thread, which is where a bridge call arrives.
         */
        internal fun <T> awaitUser(action: () -> T): T {
            waiting = Thread.currentThread()
            try {
                check(!isFinished) { "The command is no longer running" }
                arm(USER_TIMEOUT_MS)
                return action()
            } finally {
                waiting = null
                arm(timeoutMs)
            }
        }

        /** Moves the deadline [delay] out from now, unless the run has already settled. */
        private fun arm(delay: Long) {
            val timeout = this.timeout ?: return
            ConcurrentUtils.HANDLER.removeCallbacks(timeout)
            if (!isFinished) {
                ConcurrentUtils.HANDLER.postDelayed(timeout, delay)
            }
        }

        /**
         * Gives up on the run: the engine is destroyed now and the callback is never invoked. A run
         * that has already settled is left alone, so this is safe to call for anything still tracked.
         * Main thread.
         */
        fun cancel() {
            if (settle()) {
                release()
            }
        }

        /** Hands over what [cancel] has to tear down, once the run actually owns an engine. */
        internal fun start(
            engine: HeadlessJsEngine,
            timeout: Runnable,
            timeoutMs: Long,
        ) {
            this.engine = engine
            this.timeout = timeout
            this.timeoutMs = timeoutMs
        }

        /** Claims the single outcome this run is allowed; true for the caller that got it. */
        internal fun settle(): Boolean = settled.compareAndSet(false, true)

        /**
         * Drops the deadline, the dialog and the engine. Callable from either thread —
         * [HeadlessJsEngine.destroy] marshals itself onto the main one, and
         * [Handler.removeCallbacks][android.os.Handler.removeCallbacks] is safe from any.
         */
        internal fun release() {
            timeout?.let { ConcurrentUtils.HANDLER.removeCallbacks(it) }
            timeout = null
            // A script waiting on a dialog is waiting for a run that is over: the interrupt is what
            // takes the dialog off the screen and gives the thread back (see awaitUser).
            waiting?.interrupt()
            waiting = null
            engine?.destroy()
            engine = null
        }
    }

    /**
     * What a thread command asked to show in place of one post's own. [comment] is the replacement HTML
     * and [attachments] the replacement file list; either is `null` when the script asked for no change
     * to that half of the post.
     */
    data class ThreadChange(
        val comment: String?,
        val attachments: List<Post.Attachment.File>?,
    )

    /** Outcome of a [CommandsStorage.UseIn.THREAD] command. */
    sealed interface ThreadResult {
        /**
         * The command ran successfully. [changes] maps a post number to what to display in place of
         * that post's own comment and files; it is empty when the script asked for no changes.
         */
        data class Success(
            val changes: Map<String, ThreadChange>,
        ) : ThreadResult

        /** The command threw or could not be evaluated; [message] describes what went wrong. */
        data class Failure(
            val message: String,
        ) : ThreadResult
    }

    /** Outcome of a [CommandsStorage.UseIn.APP] command. */
    sealed interface AppResult {
        /**
         * The command ran successfully. [message] is what it returned, for showing back to the user, or
         * `null` when it returned nothing — a command that acts through the objects it was granted has
         * nothing it has to say.
         */
        data class Success(
            val message: String?,
        ) : AppResult

        /** The command threw or could not be evaluated; [message] describes what went wrong. */
        data class Failure(
            val message: String,
        ) : AppResult
    }

    /**
     * Evaluates a [CommandsStorage.UseIn.APP] [item] — one that is handed no input and replaces nothing
     * — and delivers the outcome to [callback] on the main thread. Safe to call from the main thread.
     * The returned [Run] lets the caller drop the run when the screen it belongs to goes away.
     *
     * [chanName], [thread] and [board] are the context the run belongs to, as far as the caller has one:
     * [chanName] is what the script's `chan` works on without naming a forum, so a command run for a
     * forum gets it and one run from a screen that is about no forum passes `null` and leaves the script
     * to name the forum it means.
     */
    fun runApp(
        item: CommandsStorage.CommandItem,
        chanName: String?,
        thread: String? = null,
        board: String? = null,
        callback: (AppResult) -> Unit,
    ): Run {
        val handle = Run()
        withLibraries(item, handle, { AppResult.Failure(it) }, callback) { libraries ->
            val script =
                buildScript(
                    factorySource = buildFactorySource(BODY_PARAMS, item.code.orEmpty(), libraries, false),
                    thread = thread,
                    board = board,
                    env = environmentFor(item),
                    resultExpr = APP_RESULT_EXPR,
                ) { }
            execute(handle, chanName, item.grants, script, ::parseApp, { AppResult.Failure(it) }, callback)
        }
        return handle
    }

    /**
     * Evaluates a [CommandsStorage.UseIn.COMMENT] [item] against the given inputs and delivers the
     * outcome to [callback] on the main thread. Safe to call from the main thread. The returned [Run]
     * lets the caller drop the run when the screen it belongs to goes away.
     */
    fun run(
        item: CommandsStorage.CommandItem,
        comment: String,
        attachments: List<AttachmentDraft>,
        chanName: String?,
        thread: String?,
        board: String?,
        callback: (Result) -> Unit,
    ): Run {
        val handle = Run()
        withLibraries(item, handle, { Result.Failure(it) }, callback) { libraries ->
            val attachmentsJson = CommandAttachments.toJson(attachments)
            val script =
                buildScript(
                    factorySource =
                        buildFactorySource(
                            "comment,attachments,$BODY_PARAMS",
                            item.code.orEmpty(),
                            libraries,
                            false,
                        ),
                    thread = thread,
                    board = board,
                    env = environmentFor(item),
                    resultExpr = COMMENT_RESULT_EXPR,
                ) {
                    append(jsArg(comment)).append(',')
                    append(attachmentsJson).append(',')
                }
            execute(handle, chanName, item.grants, script, ::parseComment, { RawResult.Failure(it) }, callback = { raw ->
                when (raw) {
                    is RawResult.Failure -> {
                        callback(Result.Failure(raw.message))
                    }

                    is RawResult.Success -> {
                        val requested = raw.attachments
                        if (requested == null) {
                            callback(Result.Success(raw.comment, null))
                        } else {
                            // The run has already settled by now, so a cancel can no longer stop this
                            // — a screen that went away drops the result instead. It is the price of
                            // resolving after the engine is gone, and all it costs is one download.
                            CommandAttachments.resolve(attachments, requested) { resolved ->
                                callback(
                                    when (resolved) {
                                        is CommandAttachments.Result.Success -> {
                                            Result.Success(raw.comment, resolved.attachments)
                                        }

                                        is CommandAttachments.Result.Failure -> {
                                            Result.Failure(resolved.message)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            })
        }
        return handle
    }

    /**
     * Evaluates a [CommandsStorage.UseIn.THREAD] [item] against the thread's [posts] and delivers the
     * outcome to [callback] on the main thread. Safe to call from the main thread. The returned [Run]
     * lets the caller drop the run when the screen it belongs to goes away.
     */
    fun runThread(
        item: CommandsStorage.CommandItem,
        posts: List<ThreadPost>,
        chanName: String?,
        thread: String?,
        board: String?,
        callback: (ThreadResult) -> Unit,
    ): Run {
        val handle = Run()
        withLibraries(item, handle, { ThreadResult.Failure(it) }, callback) { libraries ->
            val postsJson = postsToJson(posts)
            val bodyParams = if (item.perPost) "post,$BODY_PARAMS" else "posts,$BODY_PARAMS"
            val script =
                buildScript(
                    factorySource = buildFactorySource(bodyParams, item.code.orEmpty(), libraries, item.perPost),
                    thread = thread,
                    board = board,
                    env = environmentFor(item),
                    resultExpr = THREAD_RESULT_EXPR,
                ) { append(postsJson).append(',') }
            // A per-post body runs n times, so one deadline for the whole thread would fail a body that
            // is fine per post (a fetch each, say) purely for the thread being long. Give it a share per
            // post, capped so a runaway script still can't hold an engine forever.
            val timeout =
                if (item.perPost) {
                    (TIMEOUT_MS + PER_POST_TIMEOUT_MS * posts.size).coerceAtMost(MAX_PER_POST_TIMEOUT_MS)
                } else {
                    TIMEOUT_MS
                }
            // The attachments a script asked for are resolved against the posts it was handed, right
            // where the bridged JSON is parsed: unlike a draft's, a post's attachment is an address
            // rather than bytes, so there is nothing to go and fetch first.
            execute(
                handle,
                chanName,
                item.grants,
                script,
                { raw -> parseThread(raw, posts) },
                { ThreadResult.Failure(it) },
                callback,
                timeout,
            )
        }
        return handle
    }

    /**
     * Resolves [item]'s libraries and hands their source to [proceed], or reports the failure through
     * [callback] — the run never starts with a library missing, since the body would fail on it in a
     * way that points at the wrong thing. [failure] wraps the message in the result type the caller
     * expects. Everything happens on the main thread; only the download itself doesn't (see
     * [CommandLibraries.load]).
     *
     * [handle] is settled by a failure the same way a run is, so a cancel and a missing library can't
     * both be reported.
     */
    private fun <R> withLibraries(
        item: CommandsStorage.CommandItem,
        handle: Run,
        failure: (String) -> R,
        callback: (R) -> Unit,
        proceed: (String) -> Unit,
    ) {
        CommandLibraries.load(item.libraries) { result ->
            when (result) {
                // Cancelled while a library was still downloading: no engine exists yet, so giving up
                // here is the whole of it.
                is CommandLibraries.Result.Success -> if (!handle.isFinished) proceed(result.source)

                is CommandLibraries.Result.Failure -> if (handle.settle()) callback(failure(result.message))
            }
        }
    }

    /**
     * Runs [script] on a fresh [HeadlessJsEngine] and routes the outcome to [callback] on the main
     * thread. The script runs the user code as an *async* function, so a returned Promise (e.g. `await
     * fetch(...)`) is honoured. evaluateJavascript can't await Promises, so the result comes back
     * through a bridge instead of the evaluation's return value; a [timeoutMs] deadline guards a script
     * that never settles. [parse] turns the bridged JSON into a result; [failure] wraps a timeout/error
     * message.
     *
     * The result arrives on the bridge's own thread and the deadline on the main one, so which of them
     * gets to answer — and to tear the engine down — is decided by [handle], which a [Run.cancel] may
     * also have taken first.
     *
     * [chanName] is the forum the run belongs to; it is what the script's `chan` works on by default
     * (see [CommandApp]), and `null` where the command was started outside a forum. [grants] is what
     * the command may reach through the bridge, which also gets [handle] itself — a dialog the script
     * puts up holds the run it belongs to (see [CommandDialogs]).
     */
    private fun <R> execute(
        handle: Run,
        chanName: String?,
        grants: Set<CommandsStorage.Grant>,
        script: String,
        parse: (String?) -> R,
        failure: (String) -> R,
        callback: (R) -> Unit,
        timeoutMs: Long = TIMEOUT_MS,
    ) {
        val appBridge = CommandApp.bridge(chanName, grants, handle)
        val deliver: (R) -> Unit = { result ->
            if (handle.settle()) {
                ConcurrentUtils.HANDLER.post {
                    handle.release()
                    callback(result)
                    // Last, so a setting that needs the screen rebuilt is applied only once what the
                    // command produced has been put where it belongs.
                    appBridge.applyIfNeeded()
                }
            }
        }
        val timeout = Runnable { deliver(failure("Command timed out")) }
        val bridge =
            object {
                @JavascriptInterface
                fun onResult(json: String?) {
                    deliver(parse(json))
                }
            }
        val engine =
            HeadlessJsEngine(
                mapOf(
                    BRIDGE_NAME to bridge,
                    CommandApp.BRIDGE_NAME to appBridge,
                ),
            )
        handle.start(engine, timeout, timeoutMs)
        ConcurrentUtils.HANDLER.postDelayed(timeout, timeoutMs)
        engine.evaluate(script)
    }

    /**
     * Builds the runnable script around [factorySource] (see [buildFactorySource]). [leadingArg]
     * appends the matching first actual argument(s) of the command (already followed by a comma),
     * after which `thread`, `board`, the frozen `env` object and the `app` object are passed.
     * [resultExpr] is the JS expression that maps the resolved `__result` to the value delivered back
     * through the bridge.
     */
    private fun buildScript(
        factorySource: String,
        thread: String?,
        board: String?,
        env: Map<String, String>,
        resultExpr: String,
        leadingArg: StringBuilder.() -> Unit,
    ): String =
        buildString {
            append("(function(){")
            append("var __deliver=function(o){try{window.").append(BRIDGE_NAME)
            append(".onResult(JSON.stringify(o));}catch(__ignored){}};")
            append("try{")
            // Compile through the AsyncFunction constructor so the user code may `await` — e.g.
            // `return await fetch(url).then(r => r.text())`. Building it here rather than inlining also
            // means a *syntax* error throws at construction and is caught, surfacing a real message
            // instead of a null result.
            append("var __AsyncFunction=Object.getPrototypeOf(async function(){}).constructor;")
            append("var __thread=").append(jsArg(thread)).append(';')
            append("var __board=").append(jsArg(board)).append(';')
            // The shared env store, injected as a plain object so scripts read `env.NAME`. Frozen so
            // a stray `env.X = …` is dropped (throwing under "use strict") rather than mutating a
            // value that would never be persisted — this is a per-run snapshot. Empty for a command
            // the environment grant was taken away from (see environmentFor).
            append("var __env=Object.freeze(").append(JSONObject(env).toString()).append(");")
            // The store, the settings and the forum, built once here and shared by the libraries and
            // the body — including a per-post body, which is called n times.
            append("var __bridged=").append(CommandApp.SOURCE).append(";")
            append("var __store=__bridged.store;")
            append("var __app=__bridged.app;")
            append("var __chan=__bridged.chan;")
            append("var __dialog=__bridged.dialog;")
            // The three the runtime already has, made to mean what they say. Unimplemented they are
            // worse than absent -- a WebView with nothing handling them cancels every one, so confirm()
            // answers "no" without having asked -- and they are what a script reaches for first. alert
            // is wrapped to answer undefined the way the real one does, and prompt because its second
            // argument is the starting value rather than an options object; the pickers have no
            // standard names to keep, so they carry the ones dialog gives them.
            append("window.alert=function(message){__dialog.show(message);};")
            append("window.confirm=__dialog.confirm;")
            append("window.prompt=function(message,value){return __dialog.prompt(message,{value:value});};")
            append("window.choose=__dialog.choose;")
            append("window.chooseMany=__dialog.chooseMany;")
            // What is compiled is a factory that runs the libraries and returns the command, rather
            // than the command itself: the libraries then run once (even for a per-post body, which is
            // called n times) and their declarations are simply in scope for it.
            append("var __factory=new __AsyncFunction(")
            append("\"thread\",\"board\",\"env\",\"store\",\"app\",\"chan\",\"dialog\",")
            append(JSONObject.quote(factorySource))
            append(");")
            append("Promise.resolve(__factory(__thread,__board,__env,__store,__app,__chan,__dialog))")
            append(".then(function(__command){")
            append("return __command(")
            leadingArg()
            append("__thread,__board,__env,__store,__app,__chan,__dialog);")
            append("}).then(function(__result){")
            append("__deliver({ok:true,result:").append(resultExpr).append("});")
            append("}).catch(function(__err){")
            append("__deliver({ok:false,error:(__err&&__err.message)?String(__err.message):String(__err)});")
            append("});")
            append("}catch(__err){__deliver({ok:false,error:(__err&&__err.message)?String(__err.message):String(__err)});}")
            append("})();")
        }

    /**
     * The body of the factory: the library sources, then the command they are there for. [bodyParams]
     * is the comma-separated parameter list the user's body is compiled against. With [perPost] the
     * body becomes a single-post `process` and the fold over `posts` is supplied here, so what the run
     * calls is the wrapper rather than the user's body.
     *
     * Written on its own lines rather than packed onto one, because a `//` comment or a line the user
     * ended without a semicolon has to stay where it is.
     */
    private fun buildFactorySource(
        bodyParams: String,
        code: String,
        libraries: String,
        perPost: Boolean,
    ): String =
        buildString {
            append(libraries)
            if (perPost) {
                // The body is the `process` of the fold, so it is compiled against one `post` and the
                // loop that collects `{ number: replacement }` lives here. Awaited one post at a time:
                // a body that hits the network would otherwise fire the whole thread at once.
                append("var __process=async function(").append(bodyParams).append("){\n")
                append(code)
                append("\n};\n")
                append("return (async function(posts,$BODY_PARAMS){\n")
                append("var __acc={};\n")
                append("for(var __i=0;__i<posts.length;__i++){\n")
                append("var __post=posts[__i];\n")
                append("var __value=await __process(__post,$BODY_PARAMS);\n")
                // Same contract as the whole-thread form, one post at a time: nothing returned means
                // this post is left as it is, and what is returned may be either the comment or the
                // {comment?, attachments?} object — the normalizing is the same for both forms.
                append("if(__value!==undefined&&__value!==null){__acc[__post.number]=__value;}\n")
                append("}\n")
                append("return __acc;\n")
                append("});")
            } else {
                append("return (async function(").append(bodyParams).append("){\n")
                append(code)
                append("\n});")
            }
        }

    private fun postsToJson(posts: List<ThreadPost>): String {
        val array = JSONArray()
        for (post in posts) {
            val obj = JSONObject()
            obj.put("number", post.number)
            obj.put("name", post.name ?: JSONObject.NULL)
            obj.put("email", post.email ?: JSONObject.NULL)
            obj.put("icon", post.icon ?: JSONObject.NULL)
            obj.put("subject", post.subject ?: JSONObject.NULL)
            obj.put("comment", post.comment)
            obj.put("attachments", CommandPostAttachments.toJson(post.attachments))
            array.put(obj)
        }
        return array.toString()
    }

    private fun jsArg(value: String?): String = if (value == null) "null" else JSONObject.quote(value)

    /**
     * The environment [item] runs against: the shared one, or nothing at all when the user took the
     * grant away. Empty rather than absent, so a body written against `env.NAME` reads `undefined`
     * instead of throwing on an object that isn't there.
     */
    private fun environmentFor(item: CommandsStorage.CommandItem): Map<String, String> =
        if (CommandsStorage.Grant.ENVIRONMENT in item.grants) {
            CommandsStorage.getInstance().getEnv()
        } else {
            emptyMap()
        }

    /**
     * The tail every body is compiled against, whatever it takes first: the context of the run, then
     * the objects [CommandApp] provides.
     */
    private const val BODY_PARAMS = "thread,board,env,store,app,chan,dialog"

    private const val BRIDGE_NAME = "__commandBridge"
    private const val TIMEOUT_MS = 30000L

    /**
     * The deadline a run keeps while a dialog of its own is on the screen (see [Run.awaitUser]) —
     * long enough that a question is answered by the user rather than by the clock, short enough that
     * a dialog left standing still ends the run instead of holding an engine for good.
     */
    private const val USER_TIMEOUT_MS = 300000L

    /** Extra deadline a per-post run gets for each post it has to walk. See [runThread]. */
    private const val PER_POST_TIMEOUT_MS = 2000L
    private const val MAX_PER_POST_TIMEOUT_MS = 300000L

    /**
     * Normalizes what a Draft command returned into `{comment?, attachments?}`, or `null` for "change
     * nothing". A bare string stays the shorthand it has always been — it is the whole of the older
     * contract, and every command written against it goes on meaning the same thing — while an object
     * says which halves of the draft it wants replaced by which keys it carries.
     */
    private const val COMMENT_RESULT_EXPR =
        "(function(__r){" +
            "if(__r===undefined||__r===null){return null;}" +
            "if(typeof __r!=='object'){return {comment:String(__r)};}" +
            "var __o={};" +
            "if(__r.comment!==undefined&&__r.comment!==null){__o.comment=String(__r.comment);}" +
            "if(Array.isArray(__r.attachments)){__o.attachments=__r.attachments;}" +
            "return __o;" +
            "})(__result)"

    /**
     * What an App command returned, as the message to show for it: anything but nothing at all becomes
     * text, since it is going to be read rather than applied — a number or an object a script hands back
     * is still something it meant to say.
     */
    private const val APP_RESULT_EXPR = "((__result===undefined||__result===null)?null:String(__result))"

    private fun parseApp(raw: String?): AppResult {
        if (raw == null) {
            return AppResult.Failure("No result (script did not evaluate)")
        }
        return try {
            val json = JSONObject(raw)
            if (json.optBoolean("ok")) {
                AppResult.Success(if (json.isNull("result")) null else json.optString("result"))
            } else {
                AppResult.Failure(json.optString("error", "Unknown error"))
            }
        } catch (e: Exception) {
            AppResult.Failure(e.message ?: raw)
        }
    }

    private fun parseComment(raw: String?): RawResult {
        if (raw == null) {
            return RawResult.Failure("No result (script did not evaluate)")
        }
        return try {
            val json = JSONObject(raw)
            if (json.optBoolean("ok")) {
                val result = json.optJSONObject("result")
                val attachments = result?.optJSONArray("attachments")
                RawResult.Success(
                    if (result == null || result.isNull("comment")) null else result.optString("comment"),
                    if (attachments == null) null else CommandAttachments.parse(attachments),
                )
            } else {
                RawResult.Failure(json.optString("error", "Unknown error"))
            }
        } catch (e: Exception) {
            RawResult.Failure(e.message ?: raw)
        }
    }

    /**
     * Normalizes what a thread command returned into `{postNumber: {comment?, attachments?}}`. A post
     * whose value is a bare string keeps the shorthand it has always been — it is the whole of the older
     * contract, and every command written against it goes on meaning the same thing — while an object
     * says which halves of the post it wants replaced by which keys it carries.
     */
    private const val THREAD_RESULT_EXPR =
        "(function(__r){" +
            "if(__r===undefined||__r===null||typeof __r!=='object'){return {};}" +
            "var __o={};" +
            "for(var __k in __r){" +
            "if(!Object.prototype.hasOwnProperty.call(__r,__k)){continue;}" +
            "var __v=__r[__k];" +
            "if(__v===undefined||__v===null){continue;}" +
            "if(typeof __v!=='object'){__o[__k]={comment:String(__v)};continue;}" +
            "var __e={};" +
            "if(__v.comment!==undefined&&__v.comment!==null){__e.comment=String(__v.comment);}" +
            "if(Array.isArray(__v.attachments)){__e.attachments=__v.attachments;}" +
            "__o[__k]=__e;" +
            "}" +
            "return __o;" +
            "})(__result)"

    /**
     * Turns the bridged result into the change per post, resolving the attachments each one asked for
     * against the [posts] the script was handed. A reference that resolves to no file fails the whole
     * run rather than the one post: a half-applied thread is harder to make sense of than a message.
     */
    private fun parseThread(
        raw: String?,
        posts: List<ThreadPost>,
    ): ThreadResult {
        if (raw == null) {
            return ThreadResult.Failure("No result (script did not evaluate)")
        }
        return try {
            val json = JSONObject(raw)
            if (json.optBoolean("ok")) {
                val changes = LinkedHashMap<String, ThreadChange>()
                // A non-object return (string, array, …) yields no object here and thus no changes.
                val result = json.optJSONObject("result")
                if (result != null) {
                    val keys = result.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = result.optJSONObject(key) ?: continue
                        val attachments = value.optJSONArray("attachments")
                        val resolved =
                            if (attachments != null) {
                                val input = posts.firstOrNull { it.number == key }?.attachments.orEmpty()
                                val requested = CommandPostAttachments.parse(attachments)
                                when (val resolution = CommandPostAttachments.resolve(input, requested)) {
                                    is CommandPostAttachments.Result.Success -> {
                                        resolution.attachments
                                    }

                                    is CommandPostAttachments.Result.Failure -> {
                                        return ThreadResult.Failure("Post $key: ${resolution.message}")
                                    }
                                }
                            } else {
                                null
                            }
                        val comment = if (value.isNull("comment")) null else value.optString("comment")
                        if (comment != null || resolved != null) {
                            changes[key] = ThreadChange(comment, resolved)
                        }
                    }
                }
                ThreadResult.Success(changes)
            } else {
                ThreadResult.Failure(json.optString("error", "Unknown error"))
            }
        } catch (e: Exception) {
            ThreadResult.Failure(e.message ?: raw)
        }
    }
}
