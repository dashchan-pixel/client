package com.mishiranu.dashchan.util

import android.content.res.Resources
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object IOUtils {
    @JvmStatic
    fun bytesToInt(
        littleEndian: Boolean,
        start: Int,
        count: Int,
        vararg bytes: Byte,
    ): Int {
        var result = 0
        for (i in 0 until count) {
            result = result shl 8 or
                (bytes[start + if (littleEndian) count - i - 1 else i].toInt() and 0xff)
        }
        return result
    }

    @JvmStatic
    fun intToBytes(
        value: Int,
        littleEndian: Boolean,
        start: Int,
        count: Int,
        bytes: ByteArray?,
    ): ByteArray {
        val result = bytes ?: ByteArray(start + count)
        var v = value
        for (i in 0 until count) {
            result[start + if (littleEndian) i else count - i - 1] = (v and 0xff).toByte()
            v = v ushr 8
        }
        return result
    }

    @JvmStatic
    @Throws(IOException::class)
    fun skipExactly(
        input: InputStream,
        count: Int,
    ): Int {
        var total = 0
        while (count - total > 0) {
            val skipped = input.skip((count - total).toLong()).toInt()
            if (skipped == 0) {
                val read = input.read()
                if (read == -1) {
                    break
                }
                total++
            }
            total += skipped
        }
        return total
    }

    @JvmStatic
    @Throws(IOException::class)
    fun skipExactlyCheck(
        input: InputStream,
        count: Int,
    ): Boolean = skipExactly(input, count) == count

    @JvmStatic
    @Throws(IOException::class)
    fun readExactly(
        input: InputStream,
        buffer: ByteArray,
        offset: Int,
        count: Int,
    ): Int {
        var total = 0
        while (count - total > 0) {
            val read = input.read(buffer, offset + total, count - total)
            if (read == -1) {
                break
            }
            total += read
        }
        return total
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readExactlyCheck(
        input: InputStream,
        buffer: ByteArray,
        offset: Int,
        count: Int,
    ): Boolean = readExactly(input, buffer, offset, count) == count

    @JvmStatic
    @Throws(IOException::class)
    fun copyStream(
        from: InputStream,
        to: OutputStream,
    ) {
        val data = ByteArray(8192)
        while (true) {
            val count = from.read(data)
            if (count == -1) {
                break
            }
            to.write(data, 0, count)
        }
    }

    @JvmStatic
    fun close(closeable: Closeable?): Boolean =
        try {
            closeable?.close()
            true
        } catch (e: IOException) {
            false
        }

    @JvmStatic
    fun readRawResourceString(
        resources: Resources,
        resId: Int,
    ): String {
        val output = ByteArrayOutputStream()
        try {
            resources.openRawResource(resId).use { input -> copyStream(input, output) }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        return String(output.toByteArray())
    }

    @JvmStatic
    fun copyInternalFile(
        from: File,
        to: File,
    ): Boolean =
        try {
            FileInputStream(from).use { input ->
                FileOutputStream(to).use { output ->
                    copyStream(input, output)
                }
            }
            true
        } catch (e: IOException) {
            false
        }

    @JvmField
    val SORT_BY_DATE = Comparator<File> { lhs, rhs -> lhs.lastModified().compareTo(rhs.lastModified()) }

    @JvmStatic
    fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        file.delete()
    }
}
