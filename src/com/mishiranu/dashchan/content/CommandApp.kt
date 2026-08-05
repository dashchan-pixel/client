package com.mishiranu.dashchan.content

import android.webkit.JavascriptInterface
import chan.content.Chan
import chan.content.ChanManager
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.net.ProxyProvider
import com.mishiranu.dashchan.content.net.VisibleAddress
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.ui.ForegroundManager
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The objects a command script is handed besides its own input and `env` (see [CommandRunner]): `app`,
 * the application's own **settings**; `chan`, the forum it is running in, that forum's **cookies** and
 * the **address** that forum sees this device at; and `store`, a place of its own to keep what it wants
 * to remember between runs.
 *
 * They are kept apart because they are granted apart. `app` and `chan` reach what belongs to the user
 * — the same things the settings screens and "Manage cookies" edit by hand — and a command gets
 * neither until the user grants it (see [CommandsStorage.Grant]); the address is a grant of its own
 * even so, being the one thing here that spends anything of the user's outside the device; `env` is
 * granted by default and can be taken away; `store` needs no grant at all, holding only what the same
 * script put there. A grant
 * belongs to one command, is given in that command's editor, and is never carried in by an imported
 * document.
 *
 * Where `env` is a read-only snapshot of values the user typed, these write through, so a command can
 * hand what it obtained to the rest of the app. A cookie stored here is a cookie the forum's extension
 * can send: a script that solves a check itself stores the clearance it got the way
 * [CloudFlareResolver][com.mishiranu.dashchan.content.net.firewall.CloudFlareResolver] does, and every
 * later request benefits.
 *
 * ```
 * store.get("my_key")                        // whatever a script stored there, or null
 * store.set("my_key", { any: ["json", 1, true] })
 * store.remove("my_key")                     // same as set(key, null)
 * store.keys()                               // ["my_key", …]
 *
 * app.get("cache_size")                      // a string, number, boolean, list of strings, or null
 * app.has("cache_size")
 * app.all()                                  // every stored setting, as an object
 * app.set("cache_size", 200)
 * app.remove("cache_size")                   // same as set(key, null)
 *
 * chan.name                                  // the forum this run belongs to, or null
 * chan.cookies.list()                        // [{name, value, title, blocked, deleteOnExit}, …]
 * chan.cookies.get("cf_clearance")           // the value the app would send, or null
 * chan.cookies.set("cf_clearance", value, { title: "Cloudflare" })
 * chan.cookies.remove("cf_clearance")        // same as set(name, null)
 * chan.cookies.setState("cf_clearance", { blocked: false, deleteOnExit: true })
 *
 * chan.visibleAddress()                      // {address, location}, or null when it can't be read
 * chan.rotateVisibleAddress()                // another address on this forum's port; true when done
 * ```
 *
 * A command without a grant still gets the object — every call to it throws saying it wasn't granted,
 * which is a message that points at the switch, where an undefined `app` would send the user looking
 * at their own script instead. `chan.name` is the one member a missing grant leaves readable as
 * `null`, since a script may reasonably ask which forum it is in before deciding it needs anything.
 * The cookies and the address are granted separately from each other, so a script that has one may
 * still be refused the other.
 *
 * A grant is checked in [Bridge.dispatch] rather than in the JavaScript, because the bridge object is
 * bound to a global the script can reach directly: a wrapper that refused would be walked around by
 * anything that called `window.__commandAppBridge` itself.
 *
 * Every call is synchronous, and every one that cannot be carried out throws — a missing grant, an
 * unknown forum, a missing argument, a setting written with the wrong kind of value — so a script may
 * `try`/`catch` around one, and a failure it doesn't catch fails the run with that message.
 *
 * A setting is named by the key the app stores it under (`cache_size`, `active_scrollbar`, …; the
 * per-forum ones are `<forum>_<key>`, e.g. `4chan_captcha`) and holds the kind of value the app reads
 * it back as. A *string* written where a *boolean* is read would make the setting throw at every
 * reader, so a write whose kind disagrees with what is stored is refused instead; a key with nothing
 * stored yet takes the kind of the value it is given.
 *
 * Most settings are read where they are used, so a write is picked up by the next list bound or the
 * next request made, exactly as one the user changed would be. The handful the app reads only while
 * an activity is being built — the theme above all — would otherwise wait for a rebuild that a
 * preserved app never does: those are listed in [APPLIED_ON_CREATE], and writing one rebuilds the
 * screen once the run is over (see [Bridge.applyIfNeeded]).
 *
 * A cookie belongs to a forum: the one the command is running in, unless the call names another.
 * `get` answers with the value the app would actually send, so a cookie the user blocked reads as
 * `null`; `list` is the management view and shows every cookie with its flags, blocked ones included.
 * The address calls take the same optional forum name for the same reason.
 *
 * The visible address is the one the *forum* sees, so it is read through that forum's proxy (see
 * [VisibleAddress]) — a `fetch` leaves the WebView on its own and would answer with the device's
 * address instead, which is the whole reason for asking here. Rotating it asks the proxy provider for
 * another address on the port the forum uses (see [ProxyProvider]) and drops the connections that
 * would otherwise still ride the old one. Nothing is bought: a forum the provider covers but has given
 * no port yet is refused, where the button on the forum's own screen would buy it one, because a script
 * must not spend the account's balance by itself. Both calls reach the network, so both take as long as
 * a request does where every other call here answers at once.
 *
 * The store takes any value `JSON.stringify` can carry, and hands it back parsed. It is one namespace
 * for every command, so a key is worth prefixing; it is not a cache to pour things into either, and a
 * value over [MAX_STORE_VALUE_LENGTH] characters or a key beyond [MAX_STORE_KEYS] is refused rather
 * than allowed to bloat the file the commands themselves are kept in. The user can see what is in it,
 * and empty it, from the Commands screen — unlike a setting a script squats in.
 *
 * All three go through a single [Bridge.call], since a `@JavascriptInterface` can only carry strings
 * across; the shapes above are [SOURCE], the JavaScript wrapper built around it.
 *
 * They are in scope for a command's [libraries][CommandLibraries] as much as for its own body, and a
 * library may be a script downloaded from an address — a library runs with whatever the command that
 * loaded it was granted. Together with the engine's unrestricted network access that puts the user's
 * cookies one `fetch` away from anywhere, so a library address is worth as much trust as the command
 * itself, which is what granting it anything already assumed.
 */
