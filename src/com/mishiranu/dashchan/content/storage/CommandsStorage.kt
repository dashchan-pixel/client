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
 * target ([useIn]) that decides what it operates on: [UseIn.COMMENT] transforms the text of a posting
 * form before it is sent, while [UseIn.THREAD] reads the posts of a thread being viewed and produces
 * some text to show back. Like Autohide rules a command can be scoped to specific forums ([chanNames],
 * `null`/empty means every forum) and optionally to a single board ([boardName]).
 *
 * The code itself is executed by [com.mishiranu.dashchan.content.CommandRunner]; this class is only
 * concerned with persistence and scoping.
 */
class CommandsStorage private constructor() : StorageManager.JsonOrgStorage<CommandsStorage.Snapshot>("commands", 1000, 10000) {
    private val commandItems = ArrayList<CommandItem>()

    /**
     * A tiny key→value store shared by every command. The values are injected into each script's
     * scope as `env` (see [com.mishiranu.dashchan.content.CommandRunner]), so scripts can read a
     * user-provided value with `env.CUSTOM_NAME_HERE`. Edited from the Commands screen. Ordered so
     * the editor shows entries in a stable order. Keys are plain JS identifiers.
     */
    private val env = LinkedHashMap<String, String>()

    init {
        startRead()
    }

    fun getItems(): ArrayList<CommandItem> = commandItems

    /** A copy of the shared environment, as an ordered key→value map. */
    fun getEnv(): LinkedHashMap<String, String> = LinkedHashMap(env)

    /** Replaces the whole shared environment (used by the environment editor), then serializes. */
    fun setEnv(newEnv: Map<String, String>) {
        env.clear()
        env.putAll(newEnv)
        serialize()
    }

    /** Snapshot of what gets persisted: the ordered command list plus the shared environment. */
    class Snapshot(
        val items: List<CommandItem>,
        val env: Map<String, String>,
    )

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
        return Snapshot(commandItems, LinkedHashMap(env))
    }

    override fun onDeserialize(jsonObject: JSONObject) {
        val envObject = jsonObject.optJSONObject(KEY_ENV)
        if (envObject != null) {
            val keys = envObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                env[key] = envObject.optString(key)
            }
        }
        val jsonArray = jsonObject.optJSONArray(KEY_DATA) ?: return
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
                    CommandItem(id, chanNames.ifEmpty { null }, boardName, name, code, useIn, autoRun, perPost),
                )
            }
        }
        if (migrated) {
            serialize()
        }
    }

    @Throws(JSONException::class)
    override fun onSerialize(data: Snapshot): JSONObject? {
        if (data.items.isEmpty() && data.env.isEmpty()) {
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
        if (data.env.isNotEmpty()) {
            val envObject = JSONObject()
            for ((key, value) in data.env) {
                envObject.put(key, value)
            }
            jsonObject.put(KEY_ENV, envObject)
        }
        return jsonObject
    }

    @Throws(JSONException::class)
    private fun serializeCommand(
        commandItem: CommandItem,
        includeId: Boolean = true,
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
        return jsonObject
    }

    /** JSON for one command, for export/sharing; re-importable via [parseCommands]. */
    @Throws(JSONException::class)
    fun commandToJson(commandItem: CommandItem): JSONObject = serializeCommand(commandItem, includeId = false)

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

    enum class UseIn(
        val key: String,
    ) {
        COMMENT("comment"),
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
         * the `process` of a fold over the thread's posts — it takes a single `post` and returns that
         * post's replacement — which trades n+1 engine calls for not having to build the result map by
         * hand. See [com.mishiranu.dashchan.content.CommandRunner.runThread].
         */
        @JvmField var perPost = false

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
        ) {
            this.id = id
            update(chanNames, boardName, name, code, useIn, autoRun, perPost)
        }

        fun update(
            chanNames: Set<String>?,
            boardName: String?,
            name: String?,
            code: String?,
            useIn: UseIn,
            autoRun: Boolean,
            perPost: Boolean,
        ) {
            this.chanNames = chanNames
            this.boardName = boardName
            this.name = StringUtils.emptyIfNull(name)
            this.code = StringUtils.emptyIfNull(code)
            this.useIn = useIn
            this.autoRun = autoRun
            this.perPost = perPost
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
                        return commandItem
                    }

                    override fun newArray(size: Int): Array<CommandItem?> = arrayOfNulls(size)
                }
        }
    }

    companion object {
        private const val KEY_DATA = "data"
        private const val KEY_ENV = "env"
        private const val KEY_ID = "id"
        private const val KEY_CHAN_NAMES = "chanNames"
        private const val KEY_BOARD_NAME = "boardName"
        private const val KEY_NAME = "name"
        private const val KEY_CODE = "code"
        private const val KEY_USE_IN = "useIn"
        private const val KEY_AUTO_RUN = "autoRun"
        private const val KEY_PER_POST = "perPost"

        /** Legacy key for [KEY_AUTO_RUN] (the flag was named "runOnSend" before). Read-only fallback. */
        private const val KEY_AUTO_RUN_LEGACY = "runOnSend"

        private val INSTANCE = CommandsStorage()

        @JvmStatic
        fun getInstance(): CommandsStorage = INSTANCE

        /**
         * Parses commands from an imported JSON document — either a single command object or a full
         * export (`{ "data": [ … ] }`). Every imported command is given a fresh [CommandItem.generateId]
         * id so it can't collide with existing ones. Returns the parsed commands (empty if none valid).
         */
        fun parseCommands(jsonObject: JSONObject): List<CommandItem> {
            val result = ArrayList<CommandItem>()
            val array = jsonObject.optJSONArray(KEY_DATA)
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { item -> parseCommand(item)?.let(result::add) }
                }
            } else {
                parseCommand(jsonObject)?.let(result::add)
            }
            return result
        }

        /**
         * Detects command JSON embedded in arbitrary post text (mirrors
         * [com.mishiranu.dashchan.widget.ThemeEngine.fastParseThemeFromText]): a cheap key probe gates
         * the parse, then the outermost `{ … }` is extracted and handed to [parseCommands]. A command's
         * body ([KEY_CODE]) alone is too generic, so a scope/run flag key is also required. Returns the
         * parsed commands, or an empty list when the text carries none.
         */
        fun fastParseCommandsFromText(text: String): List<CommandItem> {
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
                        return parseCommands(jsonObject)
                    }
                }
            }
            return emptyList()
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
            )
        }
    }
}
