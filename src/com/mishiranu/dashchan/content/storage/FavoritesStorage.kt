package com.mishiranu.dashchan.content.storage

import chan.content.ChanManager
import chan.text.JsonSerial
import chan.text.ParseException
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.util.WeakObservable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FavoritesStorage private constructor() :
		StorageManager.Storage<List<FavoritesStorage.FavoriteItem>>("favorites", 2000, 10000) {
	private val favoriteItemsMap = HashMap<String, FavoriteItem>()
	private val favoriteItemsList = ArrayList<FavoriteItem>()

	init {
		startRead()
	}

	override fun onClone(): List<FavoriteItem> {
		val favoriteItems = ArrayList<FavoriteItem>(favoriteItemsList.size)
		for (favoriteItem in favoriteItemsList) {
			favoriteItems.add(FavoriteItem(favoriteItem))
		}
		return favoriteItems
	}

	@Throws(IOException::class)
	override fun onRead(input: InputStream) {
		try {
			val reader = JsonSerial.reader(input)
			reader.startObject()
			while (!reader.endStruct()) {
				when (reader.nextName()) {
					KEY_DATA -> {
						reader.startArray()
						while (!reader.endStruct()) {
							var chanName: String? = null
							var boardName: String? = null
							var threadNumber: String? = null
							var title: String? = null
							var modifiedTitle = false
							var watcherEnabled = false
							reader.startObject()
							while (!reader.endStruct()) {
								when (reader.nextName()) {
									KEY_CHAN_NAME -> chanName = reader.nextString()
									KEY_BOARD_NAME -> boardName = reader.nextString()
									KEY_THREAD_NUMBER -> threadNumber = reader.nextString()
									KEY_TITLE -> title = reader.nextString()
									KEY_MODIFIED_TITLE -> modifiedTitle = reader.nextBoolean()
									KEY_WATCHER_ENABLED -> watcherEnabled = reader.nextBoolean()
									else -> reader.skip()
								}
							}
							val favoriteItem = FavoriteItem(chanName!!, boardName, threadNumber,
									title, modifiedTitle, watcherEnabled)
							favoriteItemsMap[makeKey(chanName, boardName, threadNumber)] = favoriteItem
							favoriteItemsList.add(favoriteItem)
						}
					}
					else -> reader.skip()
				}
			}
		} catch (e: ParseException) {
			throw IOException(e)
		}
	}

	@Throws(IOException::class)
	override fun onWrite(data: List<FavoriteItem>, output: OutputStream) {
		val writer = JsonSerial.writer(output)
		writer.startObject()
		writer.name(KEY_DATA)
		writer.startArray()
		for (favoriteItem in data) {
			writer.startObject()
			writer.name(KEY_CHAN_NAME)
			writer.value(favoriteItem.chanName)
			if (!favoriteItem.boardName.isNullOrEmpty()) {
				writer.name(KEY_BOARD_NAME)
				writer.value(favoriteItem.boardName)
			}
			if (!favoriteItem.threadNumber.isNullOrEmpty()) {
				writer.name(KEY_THREAD_NUMBER)
				writer.value(favoriteItem.threadNumber)
			}
			val title = favoriteItem.title
			if (!title.isNullOrEmpty()) {
				writer.name(KEY_TITLE)
				writer.value(title)
			}
			writer.name(KEY_MODIFIED_TITLE)
			writer.value(favoriteItem.modifiedTitle)
			writer.name(KEY_WATCHER_ENABLED)
			writer.value(favoriteItem.watcherEnabled)
			writer.endObject()
		}
		writer.endArray()
		writer.endObject()
		writer.flush()
	}

	private val observable = WeakObservable<Observer>()

	enum class Action { ADD, REMOVE, MODIFY_TITLE, WATCHER_ENABLE, WATCHER_DISABLE }

	fun interface Observer {
		fun onFavoritesUpdate(favoriteItem: FavoriteItem, action: Action)
	}

	fun getObservable(): WeakObservable<Observer> = observable

	fun canSortManually(): Boolean {
		return Preferences.favoritesOrder != Preferences.FavoritesOrder.TITLE
	}

	private fun notifyFavoritesUpdate(favoriteItem: FavoriteItem, action: Action) {
		for (observer in observable) {
			observer.onFavoritesUpdate(favoriteItem, action)
		}
	}

	fun getFavorite(chanName: String?, boardName: String?, threadNumber: String?): FavoriteItem? {
		return favoriteItemsMap[makeKey(chanName, boardName, threadNumber)]
	}

	fun hasFavorite(chanName: String?, boardName: String?, threadNumber: String?): Boolean {
		return getFavorite(chanName, boardName, threadNumber) != null
	}

	private fun sortIfNeededInternal(): Boolean {
		if (!canSortManually()) {
			when (Preferences.favoritesOrder) {
				Preferences.FavoritesOrder.TITLE -> {
					favoriteItemsList.sortWith(titlesComparator)
					return true
				}
				else -> {}
			}
		}
		return false
	}

	fun sortIfNeeded() {
		if (sortIfNeededInternal()) {
			serialize()
		}
	}

	fun add(favoriteItem: FavoriteItem) {
		if (!hasFavorite(favoriteItem.chanName, favoriteItem.boardName, favoriteItem.threadNumber)) {
			favoriteItemsMap[makeKey(favoriteItem)] = favoriteItem
			val order = Preferences.favoritesOrder
			if (order == Preferences.FavoritesOrder.DATE_DESC) {
				favoriteItemsList.add(0, favoriteItem)
			} else {
				favoriteItemsList.add(favoriteItem)
			}
			sortIfNeededInternal()
			notifyFavoritesUpdate(favoriteItem, Action.ADD)
			if (favoriteItem.threadNumber != null && favoriteItem.watcherEnabled) {
				notifyFavoritesUpdate(favoriteItem, Action.WATCHER_ENABLE)
			}
			serialize()
		}
	}

	fun add(chanName: String, boardName: String?, threadNumber: String,
			title: String?, allowWatcherEnabled: Boolean) {
		val favoriteItem = FavoriteItem(chanName, boardName, threadNumber)
		favoriteItem.title = title
		favoriteItem.watcherEnabled = allowWatcherEnabled && Preferences.isWatcherWatchInitially
		add(favoriteItem)
	}

	fun add(chanName: String, boardName: String?) {
		add(FavoriteItem(chanName, boardName, null))
	}

	fun moveAfter(favoriteItem: FavoriteItem, afterFavoriteItem: FavoriteItem?) {
		if (canSortManually() && favoriteItemsList.remove(favoriteItem)) {
			// A null afterFavoriteItem (e.g. dragged to the top) inserts at the start, matching the
			// original Java where indexOf(null) returned -1.
			val index = if (afterFavoriteItem != null) favoriteItemsList.indexOf(afterFavoriteItem) + 1 else 0
			favoriteItemsList.add(index, favoriteItem)
			serialize()
		}
	}

	fun updateTitle(chanName: String?, boardName: String?, threadNumber: String?,
			title: String?, fromUser: Boolean) {
		val empty = title.isNullOrEmpty()
		if (!empty || fromUser) {
			val newTitle = if (empty) null else title
			val favoriteItem = getFavorite(chanName, boardName, threadNumber)
			if (favoriteItem != null && (fromUser || !favoriteItem.modifiedTitle)) {
				val titleChanged = !CommonUtils.equals(favoriteItem.title, newTitle)
				var stateChanged = false
				if (titleChanged) {
					favoriteItem.title = newTitle
					stateChanged = true
				}
				if (fromUser) {
					val modifiedTitle = !empty
					// Must not clobber titleChanged: renaming an already-renamed favorite leaves
					// modifiedTitle true, so the plain assignment the Java original used dropped
					// the rename — never sorted, never notified, never serialized.
					stateChanged = stateChanged || favoriteItem.modifiedTitle != modifiedTitle
					favoriteItem.modifiedTitle = modifiedTitle
				}
				if (stateChanged) {
					if (titleChanged) {
						sortIfNeededInternal()
					}
					notifyFavoritesUpdate(favoriteItem, Action.MODIFY_TITLE)
					serialize()
				}
			}
		}
	}

	fun setWatcherEnabled(chanName: String?, boardName: String?, threadNumber: String?,
			enabled: Boolean?) {
		val favoriteItem = getFavorite(chanName, boardName, threadNumber)
		if (favoriteItem != null) {
			val changed: Boolean
			if (enabled != null) {
				changed = favoriteItem.watcherEnabled != enabled
				if (changed) {
					favoriteItem.watcherEnabled = enabled
				}
			} else {
				favoriteItem.watcherEnabled = !favoriteItem.watcherEnabled
				changed = true
			}
			if (changed) {
				notifyFavoritesUpdate(favoriteItem, if (favoriteItem.watcherEnabled)
					Action.WATCHER_ENABLE else Action.WATCHER_DISABLE)
				serialize()
			}
		}
	}

	fun remove(chanName: String?, boardName: String?, threadNumber: String?) {
		val favoriteItem = favoriteItemsMap.remove(makeKey(chanName, boardName, threadNumber))
		if (favoriteItem != null) {
			favoriteItemsList.remove(favoriteItem)
			if (favoriteItem.watcherEnabled) {
				favoriteItem.watcherEnabled = false
				notifyFavoritesUpdate(favoriteItem, Action.WATCHER_DISABLE)
			}
			notifyFavoritesUpdate(favoriteItem, Action.REMOVE)
			serialize()
		}
	}

	fun getThreads(chanName: String?): ArrayList<FavoriteItem> {
		return getFavorites(chanName, threads = true, boards = false, orderByBoardName = false)
	}

	fun getBoards(chanName: String?): ArrayList<FavoriteItem> {
		return getFavorites(chanName, threads = false, boards = true, orderByBoardName = true)
	}

	private fun getFavorites(chanName: String?, threads: Boolean, boards: Boolean,
			orderByBoardName: Boolean): ArrayList<FavoriteItem> {
		val favoriteItems = ArrayList<FavoriteItem>()
		for (favoriteItem in favoriteItemsList) {
			if ((chanName == null || favoriteItem.chanName == chanName) && (threads && boards ||
							favoriteItem.threadNumber != null && threads ||
							favoriteItem.threadNumber == null && boards)) {
				favoriteItems.add(favoriteItem)
			}
		}
		val comparator = if (orderByBoardName) identifiersComparator else chanNameIndexAscendingComparator
		favoriteItems.sortWith(comparator)
		return favoriteItems
	}

	private val chanNameIndexAscendingComparator = Comparator<FavoriteItem> { lhs, rhs ->
		val result = compareChanNames(lhs, rhs)
		if (result != 0) {
			return@Comparator result
		}
		favoriteItemsList.indexOf(lhs) - favoriteItemsList.indexOf(rhs)
	}

	private val identifiersComparator = Comparator<FavoriteItem> { lhs, rhs ->
		var result = compareChanNames(lhs, rhs)
		if (result != 0) {
			return@Comparator result
		}
		result = compareBoardNames(lhs, rhs)
		if (result != 0) {
			return@Comparator result
		}
		result = compareThreadNumbers(lhs, rhs)
		if (result != 0) {
			return@Comparator result
		}
		favoriteItemsList.indexOf(lhs) - favoriteItemsList.indexOf(rhs)
	}

	private val titlesComparator = Comparator<FavoriteItem> { lhs, rhs ->
		var result = compareChanNames(lhs, rhs)
		if (result != 0) {
			return@Comparator result
		}
		result = StringUtils.compare(lhs.title, rhs.title, true)
		if (result != 0) {
			return@Comparator result
		}
		result = compareBoardNames(lhs, rhs)
		if (result != 0) {
			return@Comparator result
		}
		compareThreadNumbers(lhs, rhs)
	}

	class FavoriteItem(@JvmField val chanName: String, @JvmField val boardName: String?,
			@JvmField val threadNumber: String?) {
		@JvmField var title: String? = null

		@JvmField var modifiedTitle = false
		@JvmField var watcherEnabled = false

		constructor(favoriteItem: FavoriteItem) : this(favoriteItem.chanName, favoriteItem.boardName,
				favoriteItem.threadNumber, favoriteItem.title, favoriteItem.modifiedTitle,
				favoriteItem.watcherEnabled)

		constructor(chanName: String, boardName: String?, threadNumber: String?,
				title: String?, modifiedTitle: Boolean, watcherEnabled: Boolean) :
				this(chanName, boardName, threadNumber) {
			this.title = title
			this.modifiedTitle = modifiedTitle
			this.watcherEnabled = watcherEnabled
		}

		fun equals(chanName: String?, boardName: String?, threadNumber: String?): Boolean {
			return this.chanName == chanName && CommonUtils.equals(this.boardName, boardName) &&
					CommonUtils.equals(this.threadNumber, threadNumber)
		}
	}

	companion object {
		private const val KEY_DATA = "data"
		private const val KEY_CHAN_NAME = "chanName"
		private const val KEY_BOARD_NAME = "boardName"
		private const val KEY_THREAD_NUMBER = "threadNumber"
		private const val KEY_TITLE = "title"
		private const val KEY_MODIFIED_TITLE = "modifiedTitle"
		private const val KEY_WATCHER_ENABLED = "watcherEnabled"

		private val INSTANCE = FavoritesStorage()

		@JvmStatic
		fun getInstance(): FavoritesStorage = INSTANCE

		private fun makeKey(chanName: String?, boardName: String?, threadNumber: String?): String {
			return "$chanName/$boardName/$threadNumber"
		}

		private fun makeKey(favoriteItem: FavoriteItem): String {
			return makeKey(favoriteItem.chanName, favoriteItem.boardName, favoriteItem.threadNumber)
		}

		private fun compareChanNames(lhs: FavoriteItem, rhs: FavoriteItem): Int {
			return ChanManager.getInstance().compareChanNames(lhs.chanName, rhs.chanName)
		}

		private fun compareBoardNames(lhs: FavoriteItem, rhs: FavoriteItem): Int {
			return StringUtils.compare(lhs.boardName, rhs.boardName, false)
		}

		private fun compareThreadNumbers(lhs: FavoriteItem, rhs: FavoriteItem): Int {
			return StringUtils.compare(lhs.threadNumber, rhs.threadNumber, false)
		}
	}
}
