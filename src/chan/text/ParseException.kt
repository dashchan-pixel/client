package chan.text

import chan.annotation.Extendable
import chan.annotation.Public

@Extendable
open class ParseException : Exception {
	@Public
	constructor() : super()

	constructor(detailMessage: String?) : super(detailMessage)

	@Public
	constructor(throwable: Throwable?) : super(throwable)
}
