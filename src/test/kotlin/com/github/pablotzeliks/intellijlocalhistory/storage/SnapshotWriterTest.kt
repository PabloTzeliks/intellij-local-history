package com.github.pablotzeliks.intellijlocalhistory.storage

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime

class SnapshotWriterTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun makeRequest(
        relativePath: String = "src/Main.kt",
        content: String = "fun main() {}",
        timestamp: LocalDateTime = LocalDateTime.of(2026, 5, 8, 15, 6, 19)
    ): SnapshotRequest {
        val fileNameWithExt = relativePath.substringAfterLast('/')
        val hasExt = fileNameWithExt.contains('.') && fileNameWithExt.substringAfterLast('.') != fileNameWithExt

        return SnapshotRequest(
            relativePath = relativePath,
            fileName = if (hasExt) fileNameWithExt.substringBeforeLast('.') else fileNameWithExt,
            fileExtension = if (hasExt) ".${fileNameWithExt.substringAfterLast('.')}" else "",
            content = content,
            timestamp = timestamp,
            projectBasePath = tempDir.root.absolutePath
        )
    }

    @Test
    fun `write creates snapshot file in correct location`() {
        val request = makeRequest()
        SnapshotWriter.write(request)

        val expected = tempDir.root.resolve(".history/src/Main_20260508150619.kt")
        assertTrue("Snapshot file should exist", expected.exists())
    }

    @Test
    fun `write creates intermediate directories`() {
        val request = makeRequest(relativePath = "a/b/c/Deep.java")
        SnapshotWriter.write(request)

        val dir = tempDir.root.resolve(".history/a/b/c")
        assertTrue("Nested directories should be created", dir.isDirectory)
    }

    @Test
    fun `write preserves file content with UTF-8 encoding`() {
        val content = "// áéíóú ñ 日本語"
        val request = makeRequest(content = content)
        SnapshotWriter.write(request)

        val snapshotDir = tempDir.root.resolve(".history/src")
        val snapshot = snapshotDir.listFiles()!!.first { it.name.startsWith("Main_") }
        val read = Files.readString(snapshot.toPath(), StandardCharsets.UTF_8)
        assertEquals(content, read)
    }

    @Test
    fun `write uses correct timestamp format in filename`() {
        val ts = LocalDateTime.of(2026, 12, 31, 23, 59, 59)
        val request = makeRequest(timestamp = ts)
        SnapshotWriter.write(request)

        val snapshotDir = tempDir.root.resolve(".history/src")
        val name = snapshotDir.listFiles()!!.first { it.name.startsWith("Main_") }.name
        assertTrue("Filename should contain timestamp", name.contains("20261231235959"))
    }

    @Test
    fun `write handles file at project root (no subdirectory)`() {
        val request = makeRequest(relativePath = "README.md")
        SnapshotWriter.write(request)

        val expected = tempDir.root.resolve(".history/README_20260508150619.md")
        assertTrue("Root-level snapshot should be in .history directly", expected.exists())
    }
}
