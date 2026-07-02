package com.mishiranu.dashchan.text

import android.net.Uri
import chan.util.StringUtils
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WakabaLikeHtmlBuilder(threadTitle: String?, boardName: String?, boardTitle: String?,
		chanTitle: String?, threadUri: Uri, postsCount: Int, filesCount: Int) {
	private val builder = StringBuilder()

	private var originalPost = true
	private var number: String? = null
	private var subject: String? = null
	private var name: String? = null
	private var identifier: String? = null
	private var tripcode: String? = null
	private var capcode: String? = null
	private var email: String? = null
	private var sage = false
	private var originalPoster = false
	private var timestamp = 0L
	private var deleted = false
	private var useDefaultName = false
	private var comment: String? = null
	private val iconItems = ArrayList<IconItem>()
	private val fileItems = ArrayList<FileItem>()

	private class IconItem(val imageFile: String, val title: String?)

	private class FileItem(val imageFile: String, val thumbnailFile: String?, val displayName: String,
			val originalName: String?, val size: Int, val width: Int, val height: Int)

	init {
		val builder = this.builder
		builder.append("<!DOCTYPE html>\n<html>\n<head>\n")
				.append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />\n")
		builder.append("<title>")
		if (!StringUtils.isEmpty(threadTitle)) {
			builder.append(threadTitle).append(" — ")
		}
		builder.append('/').append(boardName).append('/').append(" — ")
		if (!StringUtils.isEmpty(boardTitle)) {
			builder.append(boardTitle).append(" — ")
		}
		builder.append(chanTitle)
		builder.append("</title>\n")
		for (i in STYLES.indices) {
			val (title, uri) = STYLES[i]
			builder.append("<link rel=\"")
			if (i > 0) {
				builder.append("alternate ")
			}
			builder.append("stylesheet\" type=\"text/css\" href=\"").append(uri)
					.append("\" title=\"").append(title).append("\" />\n")
		}
		builder.append("<style type=\"text/css\">\nbody {margin: 0; padding: 8px; margin-bottom: auto;}\n")
				.append(".thumb {border: none; margin: 2px 20px; max-width: 200px; max-height: 200px;}\n")
				.append(".nothumb {float: left; background: #eee; border: 2px dashed #aaa;\n")
				.append("text-align: center; margin: 2px 20px; padding: 1em 0.5em 1em 0.5em;}\n")
				.append(".filesize {padding-left: 20px; display: inline-block;}\n")
				.append(".replyheader {padding: 0 0.25em 0 0;}\n")
				.append(".reflink a {color: inherit; text-decoration: none;}\n")
				.append(".withimage {min-width: 30em;}\n")
				.append(".postericon {max-height: 1em;}\n")
				.append("span.underline {text-decoration: underline;}\n")
				.append("span.overline {text-decoration: overline;}\n")
				.append("span.strike {text-decoration: line-through;}\n")
				.append("span.code {font-family: monospace; white-space: pre;}\n")
				.append("span.aa {font-family: Mona, \"MS PGothic\", monospace;}\n")
				.append("span.heading {font-weight: bold; font-size: 1.2rem;}\n</style>\n")
		builder.append("<script type=\"text/javascript\">\nfunction switchStyle(style)\n{\n\t")
				.append("var links = document.getElementsByTagName('link');\n\tfor (var i = 0; i < links.length; i++)")
				.append("\n\t{\n\t\tvar rel = links[i].getAttribute(\"rel\");")
				.append("\n\t\tvar title = links[i].getAttribute(\"title\");")
				.append("\n\t\tif (rel.indexOf(\"style\") != -1 && title) links[i].disabled = title != style;")
				.append("\n\t}\n}\nswitchStyle('Photon');\n</script>\n")
		builder.append("</head>\n<body>\n<div class=\"logo\">").append(boardTitle).append(" @ ").append(chanTitle)
				.append("</div>\n<div class=\"logo\" style=\"font-size: 1rem; margin-top: 0.25em;\">\n")
		for ((title, _) in STYLES) {
			builder.append("[ <a href=\"javascript:switchStyle('").append(title).append("');\">")
					.append(title).append("</a> ]\n")
		}
		builder.append("</div>\n<hr />\n<div id=\"delform\" data-thread-uri=\"").append(threadUri.toString())
				.append("\" data-posts=\"").append(postsCount).append("\" data-files=\"")
				.append(filesCount).append("\">\n")
	}

	fun addPost(number: String, subject: String?, name: String?, identifier: String?, tripcode: String?,
			capcode: String?, email: String?, sage: Boolean, originalPoster: Boolean, timestamp: Long,
			deleted: Boolean, useDefaultName: Boolean, comment: String?) {
		closePost()
		this.number = number
		this.subject = subject
		this.name = name
		this.identifier = identifier
		this.tripcode = tripcode
		this.capcode = capcode
		this.email = email
		this.sage = sage
		this.originalPoster = originalPoster
		this.timestamp = timestamp
		this.deleted = deleted
		this.useDefaultName = useDefaultName
		this.comment = comment
	}

	fun addIcon(imageFile: String, title: String?) {
		iconItems.add(IconItem(imageFile, title))
	}

	fun addFile(imageFile: String, thumbnailFile: String?, originalName: String?,
			size: Int, width: Int, height: Int) {
		var index = imageFile.lastIndexOf('/')
		var displayName = if (index >= 0) imageFile.substring(index + 1) else imageFile
		index = displayName.lastIndexOf('.')
		var extension: String? = null
		if (index >= 0) {
			extension = displayName.substring(index)
			displayName = displayName.substring(0, index)
		}
		val maxLength = 25
		if (displayName.length > maxLength) {
			displayName = displayName.substring(0, maxLength - 3) + "…" +
					displayName.substring(displayName.length - 3)
		}
		if (extension != null) {
			displayName += extension
		}
		fileItems.add(FileItem(imageFile, thumbnailFile, displayName, originalName, size, width, height))
	}

	private fun closePost() {
		val number = this.number
		if (number != null) {
			val builder = this.builder
			builder.append("<span data-number=\"").append(number).append("\"></span>\n")
			if (originalPost) {
				originalPost = false
				appendFiles()
				appendHeader(true)
				appendComment()
			} else {
				builder.append("<table>\n<tbody>\n<tr>\n<td class=\"doubledash\">&gt;&gt;</td>\n")
						.append("<td class=\"reply\" id=\"reply").append(number).append("\">\n")
				appendHeader(false)
				appendFiles()
				appendComment()
				builder.append("</td>\n</tr>\n</tbody>\n</table>\n")
			}
		}
		this.number = null
		iconItems.clear()
		fileItems.clear()
	}

	private fun appendHeader(originalPost: Boolean) {
		val number = this.number
		val subject = this.subject
		val name = this.name
		val identifier = this.identifier
		val tripcode = this.tripcode
		val capcode = this.capcode
		var email = this.email
		val timestamp = this.timestamp
		val builder = this.builder
		builder.append("<div")
		if (!originalPost) {
			builder.append(" class=\"replyheader\"")
		}
		builder.append(">\n<a name=\"").append(number).append("\"></a>\n<input type=\"checkbox\" value=\"")
				.append(number).append("\" disabled />\n")
		if (!StringUtils.isEmpty(subject)) {
			builder.append("<span class=\"replytitle\" data-subject=\"true\">").append(subject).append("</span>\n")
		}
		val hasName = !StringUtils.isEmpty(name)
		val hasIdentifier = !StringUtils.isEmpty(identifier)
		val hasEmail = !StringUtils.isEmpty(email)
		builder.append("<span class=\"postername\" ")
		if (hasName) {
			builder.append("data-name=\"").append(escapeHtml(name)).append("\"")
		}
		if (hasIdentifier) {
			builder.append(" data-identifier=\"").append(escapeHtml(identifier)).append("\"")
		}
		if (hasEmail) {
			builder.append(" data-email=\"").append(escapeHtml(email)).append("\"")
		}
		if (useDefaultName) {
			builder.append(" data-default-name=\"true\"")
		}
		builder.append('>')
		if (hasEmail) {
			if (!email!!.startsWith("mailto:")) {
				email = "mailto:$email"
			}
			builder.append("<a href=\"").append(email).append("\">")
		}
		if (hasName) {
			builder.append(name)
		}
		if (hasEmail) {
			builder.append("</a>")
		}
		if (hasIdentifier) {
			builder.append(" ID: ").append(identifier)
		}
		builder.append("</span>\n")
		val hasTripcode = !StringUtils.isEmpty(tripcode)
		val hasCapcode = !StringUtils.isEmpty(capcode)
		val originalPoster = this.originalPoster
		if (hasTripcode || hasCapcode || originalPoster) {
			builder.append("<span class=\"postertrip\"")
			if (hasTripcode) {
				builder.append(" data-tripcode=\"").append(escapeHtml(tripcode)).append("\"")
			}
			if (hasCapcode) {
				builder.append(" data-capcode=\"").append(escapeHtml(capcode)).append("\"")
			}
			if (originalPoster) {
				builder.append(" data-op=\"true\"")
			}
			builder.append('>')
			if (hasTripcode) {
				builder.append(tripcode)
			}
			if (hasCapcode) {
				if (hasTripcode) {
					builder.append(' ')
				}
				builder.append("## ").append(capcode)
			}
			if (originalPoster) {
				if (hasTripcode || hasCapcode) {
					builder.append(' ')
				}
				builder.append("# OP")
			}
			builder.append("</span>\n")
		}
		if (sage) {
			builder.append("<a href=\"mailto:sage\" data-sage=\"true\"></a>\n")
		}
		for (iconItem in iconItems) {
			builder.append("<img data-icon=\"true\" class=\"postericon\" src=\"")
					.append(iconItem.imageFile).append("\"")
			if (iconItem.title != null) {
				builder.append(" title=\"").append(escapeHtml(iconItem.title)).append("\"")
			}
			builder.append(" />\n")
		}
		builder.append("<span data-timestamp=\"").append(timestamp).append("\">")
				.append(DATE_FORMAT.format(timestamp)).append("</span>\n")
		builder.append("<span class=\"reflink\">No.").append(number)
		if (deleted) {
			builder.append(" <span style=\"color: #f00\">DELETED</span>")
		}
		builder.append("</span>\n</div>\n")
	}

	private fun appendComment() {
		val builder = this.builder
		builder.append("<blockquote data-comment=\"true\"")
		if (fileItems.isNotEmpty()) {
			builder.append(" class=\"withimage\"")
		}
		builder.append(">\n").append(comment).append("\n</blockquote>\n")
	}

	private fun appendFiles() {
		val multiple = fileItems.size > 1
		for (fileItem in fileItems) {
			appendFile(fileItem, multiple)
		}
		if (multiple) {
			builder.append("<br style=\"clear: left;\" />\n")
		}
	}

	private fun appendFile(fileItem: FileItem, multiple: Boolean) {
		val builder = this.builder
		if (multiple) {
			builder.append("<div style=\"float: left;\">\n")
		}
		builder.append("<span class=\"filesize\" data-file=\"").append(fileItem.imageFile)
				.append("\" data-thumbnail=\"").append(fileItem.thumbnailFile ?: "")
		if (!StringUtils.isEmpty(fileItem.originalName)) {
			builder.append("\" data-original-name=\"").append(escapeHtml(fileItem.originalName))
		}
		builder.append("\" data-size=\"").append(fileItem.size).append("\" data-width=\"").append(fileItem.width)
				.append("\" data-height=\"").append(fileItem.height).append("\">\n")
		builder.append("File: <a target=\"_blank\" href=\"")
				.append(fileItem.imageFile).append("\">").append(fileItem.displayName).append("</a>\n")
		var size: String? = null
		if (fileItem.size > 0) {
			val sizeFloat: Float
			val dim: String
			if (fileItem.size >= 2 * 1000 * 1000) {
				sizeFloat = fileItem.size / 1000f / 1000f
				dim = "MB"
			} else if (fileItem.size >= 2 * 1000) {
				sizeFloat = fileItem.size / 1000f
				dim = "kB"
			} else {
				sizeFloat = fileItem.size.toFloat()
				dim = "B"
			}
			size = String.format(Locale.US, "%.2f", sizeFloat) + ' ' + dim
		}
		val hasFileInfo = size != null || fileItem.width > 0 && fileItem.height > 0 ||
				!StringUtils.isEmpty(fileItem.originalName)
		if (hasFileInfo) {
			if (multiple) {
				builder.append("<br />\n")
			}
			val hasTitleFileInfo = multiple && !StringUtils.isEmpty(fileItem.originalName)
			builder.append("(<em")
			if (hasTitleFileInfo) {
				builder.append(" title=\"")
				appendFileInfo(size, fileItem, false)
				builder.append("\"")
			}
			builder.append('>')
			appendFileInfo(size, fileItem, multiple)
			builder.append("</em>)\n")
		}
		builder.append("</span>\n<br />\n")
		if (fileItem.thumbnailFile != null) {
			builder.append("<a target=\"_blank\" href=\"").append(fileItem.imageFile).append("\">\n<img src=\"")
					.append(fileItem.thumbnailFile).append("\" class=\"thumb\"")
			if (!multiple) {
				builder.append(" style=\"float: left;\"")
			}
			builder.append(" />\n</a>\n")
		} else {
			builder.append("<div class=\"nothumb\">\n<a target=\"_blank\" href=\"").append(fileItem.imageFile)
					.append("\">No<br />thumbnail</a>\n</div>\n")
		}
		if (multiple) {
			builder.append("</div>\n")
		}
	}

	private fun appendFileInfo(size: String?, fileItem: FileItem, shortInfo: Boolean) {
		val builder = this.builder
		var divider = false
		if (size != null) {
			divider = true
			builder.append(size)
		}
		if (fileItem.width > 0 && fileItem.height > 0) {
			if (divider) {
				builder.append(", ")
			} else {
				divider = true
			}
			builder.append(fileItem.width).append('×').append(fileItem.height)
		}
		if (!StringUtils.isEmpty(fileItem.originalName)) {
			if (divider) {
				builder.append(", ")
			}
			if (shortInfo) {
				builder.append("…")
			} else {
				builder.append(escapeHtml(fileItem.originalName))
			}
		}
	}

	fun build(): String {
		closePost()
		return builder.append("<br style=\"clear: left;\" />\n<hr />\n</div>\n")
				.append("<p class=\"footer\">\n- <a href=\"").append(CLIENT_URI).append("\">dashchan</a> + ")
				.append("<a href=\"http://wakaba.c3.cx/\">wakaba</a> + ")
				.append("<a href=\"http://www.2chan.net/\">futaba</a> -\n</p>\n</body>\n</html>").toString()
	}

	companion object {
		private val STYLES = listOf(
				"Photon" to "https://mishiranu.github.io/Dashchan/wakaba/photon.css",
				"Futaba" to "https://mishiranu.github.io/Dashchan/wakaba/futaba.css",
				"Burichan" to "https://mishiranu.github.io/Dashchan/wakaba/burichan.css",
				"Gurochan" to "https://mishiranu.github.io/Dashchan/wakaba/gurochan.css")

		private const val CLIENT_URI = "https://github.com/Mishiranu/Dashchan/"

		private val DATE_FORMAT = SimpleDateFormat("MM/dd/yy(ccc)HH:mm:ss", Locale.US).apply {
			timeZone = TimeZone.getTimeZone("Etc/GMT")
		}

		private fun escapeHtml(string: String?): String {
			return string?.replace("&", "&amp;")?.replace("\"", "&quot;")
					?.replace("<", "&lt;")?.replace(">", "&gt;") ?: ""
		}
	}
}
