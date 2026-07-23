package com.mishiranu.dashchan.content.storage

import android.os.Parcel
import android.os.Parcelable
import chan.util.CommonUtils
import chan.util.StringUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * User-defined scripts ("Commands") that transform the content of a posting form before it is sent.
 * A command carries a name, a body of JavaScript [code] and a target ([useIn]) that decides which
 * field it operates on — currently only [UseIn.COMMENT]. Like Autohide rules a command can be scoped
 * to specific forums ([chanNames], `null`/empty means every forum) and optionally to a single board
 * ([boardName]).
 *
 * The code itself is executed by [com.mishiranu.dashchan.content.CommandRunner]; this class is only
 * concerned with persistence and scoping.
 */
class CommandsStorage private constructor() : StorageManager.JsonOrgStorage<List<CommandsStorage.CommandItem>>("commands", 1000, 10000) {
    private val commandItems = ArrayList<CommandItem>()

    init {
        startRead()
    }

    fun getItems(): ArrayList<CommandItem> = commandItems

    /** Commands whose scope matches [chanName]/[boardName] and that target [useIn]. */
    fun getAvailable(
        useIn: UseIn,
        chanName: String?,
        boardName: String?,
    ): List<CommandItem> = commandItems.filter { it.useIn == useIn && it.matches(chanName, boardName) }

    override fun onClone(): List<CommandItem> {
        val commandItems = ArrayList<CommandItem>(this.commandItems.size)
        for (commandItem in this.commandItems) {
            commandItems.add(CommandItem(commandItem))
        }
        return commandItems
    }

    override fun onDeserialize(jsonObject: JSONObject) {
        val jsonArray = jsonObject.optJSONArray(KEY_DATA) ?: return
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
                val runOnSend = item.optBoolean(KEY_RUN_ON_SEND)
                val boardName = if (item.isNull(KEY_BOARD_NAME)) null else item.optString(KEY_BOARD_NAME)
                commandItems.add(
                    CommandItem(chanNames.ifEmpty { null }, boardName, name, code, useIn, runOnSend),
                )
            }
        }
    }

    @Throws(JSONException::class)
    override fun onSerialize(data: List<CommandItem>): JSONObject? {
        if (data.isNotEmpty()) {
            val jsonArray = JSONArray()
            for (commandItem in data) {
                val jsonObject = JSONObject()
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
                AutohideStorage.putJson(jsonObject, KEY_RUN_ON_SEND, commandItem.runOnSend)
                jsonArray.put(jsonObject)
            }
            val jsonObject = JSONObject()
            jsonObject.put(KEY_DATA, jsonArray)
            return jsonObject
        }
        return null
    }

    fun add(commandItem: CommandItem) {
        commandItems.add(commandItem)
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
        ;

        companion object {
            fun fromKey(key: String?): UseIn = entries.firstOrNull { it.key == key } ?: COMMENT
        }
    }

    class CommandItem : Parcelable {
        @JvmField var chanNames: Set<String>? = null

        @JvmField var boardName: String? = null

        @JvmField var name: String? = null

        @JvmField var code: String? = null

        @JvmField var useIn: UseIn = UseIn.COMMENT

        /** When true the command runs automatically before sending, instead of from the ⌘ menu. */
        @JvmField var runOnSend = false

        constructor()

        constructor(commandItem: CommandItem) : this(
            commandItem.chanNames,
            commandItem.boardName,
            commandItem.name,
            commandItem.code,
            commandItem.useIn,
            commandItem.runOnSend,
        )

        constructor(
            chanNames: Set<String>?,
            boardName: String?,
            name: String?,
            code: String?,
            useIn: UseIn,
            runOnSend: Boolean,
        ) {
            update(chanNames, boardName, name, code, useIn, runOnSend)
        }

        fun update(
            chanNames: Set<String>?,
            boardName: String?,
            name: String?,
            code: String?,
            useIn: UseIn,
            runOnSend: Boolean,
        ) {
            this.chanNames = chanNames
            this.boardName = boardName
            this.name = StringUtils.emptyIfNull(name)
            this.code = StringUtils.emptyIfNull(code)
            this.useIn = useIn
            this.runOnSend = runOnSend
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
            dest.writeStringArray(CommonUtils.toArray(chanNames, String::class.java))
            dest.writeString(boardName)
            dest.writeString(name)
            dest.writeString(code)
            dest.writeString(useIn.key)
            dest.writeByte(if (runOnSend) 1.toByte() else 0.toByte())
        }

        companion object {
            @JvmField
            val CREATOR =
                object : Parcelable.Creator<CommandItem> {
                    override fun createFromParcel(source: Parcel): CommandItem {
                        val commandItem = CommandItem()
                        val chanNames = source.createStringArray()
                        if (chanNames != null) {
                            commandItem.chanNames = HashSet(chanNames.asList())
                        }
                        commandItem.boardName = source.readString()
                        commandItem.name = source.readString()
                        commandItem.code = source.readString()
                        commandItem.useIn = UseIn.fromKey(source.readString())
                        commandItem.runOnSend = source.readByte().toInt() != 0
                        return commandItem
                    }

                    override fun newArray(size: Int): Array<CommandItem?> = arrayOfNulls(size)
                }
        }
    }

    companion object {
        private const val KEY_DATA = "data"
        private const val KEY_CHAN_NAMES = "chanNames"
        private const val KEY_BOARD_NAME = "boardName"
        private const val KEY_NAME = "name"
        private const val KEY_CODE = "code"
        private const val KEY_USE_IN = "useIn"
        private const val KEY_RUN_ON_SEND = "runOnSend"

        private val INSTANCE = CommandsStorage()

        @JvmStatic
        fun getInstance(): CommandsStorage = INSTANCE
    }
}
