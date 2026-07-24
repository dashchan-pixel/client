package com.mishiranu.dashchan.content

import android.webkit.JavascriptInterface
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.util.ConcurrentUtils
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a user-defined [CommandsStorage.CommandItem] through the [HeadlessJsEngine].
 *
 * The command's [code][CommandsStorage.CommandItem.code] is treated as the body of an **async**
 * JavaScript function that receives three inputs — `comment`, `thread` and `board` — and is expected
 * to `return` the replacement comment. Being async, the body may `await` (e.g.
 * `return await fetch(url).then(r => r.text())`). `thread` and `board` are the current thread/board
 * identifiers (or `null`), `comment` is the current text of the posting field. Whatever the function
 * returns (or the promise it resolves to) is coerced to a string and handed back as the new comment;
 * returning `undefined`/`null` leaves the comment untouched.
 *
 * Because it runs on the [HeadlessJsEngine] the code may use `fetch`/`XMLHttpRequest` to reach the
 * network (CORS is disabled there), so scripts can, for example, build a signature from a remote
 * source. A fresh engine is created per run and destroyed once the result arrives.
 */
object CommandRunner {
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

    /**
     * Evaluates [item] against the given inputs and delivers the outcome to [callback] on the main
     * thread. Safe to call from the main thread.
     */
    fun run(
        item: CommandsStorage.CommandItem,
        comment: String,
        thread: String?,
        board: String?,
        callback: (Result) -> Unit,
    ) {
        val script = buildScript(item.code.orEmpty(), comment, thread, board)
        // The script runs the user code as an *async* function, so a returned Promise (e.g. `await
        // fetch(...)`) is honoured. evaluateJavascript can't await Promises, so the result comes back
        // through a bridge instead of the evaluation's return value; a timeout guards a script that
        // never settles.
        val delivered = AtomicBoolean(false)
        val engineHolder = arrayOfNulls<HeadlessJsEngine>(1)
        val deliver: (Result) -> Unit = { result ->
            if (delivered.compareAndSet(false, true)) {
                ConcurrentUtils.HANDLER.post {
                    engineHolder[0]?.destroy()
                    callback(result)
                }
            }
        }
        val timeout = Runnable { deliver(Result.Failure("Command timed out")) }
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

    private fun buildScript(
        code: String,
        comment: String,
        thread: String?,
        board: String?,
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
            append("var __command=new __AsyncFunction(\"comment\",\"thread\",\"board\",")
            append(JSONObject.quote(code))
            append(");")
            append("Promise.resolve(__command(")
            append(jsArg(comment)).append(',')
            append(jsArg(thread)).append(',')
            append(jsArg(board))
            append(")).then(function(__result){")
            append("__deliver({ok:true,result:(__result===undefined||__result===null)?null:String(__result)});")
            append("}).catch(function(__err){")
            append("__deliver({ok:false,error:(__err&&__err.message)?String(__err.message):String(__err)});")
            append("});")
            append("}catch(__err){__deliver({ok:false,error:(__err&&__err.message)?String(__err.message):String(__err)});}")
            append("})();")
        }

    private fun jsArg(value: String?): String = if (value == null) "null" else JSONObject.quote(value)

    private const val BRIDGE_NAME = "__commandBridge"
    private const val TIMEOUT_MS = 30000L

    private fun parse(raw: String?): Result {
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
}