object CommandApp {
    /** The global [Bridge] is bound to in the engine. Only [SOURCE] should reach for it. */
    const val BRIDGE_NAME: String = "__commandAppBridge"

    private const val METHOD_CHAN = "chan"
    private const val METHOD_SETTINGS_GET = "settings.get"
    private const val METHOD_SETTINGS_HAS = "settings.has"
    private const val METHOD_SETTINGS_ALL = "settings.all"
    private const val METHOD_SETTINGS_SET = "settings.set"
    private const val METHOD_STORE_GET = "store.get"
    private const val METHOD_STORE_SET = "store.set"
    private const val METHOD_STORE_KEYS = "store.keys"
    private const val METHOD_COOKIES_LIST = "cookies.list"
    private const val METHOD_COOKIES_GET = "cookies.get"
    private const val METHOD_COOKIES_SET = "cookies.set"
    private const val METHOD_COOKIES_STATE = "cookies.setState"
    private const val METHOD_ADDRESS_GET = "address.get"
    private const val METHOD_ADDRESS_ROTATE = "address.rotate"

    private const val KEY_OK = "ok"
    private const val KEY_RESULT = "result"
    private const val KEY_ERROR = "error"
    private const val KEY_KEY = "key"
    private const val KEY_VALUE = "value"
    private const val KEY_JSON = "json"
    private const val KEY_NAME = "name"
    private const val KEY_TITLE = "title"
    private const val KEY_CHAN = "chan"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_DELETE_ON_EXIT = "deleteOnExit"
    private const val KEY_ADDRESS = "address"
    private const val KEY_LOCATION = "location"

