package com.mishiranu.dashchan.util

object FilenameUtils {
    private const val FILENAME_BLOCKED_CHARACTERS = "\\/:*?\"<>|."
    private const val FILENAME_MAX_CHARACTER_COUNT = 255

    @JvmStatic
    fun isValidCharacter(ch: Char): Boolean = !FILENAME_BLOCKED_CHARACTERS.contains(ch)

    @JvmStatic
    fun getFilenameBlockedCharacters(): String = FILENAME_BLOCKED_CHARACTERS

    @JvmStatic
    fun getFilenameMaxCharacterCount(): Int = FILENAME_MAX_CHARACTER_COUNT
}
