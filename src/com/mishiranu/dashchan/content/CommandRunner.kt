package com.mishiranu.dashchan.content

import android.webkit.JavascriptInterface
import com.mishiranu.dashchan.content.storage.CommandsStorage
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
 * - [CommandsStorage.UseIn.COMMENT] (see [run]): `comment`, `thread`, `board`, `env`. It should
 *   `return` the replacement comment; returning `undefined`/`null` leaves the field untouched.
 * - [CommandsStorage.UseIn.THREAD] (see [runThread]): `posts`, `thread`, `board`, `env`. `posts` is an
 *   array of `{number, name, email, icon, subject, comment}`, `comment` being the post's HTML as the
 *   chan sent it. It should `return` an object mapping a post's `number` to the HTML to display in
 *   place of that post's comment (e.g. `{ "123": "<b>decrypted…</b>" }`); posts absent from the object
 *   are left as-is, and returning `undefined`/`null` changes nothing.
 *
 *   In and out are the same form on purpose: a replacement is parsed exactly like a real comment, so
 *   greentext, spoilers and `>>` links written the way the chan writes them keep working, and a script
 *   that edits only the text it cares about can hand the rest of the post's HTML straight back. The
 *   flip side is that a replacement is HTML, so plain text carrying `<` or `&` has to be escaped.
 *
 * `thread` is the thread number and `board` the board code — plain strings, either `null` when there
 * is no such context. Being async, a body may `await` (e.g.
 * `return await fetch(url).then(r => r.text())`).
 *
 * Because it runs on the [HeadlessJsEngine] the code may use `fetch`/`XMLHttpRequest` to reach the
 * network (CORS is disabled there) and the WebCrypto API (`crypto.subtle`, the origin is a secure
 * context), so scripts can encrypt/decrypt or translate posts. A fresh engine is created per run and
 * destroyed once the result arrives.
 *
 * The code also receives an `env` object holding the user's shared key→value store
 * ([CommandsStorage.getEnv]), so a script can read a user-provided value with
 * `env.CUSTOM_NAME_HERE`. It is a read-only snapshot taken at run time — assigning to it does not
 * persist; the store is edited from the Commands screen.
 */
object CommandRunner {
    /**
     * One post handed to a [CommandsStorage.UseIn.THREAD] script as an element of `posts`. [comment] is
     * the post's HTML, not the rendered text — see
     * [Post.comment][chan.content.model.Post.getComment].
     */
    data class ThreadPost(
        val number: String,
        val name: String?,
        val email: String?,
        val icon: String?,
        val subject: String?,
        val comment: String,
    )

    /** Outcome of a [CommandsStorage.UseIn.COMMENT] command. */
    sealed interface Result {
        /**
         * The command ran successfully. [comment] is the replacement text, or `null` if the script
         * returned nothing — in which case the field should be left untouched.
         */
        data class Success(
            val comment: String?,
        ) : Result

        /** The command threw or could not be evaluated; [message] describes what went wrong. */
        data class Failure(
            val message: String,
        ) : Result
    }

    /** Outcome of a [CommandsStorage.UseIn.THREAD] command. */
    sealed interface ThreadResult {
        /**
         * The command ran successfully. [replacements] maps a post number to the HTML to display in
         * place of that post's comment; it is empty when the script asked for no changes.
         */
        data class Success(
            val replacements: Map<String, String>,
        ) : ThreadResult

        /** The command threw or could not be evaluated; [message] describes what went wrong. */
        data class Failure(
            val message: String,
        ) : ThreadResult
    }

    /**
     * Evaluates a [CommandsStorage.UseIn.COMMENT] [item] against the given inputs and delivers the
     * outcome to [callback] on the main thread. Safe to call from the main thread.
     */
    fun run(
        item: CommandsStorage.CommandItem,
        comment: String,
        thread: String?,
        board: String?,
        callback: (Result) -> Unit,
    ) {
        val script =
            buildScript(
                args = "\"comment\",\"thread\",\"board\",\"env\"",
                code = item.code.orEmpty(),
                thread = thread,
                board = board,
                env = CommandsStorage.getInstance().getEnv(),
                // A comment command replaces the field with a single string.
                resultExpr = "(__result===undefined||__result===null)?null:String(__result)",
            ) { append(jsArg(comment)).append(',') }
        execute(script, ::parseComment, { Result.Failure(it) }, callback)
    }

    /**
     * Evaluates a [CommandsStorage.UseIn.THREAD] [item] against the thread's [posts] and delivers the
     * outcome to [callback] on the main thread. Safe to call from the main thread.
     */
    fun runThread(
        item: CommandsStorage.CommandItem,
        posts: List<ThreadPost>,
        thread: String?,
        board: String?,
        callback: (ThreadResult) -> Unit,
    ) {
        val postsJson = postsToJson(posts)
        val script =
            buildScript(
                args = "\"posts\",\"thread\",\"board\",\"env\"",
                code = item.code.orEmpty(),
                thread = thread,
                board = board,
                env = CommandsStorage.getInstance().getEnv(),
                // A thread command returns a { postNumber: replacementHtml } object.
                resultExpr = "(__result===undefined||__result===null)?{}:__result",
            ) { append(postsJson).append(',') }
        execute(script, ::parseThread, { ThreadResult.Failure(it) }, callback)
    }

