package com.mishiranu.dashchan.content

import com.mishiranu.dashchan.content.storage.CommandsStorage
import org.json.JSONObject

/**
 * Runs a user-defined [CommandsStorage.CommandItem] through the [HeadlessJsEngine].
 *
 * The command's [code][CommandsStorage.CommandItem.code] is treated as the body of a JavaScript
 * function that receives three inputs — `comment`, `thread` and `board` — and is expected to
 * `return` the replacement comment. `thread` and `board` are the current thread/board identifiers
 * (or `null`), `comment` is the current text of the posting field. Whatever the function returns is
 * coerced to a string and handed back as the new comment; returning `undefined`/`null` leaves the
 * comment untouched.
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
        val engine = HeadlessJsEngine()
        engine.evaluate(script) { raw ->
            engine.destroy()
            callback(parse(raw))
        }
    }

    private fun buildScript(
        code: String,
        comment: String,
        thread: String?,
        board: String?,
    ): String =
        buildString {
            // Compile the body with `new Function` rather than inlining it: that way a *syntax* error
            // in the user's code throws at construction time and is caught below, surfacing a real
            // message, instead of breaking the enclosing script and yielding a null result.
            append("(function(){try{")
            append("var __command=new Function(\"comment\",\"thread\",\"board\",")
            append(JSONObject.quote(code))
            append(");")
            append("var __result=__command(")
            append(jsArg(comment)).append(',')
            append(jsArg(thread)).append(',')
            append(jsArg(board))
            append(");")
            append("return{ok:true,result:(__result===undefined||__result===null)?null:String(__result)};")
            append("}catch(e){return{ok:false,error:(e&&e.message)?String(e.message):String(e)};}})()")
        }

    private fun jsArg(value: String?): String = if (value == null) "null" else JSONObject.quote(value)

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