    /**
     * The settings the app reads only while an activity is being built, so a write to one shows
     * nothing until the screen is built again. Each is a key some settings screen pairs with a
     * `recreate()` of its own — the themes (`ThemesFragment`, `ThemeEditorFragment`), the three scales
     * (`InterfaceFragment`), the language (`GeneralFragment`) and the expanded screen (`MainActivity`)
     * — and a command that writes one gets the same treatment at the end of its run.
     *
     * Everything else is read where it is used: the next list bound, the next request made, the next
     * notification posted. Those need nothing here; they follow on their own.
     */
    private val APPLIED_ON_CREATE =
        setOf(
            Preferences.KEY_THEME,
            Preferences.KEY_THEME_NIGHT,
            Preferences.KEY_THEME_FOLLOW_SYSTEM,
            Preferences.KEY_TEXT_SCALE,
            Preferences.KEY_THUMBNAILS_SCALE,
            Preferences.KEY_UI_CORNER_RADIUS,
            Preferences.KEY_LOCALE,
            Preferences.KEY_EXPANDED_SCREEN,
        )

    /** Longest JSON text [Bridge] will keep under one store key. */
    const val MAX_STORE_VALUE_LENGTH: Int = 64 * 1024

    /** How many keys the store may hold before a *new* one is refused. */
    const val MAX_STORE_KEYS: Int = 512

    /**
     * The three objects, as one JavaScript expression evaluated per run in the scope the command and
     * its libraries are built in. It answers `{store, app, chan}`, which [CommandRunner] hands to the
     * body as three arguments.
     *
     * Everything the script calls funnels into `__call`, which is where the JSON encoding lives and
     * where a refused call — including one refused for want of a grant — becomes a thrown `Error`.
     * `undefined` is turned into `null` on the way out because `JSON.stringify` drops an undefined
     * property, which would otherwise make `set(key, undefined)` arrive as a call naming no value.
     *
     * The store stringifies on the way in and parses on the way back out, so what crosses is always
     * text and neither side has to describe the shape. A value `JSON.stringify` cannot carry (a
     * function, say) comes back `undefined` from it, and is refused here rather than quietly filed as
     * a removal.
     */
    val SOURCE: String =
        "(function(__b){" +
            "function __call(__m,__a){" +
            "var __r=JSON.parse(__b.call(__m,JSON.stringify(__a)));" +
            "if(!__r.ok){throw new Error(__r.error);}" +
            "return __r.result;" +
            "}" +
            "function __opt(__o){return __o||{};}" +
            "return Object.freeze({" +
            "store:Object.freeze({" +
            "get:function(key){" +
            "var __t=__call('$METHOD_STORE_GET',{key:key});" +
            "return __t===null?null:JSON.parse(__t);" +
            "}," +
            "set:function(key,value){" +
            "var __j=(value===undefined||value===null)?null:JSON.stringify(value);" +
            "if(__j===undefined){throw new Error('Value for \"'+key+'\" cannot be stored as JSON');}" +
            "return __call('$METHOD_STORE_SET',{key:key,json:__j});" +
            "}," +
            "remove:function(key){return __call('$METHOD_STORE_SET',{key:key,json:null});}," +
            "keys:function(){return __call('$METHOD_STORE_KEYS',{});}" +
            "})," +
            "app:Object.freeze({" +
            "get:function(key){return __call('$METHOD_SETTINGS_GET',{key:key});}," +
            "has:function(key){return __call('$METHOD_SETTINGS_HAS',{key:key});}," +
            "all:function(){return __call('$METHOD_SETTINGS_ALL',{});}," +
            "set:function(key,value){" +
            "return __call('$METHOD_SETTINGS_SET',{key:key,value:value===undefined?null:value});" +
            "}," +
            "remove:function(key){return __call('$METHOD_SETTINGS_SET',{key:key,value:null});}" +
            "})," +
            "chan:Object.freeze({" +
            "name:__call('$METHOD_CHAN',{})," +
            "cookies:Object.freeze({" +
            "list:function(chan){return __call('$METHOD_COOKIES_LIST',{chan:chan});}," +
            "get:function(name,chan){return __call('$METHOD_COOKIES_GET',{name:name,chan:chan});}," +
            "set:function(name,value,options){" +
            "var __o=__opt(options);" +
            "return __call('$METHOD_COOKIES_SET'," +
            "{name:name,value:value===undefined?null:value,title:__o.title,chan:__o.chan});" +
            "}," +
            "remove:function(name,chan){" +
            "return __call('$METHOD_COOKIES_SET',{name:name,value:null,chan:chan});" +
            "}," +
            "setState:function(name,state,chan){" +
            "var __s=__opt(state);" +
            "return __call('$METHOD_COOKIES_STATE'," +
            "{name:name,blocked:__s.blocked,deleteOnExit:__s.deleteOnExit,chan:chan});" +
            "}" +
            "})," +
            "visibleAddress:function(chan){return __call('$METHOD_ADDRESS_GET',{chan:chan});}," +
            "rotateVisibleAddress:function(chan){return __call('$METHOD_ADDRESS_ROTATE',{chan:chan});}" +
            "})" +
            "});" +
            "})(window.$BRIDGE_NAME)"

