/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.http.cio

import io.ktor.http.cio.internals.*
import io.ktor.utils.io.*
import io.ktor.utils.io.bits.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.pool.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*

private const val MAX_CHUNK_SIZE_LENGTH = 128
private const val CHUNK_BUFFER_POOL_SIZE = 2048

private const val DEFAULT_BYTE_BUFFER_SIZE = 4088

@ThreadLocal
private val ChunkSizeBufferPool: ObjectPool<StringBuilder> =
    object : DefaultPool<StringBuilder>(CHUNK_BUFFER_POOL_SIZE) {
        override fun produceInstance(): StringBuilder = StringBuilder(MAX_CHUNK_SIZE_LENGTH)
        override fun clearInstance(instance: StringBuilder) = instance.apply { clear() }
    }

/**
 * Decoder job type
 */
typealias DecoderJob = WriterJob

/**
 * Start a chunked stream decoder coroutine
 */
@Deprecated("Specify content length if known or pass -1L",
    ReplaceWith("decodeChunked(input, -1L)"))
fun CoroutineScope.decodeChunked(input: ByteReadChannel): DecoderJob =
    decodeChunked(input, -1L)

/**
 * Start a chunked stream decoder coroutine
 */
fun CoroutineScope.decodeChunked(input: ByteReadChannel, contentLength: Long): DecoderJob = writer(coroutineContext) {
    decodeChunked(input, channel, contentLength)
}

@Deprecated("Specify contentLength if provided or pass -1L",
    ReplaceWith("decodeChunked(input, out, -1L)"))
suspend fun decodeChunked(input: ByteReadChannel, out: ByteWriteChannel) {
    return decodeChunked(input, out, -1L)
}

/**
 * Chunked stream decoding loop
 */
suspend fun decodeChunked(input: ByteReadChannel, out: ByteWriteChannel, contentLength: Long) {
    val chunkSizeBuffer = ChunkSizeBufferPool.borrow()
    var totalBytesCopied = 0L

    try {
        while (true) {
            chunkSizeBuffer.clear()
            if (!input.readUTF8LineTo(chunkSizeBuffer, MAX_CHUNK_SIZE_LENGTH)) {
                throw EOFException("Chunked stream has ended unexpectedly: no chunk size")
            } else if (chunkSizeBuffer.isEmpty()) {
                throw EOFException("Invalid chunk size: empty")
            }

            val chunkSize =
                if (chunkSizeBuffer.length == 1 && chunkSizeBuffer[0] == '0') 0
                else chunkSizeBuffer.parseHexLong()

            if (contentLength != -1L && chunkSize > (contentLength - totalBytesCopied)) {
                input.cancel()
                throw ParserException("Invalid chunk: chunk-encoded content is larger than expected $contentLength")
            }

            if (chunkSize > 0) {
                input.copyTo(out, chunkSize)
                out.flush()
                totalBytesCopied += chunkSize
            }

            chunkSizeBuffer.clear()
            if (!input.readUTF8LineTo(chunkSizeBuffer, 2)) {
                throw EOFException("Invalid chunk: content block of size $chunkSize ended unexpectedly")
            }
            if (chunkSizeBuffer.isNotEmpty()) {
                throw EOFException("Invalid chunk: content block should end with CR+LF")
            }

            if (chunkSize == 0L) break
        }

        if (contentLength != -1L && totalBytesCopied != contentLength) {
            input.cancel()
            throw EOFException("Corrupted chunk-encoded content: steam ended before the expected number of bytes ($contentLength) were decoded.")
        }
    } catch (t: Throwable) {
        out.close(t)
        throw t
    } finally {
        ChunkSizeBufferPool.recycle(chunkSizeBuffer)
        out.close()
    }
}

/**
 * Encoder job type
 */
typealias EncoderJob = ReaderJob

/**
 * Start chunked stream encoding coroutine
 */
suspend fun encodeChunked(
    output: ByteWriteChannel,
    coroutineContext: CoroutineContext
): EncoderJob = GlobalScope.reader(coroutineContext, autoFlush = false) {
    encodeChunked(output, channel)
}

/**
 * Chunked stream encoding loop
 */
suspend fun encodeChunked(output: ByteWriteChannel, input: ByteReadChannel) {
    try {
        while (!input.isClosedForRead) {
            input.read { source, startIndex, endIndex ->
                if (endIndex == startIndex) return@read 0
                output.writeChunk(source, startIndex.toInt(), endIndex.toInt())
            }
        }

        output.writeFully(LastChunkBytes)
    } catch (cause: Throwable) {
        output.close(cause)
        input.cancel(cause)
    } finally {
        output.flush()
    }
}

@SharedImmutable
private const val CrLfShort: Short = 0x0d0a

@ThreadLocal
private val CrLf = "\r\n".toByteArray()

@ThreadLocal
private val LastChunkBytes = "0\r\n\r\n".toByteArray()

private suspend fun ByteWriteChannel.writeChunk(memory: Memory, startIndex: Int, endIndex: Int): Int {
    val size = endIndex - startIndex
    writeIntHex(size)
    writeShort(CrLfShort)

    writeFully(memory, startIndex, endIndex)
    writeFully(CrLf)
    flush()

    return size
}
