package com.mishiranu.dashchan.content

import com.mishiranu.dashchan.ui.ForegroundManager
import com.mishiranu.dashchan.util.ConcurrentUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * `dialog`, the object a command stops and asks the user something through — the fourth of the objects
 * a script is handed, and like the store one every command gets (see [CommandApp] for the other three).
 *
 * ```
 * dialog.show("Nothing to rotate today.", { title: "Asocks" })    // OK, and nothing back
 * dialog.confirm("Buy another port?")                             // true / false
 * dialog.prompt("Code from the SMS", { value: "", title: "…" })   // the text, or null if cancelled
 * dialog.choose(["Germany", "Poland", "Sweden"], { title: "…" })  // the index, or null if cancelled
 * dialog.chooseMany(["Germany", "Poland"], { selected: [0] })     // the indices ticked, [] for none
 * ```
 *
 * Every one of them is a call that returns what the user answered, so a script reads one the way it
 * reads anything else the bridge answers — no callback, no promise. What it costs is the run standing
 * still until the dialog is answered, which is the whole point of asking.
 *
 * `options` takes a `title` throughout, the `ok`/`cancel` labels for the buttons of the ones that have
 * them, a starting `value` for `prompt`'s field, and `selected` for a picker — the one index for
 * `choose`, the array of them for `chooseMany`. Cancelling answers `false` or `null` throughout; `show`
 * has nothing to cancel and hands nothing back. A `prompt` confirmed with the field emptied answers
 * `""` and a `chooseMany` confirmed with nothing ticked answers `[]` — each an answer, not a cancel.
 *
 * The three the JavaScript runtime already has are bound to these as well, since they are what a script
 * reaches for first and a WebView with nothing behind them cancels every one — `confirm()` answering
 * "no" without having asked. `alert`, `confirm` and `prompt` are the dialogs above (see
 * [CommandRunner], which binds them), and the two pickers, having no standard name to keep, are global
 * as `choose` and `chooseMany` too. A library that declares a name of its own shadows the global, as it
 * would any other.
 *
 * Every target may show one, and what that costs differs by target rather than being refused for any of
 * them. An App command is the one the user ran on purpose and is waiting on, so a question from it is
 * part of the same gesture. A draft command runs in the middle of them sending a post and a thread
 * command when a thread opens — a window in the way there interrupts what they were doing, and a
 * [perPost][com.mishiranu.dashchan.content.storage.CommandsStorage.CommandItem.perPost] body asking
 * anything asks it once per post, which over a long thread is a great many dialogs. That is the script's
 * to get right; leaving the screen cancels the run and takes the dialog with it either way.
 *
 * There is no grant behind it, unlike the settings or a forum's cookies: a dialog reaches nothing of the
 * user's, it shows itself to them, and what it hands back is what they chose to type into it. That is
 * the same reason the script's own store needs none.
 *
 * The wait is the one [ForegroundManager] does for a captcha: the dialog belongs to the activity in the
 * foreground and survives the screen being rotated under it. A call made when there is no screen at all
 * throws rather than answering for the user. Until it is answered the run keeps a longer deadline —
 * a script waiting on the user is not a script that has hung — and a run given up on meanwhile takes
 * the dialog down with it and fails the call (see [CommandRunner.Run.awaitUser]), which is what happens
 * when the screen the command was started from goes away.
 */
object CommandDialogs {
    private const val METHOD_SHOW = "dialog.show"
    private const val METHOD_CONFIRM = "dialog.confirm"
    private const val METHOD_PROMPT = "dialog.prompt"
    private const val METHOD_CHOOSE = "dialog.choose"
    private const val METHOD_CHOOSE_MANY = "dialog.chooseMany"

    private const val KEY_TITLE = "title"
    private const val KEY_MESSAGE = "message"
    private const val KEY_VALUE = "value"
    private const val KEY_OK = "ok"
    private const val KEY_CANCEL = "cancel"
    private const val KEY_ITEMS = "items"
    private const val KEY_SELECTED = "selected"

    /** The methods [CommandApp.Bridge] routes here, the rest of its own being about the app or a forum. */
    val METHODS: Set<String> =
        setOf(METHOD_SHOW, METHOD_CONFIRM, METHOD_PROMPT, METHOD_CHOOSE, METHOD_CHOOSE_MANY)

    /**
     * The `dialog` object, as a JavaScript expression built into [CommandApp.SOURCE] — which is where
     * the `__call` it funnels through and the `__opt` that tolerates a missing `options` are declared,
     * so this is a piece of that source rather than one of its own.
     */
    val SOURCE: String =
        "Object.freeze({" +
            "show:function(message,options){" +
            "var __o=__opt(options);" +
            "return __call('$METHOD_SHOW',{message:message,title:__o.title,ok:__o.ok});" +
            "}," +
            "confirm:function(message,options){" +
            "var __o=__opt(options);" +
            "return __call('$METHOD_CONFIRM',{message:message,title:__o.title,ok:__o.ok,cancel:__o.cancel});" +
            "}," +
            "prompt:function(message,options){" +
            "var __o=__opt(options);" +
            "return __call('$METHOD_PROMPT'," +
            "{message:message,title:__o.title,value:__o.value,ok:__o.ok,cancel:__o.cancel});" +
            "}," +
            "choose:function(items,options){" +
            "var __o=__opt(options);" +
            "return __call('$METHOD_CHOOSE',{items:items,title:__o.title,selected:__o.selected});" +
            "}," +
            "chooseMany:function(items,options){" +
            "var __o=__opt(options);" +
            "return __call('$METHOD_CHOOSE_MANY',{items:items,title:__o.title,selected:__o.selected});" +
            "}" +
            "})"

