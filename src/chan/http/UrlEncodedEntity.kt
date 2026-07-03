/*
 * Copyright 2014-2016 Fukurou Mishiranu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package chan.http

import chan.annotation.Extendable
import chan.annotation.Public
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder

@Extendable
open class UrlEncodedEntity : RequestEntity {
	private val builder = StringBuilder()
	private var bytes: ByteArray? = null

	private var charsetName = "UTF-8"

	@Public
	constructor()

	@Public
	constructor(vararg params: String) {
		var i = 0
		while (i < params.size) {
			add(params[i], params[i + 1])
			i += 2
		}
	}

	@Public
	open fun setEncoding(charsetName: String) {
		this.charsetName = charsetName
	}

	override fun add(name: String, value: String?) {
		if (value != null) {
			bytes = null
			if (builder.isNotEmpty()) {
				builder.append('&')
			}
			builder.append(encode(name))
			builder.append('=')
			builder.append(encode(value))
		}
	}

	override fun getContentType(): String = "application/x-www-form-urlencoded"

	override fun getContentLength(): Long = getBytes().size.toLong()

	@Throws(IOException::class)
	override fun write(output: OutputStream) {
		output.write(getBytes())
		output.flush()
	}

	override fun copy(): RequestEntity {
		val entity = UrlEncodedEntity()
		entity.setEncoding(charsetName)
		entity.builder.append(builder)
		return entity
	}

	private fun encode(string: String): String = URLEncoder.encode(string, charsetName)

	private fun getBytes(): ByteArray {
		var bytes = bytes
		if (bytes == null) {
			bytes = builder.toString().toByteArray(Charsets.ISO_8859_1)
			this.bytes = bytes
		}
		return bytes
	}
}
