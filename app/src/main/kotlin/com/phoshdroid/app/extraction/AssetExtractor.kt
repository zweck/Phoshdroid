package com.phoshdroid.app.extraction

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream
import java.io.BufferedInputStream

class AssetExtractor {

    fun extract(
        input: InputStream,
        totalBytes: Long,
        targetDir: File,
        onProgress: (percent: Int) -> Unit = {}
    ): Boolean {
        val marker = File(targetDir, ".extraction_complete")
        if (marker.exists()) return false

        targetDir.mkdirs()
        var bytesRead = 0L
        var lastPercent = -1

        CountingInputStream(input) { count ->
            bytesRead = count
            val percent = if (totalBytes > 0) ((count * 100) / totalBytes).toInt().coerceAtMost(100) else 0
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
        }.let { counting ->
            TarArchiveInputStream(BufferedInputStream(counting)).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            tar.copyTo(out)
                        }
                        if (entry.mode and 0b001_001_001 != 0) {
                            outFile.setExecutable(true)
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }

        onProgress(100)
        marker.createNewFile()
        return true
    }
}

private class CountingInputStream(
    private val wrapped: InputStream,
    private val onBytesRead: (Long) -> Unit
) : InputStream() {

    private var totalRead = 0L

    override fun read(): Int {
        val b = wrapped.read()
        if (b >= 0) {
            totalRead++
            onBytesRead(totalRead)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = wrapped.read(b, off, len)
        if (n > 0) {
            totalRead += n
            onBytesRead(totalRead)
        }
        return n
    }

    override fun close() = wrapped.close()
}
