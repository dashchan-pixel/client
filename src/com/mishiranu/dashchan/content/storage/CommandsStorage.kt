package com.mishiranu.dashchan.content.storage

import android.os.Parcel
import android.os.Parcelable
import chan.util.CommonUtils
import chan.util.StringUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * User-defined scripts ("Commands"). A command carries a name, a body of JavaScript [code] and a
 * target ([useIn]) that decides what it operates on: [UseIn.COMMENT] rewrites a posting form's draft —
 * its text and the files attached to it — before it is sent, while [UseIn.THREAD] reads the posts of a
 * thread being viewed and produces the comment and the files to show back for each of them. Like
 * Autohide rules a command can be scoped to specific forums ([chanNames], `null`/empty means every
 * forum) and optionally to a single board ([boardName]).
 *
 * The code itself is executed by [com.mishiranu.dashchan.content.CommandRunner]; this class is only
 * concerned with persistence and scoping.
 *
 * Alongside the commands this file also holds the two things they share: the environment (see
 * [envText]) and the [libraries][LibraryItem] a command can pull into its scope.
 */
class CommandsStorage private constructor() : StorageManager.JsonOrgStorage<CommandsStorage.Snapshot>("commands", 1000, 10000) {
    private val commandItems = ArrayList<CommandItem>()

    /**
     * The defined libraries, in load order — a command loads the ones it selected in this order, so
     * a library may rely on one listed above it. Reordered from the Libraries screen.
     */
    private val libraryItems = ArrayList<LibraryItem>()

    /**
     * The shared environment as the user typed it (see [EnvText] for the format). This text — not
     * the parsed map — is what gets stored, so comments, blank lines and layout survive a round trip
     * through the editor.
     */
    private var envText = ""

    /**
     * A tiny key→value store shared by every command, parsed out of [envText]. The values are
     * injected into each script's scope as `env` (see [com.mishiranu.dashchan.content.CommandRunner]),
     * so scripts can read a user-provided value with `env.CUSTOM_NAME_HERE`. Edited from the Commands
     * screen. Ordered so the editor shows entries in a stable order. Keys are plain JS identifiers.
     */
    private var env: Map<String, String> = emptyMap()

    init {
        startRead()
    }

    fun getItems(): ArrayList<CommandItem> = commandItems

    /** A copy of the shared environment, as an ordered key→value map. */
    fun getEnv(): LinkedHashMap<String, String> = LinkedHashMap(env)

    /** The shared environment as text, for the environment editor. */
    fun getEnvText(): String = envText

    /** Replaces the whole shared environment (used by the environment editor), then serializes. */
    fun setEnvText(newEnvText: String) {
        envText = newEnvText
        env = EnvText.parse(newEnvText)
        serialize()
    }

    /**
     * Snapshot of what gets persisted: the ordered command list, the shared environment text and the
     * ordered library list.
     */
    class Snapshot(
        val items: List<CommandItem>,
        val envText: String,
        val libraries: List<LibraryItem>,
    )

    /**
     * What an imported JSON document turned out to hold: the [commands] plus the [libraries] they came
     * with. The two are kept apart because they are stored apart — a command references a library by
     * name, so importing one means adding any definition the user doesn't have yet (see
     * [addMissingLibraries]).
     */
    class Import(
        val commands: List<CommandItem>,
        val libraries: List<LibraryItem>,
    ) {
        val isEmpty: Boolean
            get() = commands.isEmpty() && libraries.isEmpty()

        companion object {
            val EMPTY = Import(emptyList(), emptyList())
        }
    }

    /** Commands whose scope matches [chanName]/[boardName] and that target [useIn]. */
    fun getAvailable(
        useIn: UseIn,
        chanName: String?,
        boardName: String?,
    ): List<CommandItem> = commandItems.filter { it.useIn == useIn && it.matches(chanName, boardName) }

    override fun onClone(): Snapshot {
        val commandItems = ArrayList<CommandItem>(this.commandItems.size)
        for (commandItem in this.commandItems) {
            commandItems.add(CommandItem(commandItem))
        }
        val libraryItems = ArrayList<LibraryItem>(this.libraryItems.size)
        for (libraryItem in this.libraryItems) {
            libraryItems.add(LibraryItem(libraryItem))
        }
        return Snapshot(commandItems, envText, libraryItems)
    }

