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
import java.io.IOException
import java.io.OutputStream

@Extendable
interface RequestEntity : Cloneable {
    @Extendable
    fun add(
        name: String,
        value: String?,
    )

    @Extendable
    fun getContentType(): String?

    @Extendable
    fun getContentLength(): Long

    @Extendable
    @Throws(IOException::class)
    fun write(output: OutputStream)

    @Extendable
    fun copy(): RequestEntity
}
