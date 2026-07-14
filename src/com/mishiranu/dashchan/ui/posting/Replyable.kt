package com.mishiranu.dashchan.ui.posting

import android.os.Parcel
import android.os.Parcelable
import com.mishiranu.dashchan.content.model.PostNumber

fun interface Replyable {
    fun onRequestReply(
        click: Boolean,
        vararg data: ReplyData,
    ): Boolean

    class ReplyData(
        @JvmField val postNumber: PostNumber?,
        @JvmField val comment: String?,
    ) : Parcelable {
        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeByte(if (postNumber != null) 1 else 0)
            postNumber?.writeToParcel(dest, flags)
            dest.writeString(comment)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<ReplyData> =
                object : Parcelable.Creator<ReplyData> {
                    override fun createFromParcel(source: Parcel): ReplyData {
                        val postNumber =
                            if (source.readByte().toInt() != 0) {
                                PostNumber.CREATOR.createFromParcel(source)
                            } else {
                                null
                            }
                        val comment = source.readString()
                        return ReplyData(postNumber, comment)
                    }

                    override fun newArray(size: Int): Array<ReplyData?> = arrayOfNulls(size)
                }
        }
    }
}