    /**
     * Builds the bridge for a run belonging to [chanName] (`null` where there is no forum) with the
     * [grants] the command carries. They are taken once, at the start of the run, so a body and the
     * libraries around it all work against the same answer.
     */
    fun bridge(
        chanName: String?,
        grants: Set<CommandsStorage.Grant>,
    ): Bridge = Bridge(chanName, grants)

    /**
     * What [SOURCE] talks to. One entry point rather than a method each: a `@JavascriptInterface` can
     * only take and return primitives, so arguments and results travel as JSON either way, and having
     * one place to encode that gives one place to turn a failure into a JS exception too.
     *
     * Calls arrive on the engine's own bridge thread, not the main one. Both stores behind it are made
     * for that — [ChanDatabase] is used from the background all over the app, and a preferences write
     * marshals its own commit onto the main thread (see [SharedPreferences.Editor.close]).
     */
    class Bridge internal constructor(
        /** The forum the run belongs to, used by every cookie call that doesn't name one. */
        private val chanName: String?,
        /** What the command running was granted, as of this run's start. */
        private val grants: Set<CommandsStorage.Grant>,
    ) {
        @JavascriptInterface
        fun call(
            method: String?,
            argsJson: String?,
        ): String =
            try {
                val args = if (argsJson.isNullOrEmpty()) JSONObject() else JSONObject(argsJson)
                val result = dispatch(method.orEmpty(), args)
                JSONObject()
                    .put(KEY_OK, true)
                    .put(KEY_RESULT, result ?: JSONObject.NULL)
                    .toString()
            } catch (e: Exception) {
                // Nothing may escape into the WebView: an exception thrown out of a bridge method is
                // swallowed there, and the script would see an unparseable result instead of the reason.
                JSONObject()
                    .put(KEY_OK, false)
                    .put(KEY_ERROR, e.message ?: e.javaClass.simpleName)
                    .toString()
            }

        /**
         * Set by a write to a setting the app only reads while an activity is being built, and acted
         * on by [applyIfNeeded] once the run is over. Written from the bridge thread, read from the
         * main one.
         */
        @Volatile private var applyPending = false

        /**
         * Rebuilds the screen if the run changed a setting that needs it — the same `recreate()` each
         * settings screen does after changing the same key (see [APPLIED_ON_CREATE]).
         *
         * Deferred to the end of the run rather than done at the write, because a recreate tears down
         * the screen the command was started from: doing it mid-run would cut the script off part way
         * and throw away what it had produced. Several writes in one run still cost one recreate.
         *
         * A run that was given up on never gets here, so a setting it wrote applies whenever the app
         * is next built, as one written outside a command would.
         */
        internal fun applyIfNeeded() {
            if (applyPending) {
                applyPending = false
                ConcurrentUtils.HANDLER.post { ForegroundManager.getInstance().currentActivity?.recreate() }
            }
        }

        /**
         * Refuses a call the command wasn't granted. Only what belongs to the user is behind one: the
         * store holds what a script put there itself, and the forum name is where the script is
         * already running, so neither waits on a grant.
         */
        private fun requireGrant(grant: CommandsStorage.Grant) {
            require(grant in grants) {
                "This command was not granted ${grant.key}: allow it in the command's editor"
            }
        }

        private fun dispatch(
            method: String,
            args: JSONObject,
        ): Any? =
            when (method) {
                METHOD_CHAN -> {
                    chanName
                }

                METHOD_SETTINGS_GET -> {
                    requireGrant(CommandsStorage.Grant.SETTINGS)
                    settingValue(required(args, KEY_KEY))
                }

                METHOD_SETTINGS_HAS -> {
                    requireGrant(CommandsStorage.Grant.SETTINGS)
                    Preferences.prefs.contains(required(args, KEY_KEY))
                }

                METHOD_SETTINGS_ALL -> {
                    requireGrant(CommandsStorage.Grant.SETTINGS)
                    allSettings()
                }

                METHOD_SETTINGS_SET -> {
                    requireGrant(CommandsStorage.Grant.SETTINGS)
                    writeSetting(args)
                }

                METHOD_STORE_GET -> {
                    onMain { CommandsStorage.getInstance().getStoreValue(required(args, KEY_KEY)) }
                }

                METHOD_STORE_SET -> {
                    setStoreValue(args)
                }

                METHOD_STORE_KEYS -> {
                    JSONArray(
                        onMain {
                            CommandsStorage
                                .getInstance()
                                .getStore()
                                .keys
                                .toList()
                        }.orEmpty(),
                    )
                }

                else -> {
                    dispatchChan(method, args)
                }
            }

        /**
         * The calls that are about a forum rather than about the app: its cookies and the address it
         * sees, each behind its own grant. Split off because [dispatch] and this together are one
         * `when` too long for one function, and this is where the seam falls.
         */
        private fun dispatchChan(
            method: String,
            args: JSONObject,
        ): Any? =
            when (method) {
                METHOD_COOKIES_LIST -> {
                    requireGrant(CommandsStorage.Grant.COOKIES)
                    cookies(chan(args))
                }

                METHOD_COOKIES_GET -> {
                    requireGrant(CommandsStorage.Grant.COOKIES)
                    ChanDatabase.getInstance().getCookieChecked(chan(args), required(args, KEY_NAME))
                }

                METHOD_COOKIES_SET -> {
                    requireGrant(CommandsStorage.Grant.COOKIES)
                    setCookie(args)
                }

                METHOD_COOKIES_STATE -> {
                    requireGrant(CommandsStorage.Grant.COOKIES)
                    setCookieState(args)
                }

                METHOD_ADDRESS_GET -> {
                    requireGrant(CommandsStorage.Grant.PROXY)
                    visibleAddress(chan(args))
                }

                METHOD_ADDRESS_ROTATE -> {
                    requireGrant(CommandsStorage.Grant.PROXY)
                    rotateVisibleAddress(chan(args))
                }

                else -> {
                    throw IllegalArgumentException("Unknown app method \"$method\"")
                }
            }

        /**
         * Writes the setting and remembers whether the screen has to be rebuilt for it to show (see
         * [applyIfNeeded]). The note is taken here rather than at the write itself, because only a
         * call that came from a script should move the user's screen.
         */
        private fun writeSetting(args: JSONObject): Any? {
            val key = required(args, KEY_KEY)
            setSetting(key, args.opt(KEY_VALUE))
            if (key in APPLIED_ON_CREATE) {
                applyPending = true
            }
            return null
        }

        /**
         * The forum a call is about: the one it named, or the one the run belongs to. A name no
         * extension answers to is refused — a cookie written under it would be read by nobody, an
         * address asked for under it belongs to no forum, and a typo is the only way to get there.
         */
        private fun chan(args: JSONObject): String {
            val name =
                requireNotNull(optional(args, KEY_CHAN) ?: chanName) {
                    "This command is not running in a forum, so a call about one has to name it"
                }
            require(ChanManager.getInstance().isExistingChanName(name)) { "Unknown forum \"$name\"" }
            return name
        }

        private fun setCookie(args: JSONObject): Any? {
            // A title left out keeps the one the cookie already carries, which is what setCookie reads
            // a null title as; the value is the opposite, null there being what deletes the cookie.
            ChanDatabase.getInstance().setCookie(
                chan(args),
                required(args, KEY_NAME),
                optional(args, KEY_VALUE),
                optional(args, KEY_TITLE),
            )
            return null
        }

        private fun setCookieState(args: JSONObject): Any? {
            val blocked = optionalBoolean(args, KEY_BLOCKED)
            val deleteOnExit = optionalBoolean(args, KEY_DELETE_ON_EXIT)
            // Either one left out means "leave that flag alone", so both left out is a call that would
            // quietly do nothing at all.
            require(blocked != null || deleteOnExit != null) {
                "Nothing to set: pass blocked, deleteOnExit, or both"
            }
            ChanDatabase.getInstance().setCookieState(chan(args), required(args, KEY_NAME), blocked, deleteOnExit)
            return null
        }
    }

