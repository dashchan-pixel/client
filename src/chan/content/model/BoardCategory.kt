package chan.content.model

import chan.annotation.Public
import chan.util.CommonUtils

@Public
class BoardCategory @Public constructor(private val title: String?, boards: Array<Board?>?) {
	private val boards: Array<Board?>? = CommonUtils.removeNullItems(boards, Board::class.java)

	@Public
	fun getTitle(): String? = title

	@Public
	fun getBoards(): Array<Board?>? = boards

	@Public
	@Suppress("UNCHECKED_CAST") // Array<Board> and Array<Board?> share the Board[] erasure
	constructor(title: String?, boards: Collection<Board>?) :
			this(title, CommonUtils.toArray(boards, Board::class.java) as Array<Board?>?)
}
