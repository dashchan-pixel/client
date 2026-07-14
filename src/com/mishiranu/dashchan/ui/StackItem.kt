package com.mishiranu.dashchan.ui

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

class StackItem : Parcelable {
    fun interface ReplaceFragment {
        fun replace(fragment: Fragment): Fragment
    }

    fun interface SaveFragment {
        fun save(
            fragmentManager: FragmentManager,
            fragment: Fragment,
        ): Fragment.SavedState?
    }

    @JvmField val className: String

    @JvmField val arguments: Bundle?

    @JvmField val savedState: Fragment.SavedState?

    constructor(className: String, arguments: Bundle?, savedState: Fragment.SavedState?) {
        this.className = className
        this.arguments = arguments
        this.savedState = savedState
    }

    constructor(fragmentManager: FragmentManager, fragment: Fragment, saveFragment: SaveFragment?) :
        this(
            fragment.javaClass.name,
            fragment.arguments,
            if (saveFragment != null) {
                saveFragment.save(fragmentManager, fragment)
            } else {
                fragmentManager.saveFragmentInstanceState(fragment)
            },
        )

    fun create(replaceFragment: ReplaceFragment?): Fragment {
        var fragment: Fragment =
            try {
                Class.forName(className).getDeclaredConstructor().newInstance() as Fragment
            } catch (e: ReflectiveOperationException) {
                throw RuntimeException(e)
            }
        if (arguments != null) {
            fragment.arguments = arguments
        }
        if (replaceFragment != null) {
            fragment = replaceFragment.replace(fragment)
        }
        if (savedState != null) {
            fragment.setInitialSavedState(savedState)
        }
        return fragment
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeString(className)
        dest.writeByte((if (arguments != null) 1 else 0).toByte())
        arguments?.writeToParcel(dest, flags)
        dest.writeByte((if (savedState != null) 1 else 0).toByte())
        savedState?.writeToParcel(dest, flags)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<StackItem> =
            object : Parcelable.Creator<StackItem> {
                override fun createFromParcel(source: Parcel): StackItem {
                    val className = source.readString()!!
                    val arguments =
                        if (source.readByte().toInt() != 0) {
                            Bundle.CREATOR.createFromParcel(source)
                        } else {
                            null
                        }
                    arguments?.classLoader = javaClass.classLoader
                    val savedState =
                        if (source.readByte().toInt() != 0) {
                            Fragment.SavedState.CREATOR.createFromParcel(source)
                        } else {
                            null
                        }
                    return StackItem(className, arguments, savedState)
                }

                override fun newArray(size: Int): Array<StackItem?> = arrayOfNulls(size)
            }
    }
}