    /** The cookies of [chanName], as the "Manage cookies" screen lists them. */
    private fun cookies(chanName: String): JSONArray {
        val array = JSONArray()
        ChanDatabase.getInstance().getCookies(chanName).use { cursor ->
            val item = ChanDatabase.CookieItem()
            while (cursor.moveToNext()) {
                item.update(cursor)
                val obj = JSONObject()
                obj.put(KEY_NAME, item.name ?: JSONObject.NULL)
                obj.put(KEY_VALUE, item.value ?: JSONObject.NULL)
                // Stored as "" for a cookie the forum never gave a display name; null says that more
                // plainly to a script deciding what to show.
                obj.put(KEY_TITLE, item.title?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
                obj.put(KEY_BLOCKED, item.blocked)
                obj.put(KEY_DELETE_ON_EXIT, item.deleteOnExit)
                array.put(obj)
            }
        }
        return array
    }

    /**
     * The address [chanName] is seen at, as `{address, location}`, or `null` where it could not be read
     * at all. It is asked through that forum's own holder, so what comes back is the address that
     * forum's traffic is seen leaving from -- see [VisibleAddress].
     */
    private fun visibleAddress(chanName: String): Any? {
        val chan = Chan.get(chanName)
        val holder = HttpHolder(chan)
        val result = holder.use().use { VisibleAddress.resolve(chan, holder) } ?: return null
        return JSONObject()
            .put(KEY_ADDRESS, result.address)
            .put(KEY_LOCATION, result.location ?: JSONObject.NULL)
    }

    /**
     * Ask the provider for another address on the port [chanName] uses, and answer whether it gave one.
     * Nothing is bought (see [ProxyProvider.rotateVisibleAddress]), so a forum the provider covers but
     * has given no port yet is refused here rather than given one at the account's expense.
     *
     * The provider's own endpoint is asked through the fallback chan, the way the settings screen asks
     * it: a forum proxied by the very port being rotated cannot get in the way of the request that
     * changes it.
     *
     * Each failure is turned into a message of its own, because what the provider throws carries an
     * error item for the UI to show rather than something a script could read.
     */
    private fun rotateVisibleAddress(chanName: String): Any {
        val chan = Chan.get(chanName)
        require(ProxyProvider.coversChan(chan)) {
            "The proxy provider does not cover \"$chanName\": choose that forum in its settings"
        }
        val holder = HttpHolder(Chan.getFallback())
        return try {
            holder.use().use { ProxyProvider.rotateVisibleAddress(holder, chan, buyPorts = false) }
        } catch (e: ProxyProvider.NoPortsException) {
            throw IllegalStateException(
                "The provider has given \"$chanName\" no port yet, and a command may not buy one: " +
                    "check the provider on that forum's settings screen first",
                e,
            )
        } catch (e: ProxyProvider.ServiceException) {
            throw IllegalStateException(e.errorItem.toString(), e)
        } catch (e: HttpException) {
            throw IllegalStateException(e.getErrorItemAndHandle().toString(), e)
        }
    }

    /**
     * Stores the JSON text a script produced, or forgets the key when it sent none. The caps are
     * checked here rather than in [CommandsStorage] because this is the only writer, and because the
     * script is who has to be told: [CommandsStorage] holds the store, but nothing there knows how to
     * refuse a caller.
     */
    private fun setStoreValue(args: JSONObject): Any? {
        val key = required(args, KEY_KEY)
        val json = optional(args, KEY_JSON)
        require(json == null || json.length <= MAX_STORE_VALUE_LENGTH) {
            "Value for \"$key\" is too large (over ${MAX_STORE_VALUE_LENGTH / 1024} KB of JSON)"
        }
        onMain {
            val storage = CommandsStorage.getInstance()
            // A key that already exists may always be rewritten; only growing the store is capped.
            require(json == null || storage.getStoreValue(key) != null || storage.getStoreSize() < MAX_STORE_KEYS) {
                "The store is full ($MAX_STORE_KEYS keys): remove something before adding \"$key\""
            }
            storage.setStoreValue(key, json)
        }
        return null
    }

    /**
     * Runs [action] on the main thread and hands back what it returned. The storage is main-thread
     * state — a serialize clones it from whichever thread asked for one — while bridge calls arrive on
     * the engine's own thread, and the two would otherwise walk the same map at once.
     */
    private fun <T> onMain(action: () -> T): T? = ConcurrentUtils.mainGet { action() }

    private fun settingValue(key: String): Any? = toJson(Preferences.prefs.getAll()[key])

    private fun allSettings(): JSONObject {
        val json = JSONObject()
        for ((key, value) in Preferences.prefs.getAll()) {
            json.put(key, toJson(value) ?: JSONObject.NULL)
        }
        return json
    }

    /** A stored preference as the value a script sees; a string set becomes an array. */
    private fun toJson(value: Any?): Any? =
        when (value) {
            null -> null
            is String, is Boolean, is Number -> value
            is Set<*> -> JSONArray().apply { value.forEach { if (it != null) put(it.toString()) } }
            else -> value.toString()
        }

    /**
     * Stores [value] under [key], or removes the setting when it is `null`. Refused before the editor
     * is opened, so a rejected write leaves nothing half-applied.
     */
    private fun setSetting(
        key: String,
        value: Any?,
    ): Any? {
        val preferences = Preferences.prefs
        val stored = preferences.getAll()[key]
        val remove = value == null || value === JSONObject.NULL
        if (!remove) {
            val kind =
                requireNotNull(kindOf(value)) {
                    "Setting \"$key\" cannot hold that: pass a string, number, boolean, list of strings, or null"
                }
            val storedKind = kindOf(stored)
            if (storedKind != null) {
                require(storedKind == kind) { "Setting \"$key\" holds ${storedKind.description}" }
            }
        }
        preferences.edit().use { editor ->
            when {
                remove -> editor.remove(key)
                value is Boolean -> editor.put(key, value)
                value is Number -> putNumber(editor, key, stored, value)
                value is String -> editor.put(key, value)
                value is JSONArray -> editor.put(key, stringSet(value))
            }
        }
        return null
    }

    /**
     * Stores a number the width the setting is already kept at, widening only for a value that needs
     * it. Nothing has to be exact here — every one of these is read back through [SharedPreferences],
     * which takes any [Number] for a numeric setting — but a setting that was an `int` staying an `int`
     * keeps what the app writes and what a script writes the same shape.
     */
    private fun putNumber(
        editor: SharedPreferences.Editor,
        key: String,
        stored: Any?,
        value: Number,
    ) {
        when {
            stored is Long -> editor.put(key, value.toLong())
            stored is Float || stored is Double -> editor.put(key, value.toFloat())
            stored is Number -> editor.put(key, value.toInt())
            value is Double || value is Float -> editor.put(key, value.toFloat())
            value.toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> editor.put(key, value.toInt())
            else -> editor.put(key, value.toLong())
        }
    }

    private fun stringSet(array: JSONArray): Set<String> {
        val values = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            values.add(array.optString(index))
        }
        return values
    }