    /**
     * Runs [script] on a fresh [HeadlessJsEngine] and routes the outcome to [callback] on the main
     * thread. The script runs the user code as an *async* function, so a returned Promise (e.g. `await
     * fetch(...)`) is honoured. evaluateJavascript can't await Promises, so the result comes back
     * through a bridge instead of the evaluation's return value; a timeout guards a script that never
     * settles. [parse] turns the bridged JSON into a result; [failure] wraps a timeout/error message.
     */
    private fun <R> execute(
        script: String,
        parse: (String?) -> R,
        failure: (String) -> R,
        callback: (R) -> Unit,
    ) {
        val delivered = AtomicBoolean(false)
        val engineHolder = arrayOfNulls<HeadlessJsEngine>(1)
        val deliver: (R) -> Unit = { result ->
            if (delivered.compareAndSet(false, true)) {
                ConcurrentUtils.HANDLER.post {
                    engineHolder[0]?.destroy()
                    callback(result)
                }
            }
        }
        val timeout = Runnable { deliver(failure("Command timed out")) }
        val bridge =
            object {
                @JavascriptInterface
                fun onResult(json: String?) {
                    ConcurrentUtils.HANDLER.removeCallbacks(timeout)
                    deliver(parse(json))
                }
            }
        val engine = HeadlessJsEngine(mapOf(BRIDGE_NAME to bridge))
        engineHolder[0] = engine
        ConcurrentUtils.HANDLER.postDelayed(timeout, TIMEOUT_MS)
        engine.evaluate(script)
    }

    /**
     * Builds the runnable script. [args] is the quoted, comma-separated argument list of the async
     * function; [leadingArg] appends the matching first actual argument(s) (already followed by a
     * comma), after which `thread`, `board` and the frozen `env` object are passed. [resultExpr] is the
     * JS expression that maps the resolved `__result` to the value delivered back through the bridge.
     */
    private fun buildScript(
        args: String,
        code: String,
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
            // Compile the body as an *async* function (via the AsyncFunction constructor) so the user
            // code may `await` — e.g. `return await fetch(url).then(r => r.text())`. Building it here
            // rather than inlining also means a *syntax* error throws at construction and is caught,
            // surfacing a real message instead of a null result.
            append("var __AsyncFunction=Object.getPrototypeOf(async function(){}).constructor;")
            append("var __command=new __AsyncFunction(").append(args).append(',')
            append(JSONObject.quote(code))
            append(");")
            append("Promise.resolve(__command(")
            leadingArg()
            append(jsArg(thread)).append(',')
            append(jsArg(board)).append(',')
            // The shared env store, injected as a plain object so scripts read `env.NAME`. Frozen so
            // a stray `env.X = …` is dropped (throwing under "use strict") rather than mutating a
            // value that would never be persisted — this is a per-run snapshot.
            append("Object.freeze(").append(JSONObject(env).toString()).append(')')
            append(")).then(function(__result){")
            append("__deliver({ok:true,result:").append(resultExpr).append("});")
            append("}).catch(function(__err){")
            append("__deliver({ok:false,error:(__err&&__err.message)?String(__err.message):String(__err)});")
            append("});")
            append("}catch(__err){__deliver({ok:false,error:(__err&&__err.message)?String(__err.message):String(__err)});}")
            append("})();")
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
            array.put(obj)
        }
        return array.toString()
    }

    private fun jsArg(value: String?): String = if (value == null) "null" else JSONObject.quote(value)

    private const val BRIDGE_NAME = "__commandBridge"
    private const val TIMEOUT_MS = 30000L

    private fun parseComment(raw: String?): Result {
        if (raw == null) {
            return Result.Failure("No result (script did not evaluate)")
        }
        return try {
            val json = JSONObject(raw)
            if (json.optBoolean("ok")) {
                Result.Success(if (json.isNull("result")) null else json.optString("result"))
            } else {
                Result.Failure(json.optString("error", "Unknown error"))
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: raw)
        }
    }

    private fun parseThread(raw: String?): ThreadResult {
        if (raw == null) {
            return ThreadResult.Failure("No result (script did not evaluate)")
        }
        return try {
            val json = JSONObject(raw)
            if (json.optBoolean("ok")) {
                val replacements = LinkedHashMap<String, String>()
                // A non-object return (string, array, …) yields no object here and thus no changes.
                val result = json.optJSONObject("result")
                if (result != null) {
                    val keys = result.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (!result.isNull(key)) {
                            replacements[key] = result.optString(key)
                        }
                    }
                }
                ThreadResult.Success(replacements)
            } else {
                ThreadResult.Failure(json.optString("error", "Unknown error"))
            }
        } catch (e: Exception) {
            ThreadResult.Failure(e.message ?: raw)
        }
    }
}