    /**
     * Carries out one of [METHODS] for [run] and answers what the user did — the value the script sees
     * come back from its call. Arrives on the JS bridge thread and blocks it until the dialog is
     * answered.
     */
    internal fun dispatch(
        run: CommandRunner.Run,
        method: String,
        args: JSONObject,
    ): Any? =
        when (method) {
            METHOD_SHOW -> {
                // Nothing to say back: a message dialog is answered by being read.
                ask(run, args, input = null, negative = null)
                null
            }

            METHOD_CONFIRM -> {
                ask(run, args, input = null, negative = cancelLabel(args)).confirmed
            }

            METHOD_PROMPT -> {
                // A field the script gave no starting value for is an empty one, not an absent one —
                // absent is what makes the dialog a message instead of a question.
                val result = ask(run, args, optional(args, KEY_VALUE).orEmpty(), cancelLabel(args))
                if (result.confirmed) result.text.orEmpty() else null
            }

            METHOD_CHOOSE -> {
                choose(run, args)
            }

            METHOD_CHOOSE_MANY -> {
                chooseMany(run, args)
            }

            else -> {
                throw IllegalArgumentException("Unknown dialog method \"$method\"")
            }
        }

    /**
     * Puts up the title/message dialog the arguments describe and waits for it. [input] makes it a
     * question with a text field and [negative] gives it a second button; either left out is what
     * makes it the plainer thing.
     *
     * The screen going in the moment between [onScreen]'s check and the dialog being shown reads as a
     * cancel: nobody is there to answer, and a cancel is an answer every caller here already handles.
     */
    private fun ask(
        run: CommandRunner.Run,
        args: JSONObject,
        input: String?,
        negative: String?,
    ): ForegroundManager.InputResult =
        onScreen(run) {
            ForegroundManager.getInstance().requireUserInput(
                optional(args, KEY_TITLE),
                optional(args, KEY_MESSAGE),
                input,
                optional(args, KEY_OK),
                negative,
            )
        } ?: ForegroundManager.InputResult(false, null)

    /**
     * Puts the script's items to the user as a list and answers the index they picked, or `null` for a
     * dialog they cancelled. This is the single-choice dialog the app already asks its own questions
     * with, so a script gets the items and a title around them and nothing else to arrange.
     */
    private fun choose(
        run: CommandRunner.Run,
        args: JSONObject,
    ): Any? {
        val labels = items(args)
        val selected = if (args.isNull(KEY_SELECTED)) -1 else args.optInt(KEY_SELECTED, -1)
        val result =
            onScreen(run) {
                ForegroundManager
                    .getInstance()
                    .requireUserItemSingleChoice(selected, labels, optional(args, KEY_TITLE), null)
            }
        // OK with nothing ticked answers -1, which is as much "chose nothing" as a cancel is.
        return result?.takeIf { it >= 0 }
    }

    /**
     * The same list with a checkbox on every row, answering the indices ticked — `[]` for a list the
     * user confirmed having ticked nothing, `null` for one they cancelled, which is the difference a
     * script has to be able to tell. `selected` starts rows ticked and is an array of indices here,
     * where [choose]'s is the one index.
     */
    private fun chooseMany(
        run: CommandRunner.Run,
        args: JSONObject,
    ): Any? {
        val labels = items(args)
        val selected = BooleanArray(labels.size)
        args.optJSONArray(KEY_SELECTED)?.let { array ->
            for (index in 0 until array.length()) {
                // An index for a row that isn't there ticks nothing, rather than failing the call over
                // a list the script trimmed and a default it forgot to.
                array.optInt(index, -1).takeIf { it in labels.indices }?.let { selected[it] = true }
            }
        }
        val result =
            onScreen(run) {
                ForegroundManager
                    .getInstance()
                    .requireUserItemMultipleChoice(selected, labels, optional(args, KEY_TITLE), null)
            } ?: return null
        val picked = JSONArray()
        for (index in result.indices) {
            if (result[index]) {
                picked.put(index)
            }
        }
        return picked
    }

    /** What the script gave a picker to choose from. An empty list is a dialog with nothing in it. */
    private fun items(args: JSONObject): Array<CharSequence?> {
        val items = args.optJSONArray(KEY_ITEMS)
        require(items != null && items.length() > 0) {
            "\"items\" is required: pass what there is to choose from"
        }
        return Array(items.length()) { items.optString(it) }
    }

    /**
     * Waits on [action] with the run's deadline widened for it (see [CommandRunner.Run.awaitUser]),
     * having first refused outright when there is no screen to put a dialog on: a script that asked a
     * question of nobody is better told so than answered with a silent cancel it would read as the
     * user's.
     */
    private fun <T> onScreen(
        run: CommandRunner.Run,
        action: () -> T,
    ): T {
        requireNotNull(ConcurrentUtils.mainGet { ForegroundManager.getInstance().currentActivity }) {
            "There is no screen to show a dialog on"
        }
        return run.awaitUser(action)
    }

    /**
     * What the second button is labelled. Resolved here rather than in the dialog, unlike the first
     * one's OK: whether there is a second button at all is this object's answer, so what it says when
     * the script named nothing is too.
     */
    private fun cancelLabel(args: JSONObject): String =
        optional(args, KEY_CANCEL)
            ?: MainApplication.getInstance().getString(android.R.string.cancel)

    /** An optional string argument: `null` when the script left it out or passed `null`. */
    private fun optional(
        args: JSONObject,
        key: String,
    ): String? = if (args.isNull(key)) null else args.optString(key).takeIf { it.isNotEmpty() }
}