    /**
     * What kind of value a setting holds — the four a script can write and the app can read back.
     * `null` for anything else, which on the way in is a value the setting cannot take and on the way
     * out is a setting nothing here should be rewriting.
     */
    private enum class Kind(
        val description: String,
    ) {
        BOOLEAN("a boolean"),
        NUMBER("a number"),
        STRING("a string"),
        LIST("a list of strings"),
    }

    private fun kindOf(value: Any?): Kind? =
        when (value) {
            is Boolean -> Kind.BOOLEAN
            is Number -> Kind.NUMBER
            is String -> Kind.STRING
            is Set<*>, is JSONArray -> Kind.LIST
            else -> null
        }

    /** A required string argument; left out, `null` and empty are all the same mistake. */
    private fun required(
        args: JSONObject,
        key: String,
    ): String = requireNotNull(optional(args, key)) { "\"$key\" is required" }

    /** An optional string argument: `null` when the script left it out or passed `null`. */
    private fun optional(
        args: JSONObject,
        key: String,
    ): String? = if (args.isNull(key)) null else args.optString(key).takeIf { it.isNotEmpty() }

    /** An optional boolean argument: `null` when the script left it out, meaning "leave it alone". */
    private fun optionalBoolean(
        args: JSONObject,
        key: String,
    ): Boolean? = if (args.isNull(key)) null else args.optBoolean(key)
}
