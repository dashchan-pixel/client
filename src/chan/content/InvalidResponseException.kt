package chan.content

import chan.annotation.Public
import com.mishiranu.dashchan.content.model.ErrorItem

@Public
class InvalidResponseException :
    Exception,
    ErrorItem.Holder {
    @Public
    constructor()

    @Public
    constructor(throwable: Throwable?) : super(throwable)

    override fun getErrorItemAndHandle(): ErrorItem {
        printStackTrace()
        return ErrorItem(ErrorItem.Type.INVALID_RESPONSE)
    }
}
