package com.phoshdroid.app.extraction

import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import com.github.luben.zstd.ZstdOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class AssetExtractorTest {

    @TempDir
    lateinit var tempDir: File

    private fun createTarZst(files: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZstdOutputStream(baos).use { zstd ->
            TarArchiveOutputStream(zstd).use { tar ->
                for ((name, content) in files) {
                    val entry = tar.createArchiveEntry(File(name), name)
                    entry.size = content.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `extracts single file from tar zst`() {
        val content = "hello world".toByteArray()
        val archive = createTarZst(mapOf("test.txt" to content))

        val extractor = AssetExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        val extracted = File(tempDir, "test.txt")
        assertTrue(extracted.exists())
        assertEquals("hello world", extracted.readText())
    }

    @Test
    fun `extracts nested directory structure`() {
        val archive = createTarZst(mapOf(
            "usr/bin/hello" to "#!/bin/sh".toByteArray(),
            "etc/config" to "key=value".toByteArray()
        ))

        val extractor = AssetExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertTrue(File(tempDir, "usr/bin/hello").exists())
        assertEquals("key=value", File(tempDir, "etc/config").readText())
    }

    @Test
    fun `reports progress during extraction`() {
        val largeContent = ByteArray(10_000) { it.toByte() }
        val archive = createTarZst(mapOf("big.bin" to largeContent))

        val progressValues = mutableListOf<Int>()
        val extractor = AssetExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir,
            onProgress = { percent -> progressValues.add(percent) }
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(100, progressValues.last())
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1])
        }
    }

    @Test
    fun `skips extraction if marker file exists`() {
        val archive = createTarZst(mapOf("test.txt" to "hello".toByteArray()))

        File(tempDir, ".extraction_complete").createNewFile()

        val extractor = AssetExtractor()
        val result = extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertFalse(result)
        assertFalse(File(tempDir, "test.txt").exists())
    }
}
