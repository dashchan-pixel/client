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

@Extendable
open class SimpleEntity
    @Public
    constructor() : RequestEntity {
        private var data: ByteArray? = null
        private var contentType = "text/plain"

        override fun add(
            name: String,
            value: String?,
        ): Unit = throw UnsupportedOperationException()

        @Extendable
        open fun setData(data: String?) {
            setData(data, "UTF-8")
        }

        @Extendable
        open fun setData(
            data: String?,
            charsetName: String,
        ) {
            setData(data?.toByteArray(charset(charsetName)))
        }

        @Extendable
        open fun setData(data: ByteArray?) {
            this.data = data
        }

        @Extendable
        open fun setContentType(contentType: String) {
            require(contentType.isNotEmpty()) { "Invalid content type" }
            this.contentType = contentType
        }

        override fun getContentType(): String = contentType

        override fun getContentLength(): Long = data?.size?.toLong() ?: 0L

        @Throws(IOException::class)
        override fun write(output: OutputStream) {
            data?.let { output.write(it) }
        }

        override fun copy(): RequestEntity {
            val entity = SimpleEntity()
            entity.setData(data)
            entity.setContentType(contentType)
            return entity
        }
    }
