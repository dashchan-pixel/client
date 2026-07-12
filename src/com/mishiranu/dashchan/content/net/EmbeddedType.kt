package com.mishiranu.dashchan.content.net

import chan.content.Chan
import chan.content.ChanLocator
import chan.util.StringUtils
import com.mishiranu.dashchan.content.model.Post
import java.util.regex.Pattern

enum class EmbeddedType(
		private val pattern: Pattern,
		private val index: Int,
		private val tests: List<String>,
		private val builder: (ChanLocator, String) -> Post.Attachment.Embedded?) {
	YOUTUBE(Pattern.compile("(?:https?://)(?:www\\.)?(?:m\\.)?" +
			"youtu(?:\\.be/|be\\.com/(?:v/|embed/|(?:#/)?watch\\?(?:.*?|)v=))([\\w\\-]{11})"),
			1, listOf("youtu"), { locator, embeddedCode ->
		val fileUri = locator.buildQueryWithSchemeHost(true,
				"www.youtube.com", "watch", "v", embeddedCode)
		val thumbnailUri = locator.buildPathWithSchemeHost(true,
				"img.youtube.com", "vi", embeddedCode, "default.jpg")
		Post.Attachment.Embedded.createExternal(true, fileUri, thumbnailUri, "YouTube",
				Post.Attachment.Embedded.ContentType.VIDEO, false, null)
	}),
	VIMEO(Pattern.compile("(?:https?://)(?:player\\.)?vimeo.com/(?:video/)?(?:channels/staffpicks/)?(\\d+)"),
			1, listOf("vimeo"), { locator, embeddedCode ->
		val fileUri = locator.buildPathWithSchemeHost(true, "vimeo.com", embeddedCode)
		Post.Attachment.Embedded.createExternal(true, fileUri, null, "Vimeo",
				Post.Attachment.Embedded.ContentType.VIDEO, false, null)
	}),
	VOCAROO(Pattern.compile("(?:https?://)(?:www\\.)?" +
			"(?:media\\.vocaroo\\.com|vocaroo\\.com|voca.ro)/(?:player\\.swf\\?playMediaID=|i/|" +
			"media_command\\.php\\?media=|mp3/|)([\\w\\-]{11,12})"),
			1, listOf("vocaroo", "voca.ro"), { locator, embeddedCode ->
		val fileUri = locator.buildPathWithHost("media.vocaroo.com", "mp3", embeddedCode)
		val forcedName = "vocaroo-$embeddedCode.mp3"
		Post.Attachment.Embedded.createExternal(true, fileUri, null, "Vocaroo",
				Post.Attachment.Embedded.ContentType.AUDIO, true, forcedName)
	});

	private fun test(text: String?): Boolean {
		if (StringUtils.isEmpty(text)) {
			return false
		}
		for (test in tests) {
			if (text!!.contains(test)) {
				return true
			}
		}
		return false
	}

	fun get(locator: ChanLocator, text: String?): String? =
			if (test(text)) locator.getGroupValue(text, pattern, index) else null

	fun getAll(locator: ChanLocator, text: String?): Array<String?>? =
			if (test(text)) locator.getUniqueGroupValues(text, pattern, index) else null

	fun obtainAttachment(locator: ChanLocator, embeddedCode: String): Post.Attachment.Embedded? =
			builder(locator, embeddedCode)

	companion object {
		@JvmStatic
		fun extractAttachment(data: String?): Post.Attachment.Embedded? {
			if (!StringUtils.isEmpty(data)) {
				val chan = Chan.getFallback()
				for (embeddedType in values()) {
					val embeddedCode = embeddedType.get(chan.locator, data)
					if (!StringUtils.isEmpty(embeddedCode)) {
						return embeddedType.obtainAttachment(chan.locator, embeddedCode!!)
					}
				}
			}
			return null
		}
	}
}