    override fun onDeserialize(jsonObject: JSONObject) {
        readEnv(jsonObject)
        // Either list may hold an entry saved before ids existed; both then get one here, and the file
        // is written back once so they stay stable across launches.
        val librariesMigrated = readLibraries(jsonObject)
        val jsonArray = jsonObject.optJSONArray(KEY_DATA)
        val commandsMigrated = jsonArray != null && readCommands(jsonArray)
        if (librariesMigrated || commandsMigrated) {
            serialize()
        }
    }

    private fun readEnv(jsonObject: JSONObject) {
        if (!jsonObject.isNull(KEY_ENV_TEXT)) {
            envText = jsonObject.optString(KEY_ENV_TEXT)
        } else {
            // Stored before the text became the stored form: rebuild it from the key→value object.
            val envObject = jsonObject.optJSONObject(KEY_ENV)
            if (envObject != null) {
                val legacyEnv = LinkedHashMap<String, String>()
                val keys = envObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    legacyEnv[key] = envObject.optString(key)
                }
                envText = EnvText.format(legacyEnv)
            }
        }
        env = EnvText.parse(envText)
    }

    /** Reads the library list into [libraryItems]. Returns true if any entry had to be given an id. */
    private fun readLibraries(jsonObject: JSONObject): Boolean {
        var migrated = false
        val librariesArray = jsonObject.optJSONArray(KEY_LIBRARIES) ?: return false
        for (i in 0 until librariesArray.length()) {
            val item = librariesArray.optJSONObject(i) ?: continue
            val libraryItem = parseLibrary(item) ?: continue
            val storedId = item.optLong(KEY_ID)
            if (storedId != 0L) {
                libraryItem.id = storedId
            } else {
                // parseLibrary has already generated one; keeping it means writing the file back.
                migrated = true
            }
            libraryItems.add(libraryItem)
        }
        return migrated
    }

    /** Reads the command list into [commandItems]. Returns true if any entry had to be given an id. */
    private fun readCommands(jsonArray: JSONArray): Boolean {
        var migrated = false
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i)
            if (item != null) {
                val chanNames = HashSet<String>()
                val chanNamesArray = item.optJSONArray(KEY_CHAN_NAMES)
                if (chanNamesArray != null) {
                    for (j in 0 until chanNamesArray.length()) {
                        val chanName = chanNamesArray.optString(j, null)
                        if (!chanName.isNullOrEmpty()) {
                            chanNames.add(chanName)
                        }
                    }
                }
                val name = item.optString(KEY_NAME)
                val code = item.optString(KEY_CODE)
                val useIn = UseIn.fromKey(item.optString(KEY_USE_IN))
                val autoRun = item.optBoolean(KEY_AUTO_RUN, item.optBoolean(KEY_AUTO_RUN_LEGACY))
                val perPost = item.optBoolean(KEY_PER_POST)
                val boardName = if (item.isNull(KEY_BOARD_NAME)) null else item.optString(KEY_BOARD_NAME)
                val libraries = parseLibraryNames(item)
                val storedId = item.optLong(KEY_ID)
                val id =
                    if (storedId != 0L) {
                        storedId
                    } else {
                        // Legacy command saved before ids existed: assign one and re-persist so it stays
                        // stable across launches.
                        migrated = true
                        CommandItem.generateId()
                    }
                commandItems.add(
                    CommandItem(id, chanNames.ifEmpty { null }, boardName, name, code, useIn, autoRun, perPost, libraries),
                )
            }
        }
        return migrated
    }

    @Throws(JSONException::class)
    override fun onSerialize(data: Snapshot): JSONObject? {
        if (data.items.isEmpty() && data.envText.isEmpty() && data.libraries.isEmpty()) {
            return null
        }
        val jsonObject = JSONObject()
        if (data.items.isNotEmpty()) {
            val jsonArray = JSONArray()
            for (commandItem in data.items) {
                jsonArray.put(serializeCommand(commandItem))
            }
            jsonObject.put(KEY_DATA, jsonArray)
        }
        if (data.envText.isNotEmpty()) {
            jsonObject.put(KEY_ENV_TEXT, data.envText)
        }
        if (data.libraries.isNotEmpty()) {
            val jsonArray = JSONArray()
            for (libraryItem in data.libraries) {
                jsonArray.put(serializeLibrary(libraryItem))
            }
            jsonObject.put(KEY_LIBRARIES, jsonArray)
        }
        return jsonObject
    }

    /**
     * [embedLibraries] writes the referenced libraries as whole definitions rather than as names, so
     * that an exported command carries the code it needs (see [parseImport]). Stored commands only
     * ever reference by name — the definitions live once, in [libraryItems].
     */
    @Throws(JSONException::class)
    private fun serializeCommand(
        commandItem: CommandItem,
        includeId: Boolean = true,
        embedLibraries: Boolean = false,
    ): JSONObject {
        val jsonObject = JSONObject()
        // The id is an internal storage detail (it keeps rows stable across launches); export/sharing
        // JSON omits it, and re-import assigns a fresh one anyway (see parseCommand).
        if (includeId) {
            jsonObject.put(KEY_ID, commandItem.id)
        }
        val chanNames = commandItem.chanNames
        if (!chanNames.isNullOrEmpty()) {
            val chanNamesArray = JSONArray()
            for (chanName in chanNames) {
                chanNamesArray.put(chanName)
            }
            jsonObject.put(KEY_CHAN_NAMES, chanNamesArray)
        }
        AutohideStorage.putJson(jsonObject, KEY_BOARD_NAME, commandItem.boardName)
        AutohideStorage.putJson(jsonObject, KEY_NAME, commandItem.name)
        AutohideStorage.putJson(jsonObject, KEY_CODE, commandItem.code)
        AutohideStorage.putJson(jsonObject, KEY_USE_IN, commandItem.useIn.key)
        AutohideStorage.putJson(jsonObject, KEY_AUTO_RUN, commandItem.autoRun)
        AutohideStorage.putJson(jsonObject, KEY_PER_POST, commandItem.perPost)
        val libraries = commandItem.libraries
        if (!libraries.isNullOrEmpty()) {
            val librariesArray = JSONArray()
            // Storing runs off a snapshot on the serializer thread, so only the export path — which
            // runs on the main thread, for one command the user picked — looks the definitions up.
            for (libraryName in if (embedLibraries) orderLibraryNames(libraries) else libraries) {
                val libraryItem = if (embedLibraries) getLibrary(libraryName) else null
                librariesArray.put(if (libraryItem != null) serializeLibrary(libraryItem, includeId = false) else libraryName)
            }
            jsonObject.put(KEY_LIBRARIES, librariesArray)
        }
        return jsonObject
    }

    @Throws(JSONException::class)
    private fun serializeLibrary(
        libraryItem: LibraryItem,
        includeId: Boolean = true,
    ): JSONObject {
        val jsonObject = JSONObject()
        if (includeId) {
            jsonObject.put(KEY_ID, libraryItem.id)
        }
        jsonObject.put(KEY_NAME, libraryItem.name)
        // The kind is told apart by which key carries the content, so there is no third key to keep
        // in sync with it (see parseLibrary).
        jsonObject.put(libraryItem.kind.key, libraryItem.content)
        return jsonObject
    }

    /**
     * JSON for one command, for export/sharing; re-importable via [parseImport]. The libraries the
     * command uses are embedded whole, so the recipient gets a command that runs rather than one that
     * fails on a library they don't have.
     */
    @Throws(JSONException::class)
    fun commandToJson(commandItem: CommandItem): JSONObject = serializeCommand(commandItem, includeId = false, embedLibraries = true)

    fun add(commandItem: CommandItem) {
        commandItems.add(commandItem)
        serialize()
    }

    /** Replaces the whole ordered list (used to persist a drag-reorder), then serializes. */
    fun replaceAll(newItems: List<CommandItem>) {
        commandItems.clear()
        commandItems.addAll(newItems)
        serialize()
    }

    fun update(
        index: Int,
        commandItem: CommandItem,
    ) {
        commandItems[index] = commandItem
        serialize()
    }

    fun delete(index: Int) {
        commandItems.removeAt(index)
        serialize()
    }

    fun getLibraryItems(): ArrayList<LibraryItem> = libraryItems

    /** The library called [name], or `null` when no such library is defined. */
    fun getLibrary(name: String): LibraryItem? = libraryItems.firstOrNull { it.name == name }

    /**
     * The definitions [names] refers to, in the order [libraryItems] lists them — that order is the
     * load order, so a library may use what one above it declared. A name with no library behind it
     * is skipped here and reported by the loader instead.
     */
    fun librariesFor(names: Set<String>?): List<LibraryItem> = if (names.isNullOrEmpty()) emptyList() else libraryItems.filter { it.name in names }

    /** [names] sorted into load order, with names of undefined libraries kept at the end. */
    private fun orderLibraryNames(names: Set<String>): List<String> {
        val known = libraryItems.mapNotNull { if (it.name in names) it.name else null }
        return known + names.filter { it !in known }
    }

    fun addLibrary(libraryItem: LibraryItem) {
        libraryItems.add(libraryItem)
        serialize()
    }

    /**
     * Replaces the library at [index]. A rename is followed through the commands that referenced the
     * old name, since a command names the libraries it loads — leaving them behind would silently
     * unhook every command that used it.
     */
    fun updateLibrary(
        index: Int,
        libraryItem: LibraryItem,
    ) {
        val oldName = libraryItems[index].name
        libraryItems[index] = libraryItem
        if (oldName != libraryItem.name) {
            renameLibraryReferences(oldName, libraryItem.name)
        }
        serialize()
    }

    fun deleteLibrary(index: Int) {
        libraryItems.removeAt(index)
        serialize()
    }

    /** Replaces the whole ordered library list (used to persist a drag-reorder), then serializes. */
    fun replaceAllLibraries(newItems: List<LibraryItem>) {
        libraryItems.clear()
        libraryItems.addAll(newItems)
        serialize()
    }

    /**
     * Adds the [libraries] that are not defined yet, keeping the existing definition whenever a name
     * is already taken — an import must not overwrite code the user wrote or trusts. Returns how many
     * were added.
     */
    fun addMissingLibraries(libraries: List<LibraryItem>): Int {
        var added = 0
        for (libraryItem in libraries) {
            if (getLibrary(libraryItem.name) == null) {
                libraryItems.add(libraryItem)
                added++
            }
        }
        if (added > 0) {
            serialize()
        }
        return added
    }

    private fun renameLibraryReferences(
        oldName: String,
        newName: String,
    ) {
        for (commandItem in commandItems) {
            val libraries = commandItem.libraries
            if (libraries != null && oldName in libraries) {
                val renamed = LinkedHashSet(libraries)
                renamed.remove(oldName)
                renamed.add(newName)
                commandItem.libraries = renamed
            }
        }
    }

    enum class UseIn(
        val key: String,
    ) {
        /**
         * The posting form's draft — its comment and its attachments. Shown as "Draft"; the stored key
         * is still `comment`, which is all it could reach when it was named that, so a command written
         * or exported before the attachments existed keeps working unchanged.
         */
        COMMENT("comment"),

        /**
         * The posts of the thread being viewed — their comments and their attached files, each post
         * showing what the command hands back for it until the thread is left or read again.
         */
        THREAD("thread"),
        ;

        companion object {
            fun fromKey(key: String?): UseIn = entries.firstOrNull { it.key == key } ?: COMMENT
        }
    }

    class CommandItem : Parcelable {
        /** Stable unique identity, kept across edits and reorders (used for drag-drop, edit lookup). */
        @JvmField var id: Long = 0

        @JvmField var chanNames: Set<String>? = null

        @JvmField var boardName: String? = null

        @JvmField var name: String? = null

        @JvmField var code: String? = null

        @JvmField var useIn: UseIn = UseIn.COMMENT

        /**
         * When true the command runs automatically rather than on demand from a menu: a
         * [UseIn.COMMENT] command runs before sending, a [UseIn.THREAD] command runs when the thread
         * is opened.
         */
        @JvmField var autoRun = false

        /**
         * [UseIn.THREAD] only: run the body once per post rather than once per thread. The body is then
         * the `process` of a fold over the thread's posts — it takes a single `post` and returns just
         * that post's replacement — which trades n+1 engine calls for not having to build the result
         * map by hand. See [com.mishiranu.dashchan.content.CommandRunner.runThread].
         */
        @JvmField var perPost = false

        /**
         * Names of the [libraries][LibraryItem] loaded into this command's scope before its body runs
         * (`null`/empty means none). They are named rather than pointed at by id so that a command
         * survives being exported and imported next to its libraries; a rename is followed through by
         * [renameLibraryReferences].
         */
        @JvmField var libraries: Set<String>? = null

        constructor()

        constructor(commandItem: CommandItem) : this(
            commandItem.id,
            commandItem.chanNames,
            commandItem.boardName,
            commandItem.name,
            commandItem.code,
            commandItem.useIn,
            commandItem.autoRun,
            commandItem.perPost,
            commandItem.libraries,
        )

        constructor(
            id: Long,
            chanNames: Set<String>?,
            boardName: String?,
            name: String?,
            code: String?,
            useIn: UseIn,
            autoRun: Boolean,
            perPost: Boolean,
            libraries: Set<String>? = null,
        ) {
            this.id = id
            update(chanNames, boardName, name, code, useIn, autoRun, perPost, libraries)
        }

        fun update(
            chanNames: Set<String>?,
            boardName: String?,
            name: String?,
            code: String?,
            useIn: UseIn,
            autoRun: Boolean,
            perPost: Boolean,
            libraries: Set<String>? = null,
        ) {
            this.chanNames = chanNames
            this.boardName = boardName
            this.name = StringUtils.emptyIfNull(name)
            this.code = StringUtils.emptyIfNull(code)
            this.useIn = useIn
            this.autoRun = autoRun
            this.perPost = perPost
            this.libraries = libraries
        }

        /** True if this command should be offered for the given forum/board. */
        fun matches(
            chanName: String?,
            boardName: String?,
        ): Boolean {
            val chanNames = this.chanNames
            if (!chanNames.isNullOrEmpty() && (chanName == null || chanName !in chanNames)) {
                return false
            }
            val requiredBoard = this.boardName
            if (!requiredBoard.isNullOrEmpty() && requiredBoard != boardName) {
                return false
            }
            return true
        }

        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeLong(id)
            dest.writeStringArray(CommonUtils.toArray(chanNames, String::class.java))
            dest.writeString(boardName)
            dest.writeString(name)
            dest.writeString(code)
            dest.writeString(useIn.key)
            dest.writeByte(if (autoRun) 1.toByte() else 0.toByte())
            dest.writeByte(if (perPost) 1.toByte() else 0.toByte())
            dest.writeStringArray(CommonUtils.toArray(libraries, String::class.java))
        }

        companion object {
            /** A fresh non-zero identity for a newly created command. */
            fun generateId(): Long {
                var id = 0L
                while (id == 0L) {
                    id =
                        java.util.concurrent.ThreadLocalRandom
                            .current()
                            .nextLong()
                }
                return id
            }

            @JvmField
            val CREATOR =
                object : Parcelable.Creator<CommandItem> {
                    override fun createFromParcel(source: Parcel): CommandItem {
                        val commandItem = CommandItem()
                        commandItem.id = source.readLong()
                        val chanNames = source.createStringArray()
                        if (chanNames != null) {
                            commandItem.chanNames = HashSet(chanNames.asList())
                        }
                        commandItem.boardName = source.readString()
                        commandItem.name = source.readString()
                        commandItem.code = source.readString()
                        commandItem.useIn = UseIn.fromKey(source.readString())
                        commandItem.autoRun = source.readByte().toInt() != 0
                        commandItem.perPost = source.readByte().toInt() != 0
                        val libraries = source.createStringArray()
                        if (libraries != null) {
                            commandItem.libraries = LinkedHashSet(libraries.asList())
                        }
                        return commandItem
                    }

                    override fun newArray(size: Int): Array<CommandItem?> = arrayOfNulls(size)
                }
        }
    }

    /**
     * A piece of JavaScript shared by commands: either a snippet the user keeps here
     * ([Kind.SNIPPET]) or the address of a script kept elsewhere ([Kind.URL], downloaded and cached by
     * [com.mishiranu.dashchan.content.CommandLibraries]). A command lists the libraries it wants by
     * [name] and they are loaded, in the order the Libraries screen shows them, into the scope its
     * body runs in — so whatever a library declares at its top level (a function, a `const`, a class)
     * the body can just use.
     */
    class LibraryItem : Parcelable {
        /** Stable unique identity, kept across edits and reorders. Commands refer to [name] instead. */
        @JvmField var id: Long = 0

        /** Unique, non-empty; this is what a command stores to say it wants this library. */
        @JvmField var name: String = ""

        @JvmField var kind: Kind = Kind.SNIPPET

        /** The JavaScript itself for a [Kind.SNIPPET], the address to download for a [Kind.URL]. */
        @JvmField var content: String = ""

        constructor()

        constructor(libraryItem: LibraryItem) : this(
            libraryItem.id,
            libraryItem.name,
            libraryItem.kind,
            libraryItem.content,
        )

        constructor(
            id: Long,
            name: String?,
            kind: Kind,
            content: String?,
        ) {
            this.id = id
            this.name = StringUtils.emptyIfNull(name)
            this.kind = kind
            this.content = StringUtils.emptyIfNull(content)
        }

        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeLong(id)
            dest.writeString(name)
            dest.writeString(kind.key)
            dest.writeString(content)
        }

        /** Where a library's code comes from. The [key] is also the JSON key its content is stored under. */
        enum class Kind(
            val key: String,
        ) {
            SNIPPET(KEY_CODE),
            URL(KEY_URL),
            ;

            companion object {
                fun fromKey(key: String?): Kind = entries.firstOrNull { it.key == key } ?: SNIPPET
            }
        }

        companion object {
            /** A fresh non-zero identity for a newly created library. */
            fun generateId(): Long = CommandItem.generateId()

            @JvmField
            val CREATOR =
                object : Parcelable.Creator<LibraryItem> {
                    override fun createFromParcel(source: Parcel): LibraryItem {
                        val libraryItem = LibraryItem()
                        libraryItem.id = source.readLong()
                        libraryItem.name = StringUtils.emptyIfNull(source.readString())
                        libraryItem.kind = Kind.fromKey(source.readString())
                        libraryItem.content = StringUtils.emptyIfNull(source.readString())
                        return libraryItem
                    }

                    override fun newArray(size: Int): Array<LibraryItem?> = arrayOfNulls(size)
                }
        }
    }

    companion object {
        private const val KEY_DATA = "data"
        private const val KEY_ENV_TEXT = "envText"

        /** Legacy key for [KEY_ENV_TEXT] (the environment was stored as a key→value object before). */
        private const val KEY_ENV = "env"
        private const val KEY_ID = "id"
        private const val KEY_CHAN_NAMES = "chanNames"
        private const val KEY_BOARD_NAME = "boardName"
        private const val KEY_NAME = "name"
        private const val KEY_CODE = "code"
        private const val KEY_USE_IN = "useIn"
        private const val KEY_AUTO_RUN = "autoRun"
        private const val KEY_PER_POST = "perPost"
        private const val KEY_URL = "url"

        /**
         * The library list. At the top level of the file it holds the definitions; inside a command it
         * holds the libraries that command loads — as names when stored (the definitions are kept once,
         * at the top level) or as whole definitions when the command was exported on its own.
         */
        private const val KEY_LIBRARIES = "libraries"

        /** Legacy key for [KEY_AUTO_RUN] (the flag was named "runOnSend" before). Read-only fallback. */
        private const val KEY_AUTO_RUN_LEGACY = "runOnSend"

        private val INSTANCE = CommandsStorage()

        @JvmStatic
        fun getInstance(): CommandsStorage = INSTANCE

        /**
         * Parses an imported JSON document — a single command object, a full export
         * (`{ "data": [ … ] }`), or either of those with libraries alongside. Every imported command
         * and library is given a fresh id so it can't collide with an existing one. Returns what was
         * found (empty if nothing valid was).
         */
        fun parseImport(jsonObject: JSONObject): Import {
            val commands = ArrayList<CommandItem>()
            // Keyed by name, which is what a command references; the first definition of a name wins.
            val libraries = LinkedHashMap<String, LibraryItem>()
            val array = jsonObject.optJSONArray(KEY_DATA)
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    parseCommand(item)?.let(commands::add)
                    collectLibraries(item, libraries)
                }
            } else {
                parseCommand(jsonObject)?.let(commands::add)
            }
            // A whole-storage export keeps its libraries at the top level; a single exported command
            // carries them inside itself, which the loop above already picked up.
            collectLibraries(jsonObject, libraries)
            return Import(commands, ArrayList(libraries.values))
        }

        /**
         * Detects command JSON embedded in arbitrary post text (mirrors
         * [com.mishiranu.dashchan.widget.ThemeEngine.fastParseThemeFromText]): a cheap key probe gates
         * the parse, then the outermost `{ … }` is extracted and handed to [parseImport]. A command's
         * body ([KEY_CODE]) alone is too generic, so a scope/run flag key is also required. Returns
         * what the text carries, or [Import.EMPTY] when it carries no command.
         */
        fun fastParseImportFromText(text: String): Import {
            if (text.contains("\"$KEY_CODE\"") &&
                (
                    text.contains("\"$KEY_USE_IN\"") ||
                        text.contains("\"$KEY_AUTO_RUN\"") ||
                        text.contains("\"$KEY_AUTO_RUN_LEGACY\"")
                )
            ) {
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}') + 1
                if (start >= 0 && end > start) {
                    val jsonObject =
                        try {
                            JSONObject(text.substring(start, end))
                        } catch (e: JSONException) {
                            e.printStackTrace()
                            null
                        }
                    if (jsonObject != null) {
                        val import = parseImport(jsonObject)
                        // Only a command makes this text an offer to add one; libraries on their own
                        // are of no use to the button that asks.
                        if (import.commands.isNotEmpty()) {
                            return import
                        }
                    }
                }
            }
            return Import.EMPTY
        }

        /** Adds every library definition found in [item]'s library list to [out], first name winning. */
        private fun collectLibraries(
            item: JSONObject,
            out: LinkedHashMap<String, LibraryItem>,
        ) {
            val array = item.optJSONArray(KEY_LIBRARIES) ?: return
            for (i in 0 until array.length()) {
                val libraryObject = array.optJSONObject(i) ?: continue
                val libraryItem = parseLibrary(libraryObject) ?: continue
                out.putIfAbsent(libraryItem.name, libraryItem)
            }
        }

        /**
         * Reads one library definition. The kind is told by which key carries the content, so nothing
         * has to agree with a separate type field. Returns `null` for an entry with no name or no
         * content — there would be nothing to reference or to run.
         */
        private fun parseLibrary(item: JSONObject): LibraryItem? {
            val name = item.optString(KEY_NAME)
            if (name.isEmpty()) {
                return null
            }
            val url = if (item.isNull(KEY_URL)) "" else item.optString(KEY_URL)
            val kind = if (url.isNotEmpty()) LibraryItem.Kind.URL else LibraryItem.Kind.SNIPPET
            val content = if (kind == LibraryItem.Kind.URL) url else item.optString(KEY_CODE)
            if (content.isEmpty()) {
                return null
            }
            return LibraryItem(LibraryItem.generateId(), name, kind, content)
        }

        /**
         * The libraries [item] says it loads. Entries may be names (how a stored command references
         * them) or whole definitions (how an exported one carries them); either way what a command
         * keeps is the name.
         */
        private fun parseLibraryNames(item: JSONObject): Set<String>? {
            val array = item.optJSONArray(KEY_LIBRARIES) ?: return null
            val names = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                val libraryObject = array.optJSONObject(i)
                val name = if (libraryObject != null) libraryObject.optString(KEY_NAME) else array.optString(i, null)
                if (!name.isNullOrEmpty()) {
                    names.add(name)
                }
            }
            return names.ifEmpty { null }
        }

        private fun parseCommand(item: JSONObject): CommandItem? {
            val code = item.optString(KEY_CODE)
            if (code.isEmpty()) {
                // Without a body there is nothing to run; treat as not a command.
                return null
            }
            val chanNames = HashSet<String>()
            val chanNamesArray = item.optJSONArray(KEY_CHAN_NAMES)
            if (chanNamesArray != null) {
                for (j in 0 until chanNamesArray.length()) {
                    val chanName = chanNamesArray.optString(j, null)
                    if (!chanName.isNullOrEmpty()) {
                        chanNames.add(chanName)
                    }
                }
            }
            val name = item.optString(KEY_NAME)
            val useIn = UseIn.fromKey(item.optString(KEY_USE_IN))
            val autoRun = item.optBoolean(KEY_AUTO_RUN, item.optBoolean(KEY_AUTO_RUN_LEGACY))
            val perPost = item.optBoolean(KEY_PER_POST)
            val boardName = if (item.isNull(KEY_BOARD_NAME)) null else item.optString(KEY_BOARD_NAME)
            return CommandItem(
                CommandItem.generateId(),
                chanNames.ifEmpty { null },
                boardName,
                name,
                code,
                useIn,
                autoRun,
                perPost,
                parseLibraryNames(item),
            )
        }
    }
}
