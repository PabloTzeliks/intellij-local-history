package com.github.pablotzeliks.intellijlocalhistory.storage

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class SnapshotReaderTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun writeSnapshot(relativePath: String, timestamp: LocalDateTime, content: String = "x") {
        val fileNameWithExtension = relativePath.substringAfterLast('/')
        val hasExtension = fileNameWithExtension.contains('.') && 
                           fileNameWithExtension.substringAfterLast('.') != fileNameWithExtension
        
        val request = SnapshotRequest(
            relativePath = relativePath,
            fileName = if (hasExtension) fileNameWithExtension.substringBeforeLast('.') else fileNameWithExtension,
            fileExtension = if (hasExtension) ".${fileNameWithExtension.substringAfterLast('.')}" else "",
            content = content,
            timestamp = timestamp,
            projectBasePath = tempDir.root.absolutePath
        )
        SnapshotWriter.write(request)
    }

    @Test
    fun `listSnapshots returns empty list when no snapshots exist`() {
        val result = SnapshotReader.listSnapshots("src/Main.kt", tempDir.root.absolutePath)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `listSnapshots returns snapshots ordered newest first`() {
        val old = LocalDateTime.of(2026, 1, 1, 10, 0, 0)
        val new = LocalDateTime.of(2026, 6, 1, 10, 0, 0)
        writeSnapshot("src/Main.kt", old)
        writeSnapshot("src/Main.kt", new)

        val result = SnapshotReader.listSnapshots("src/Main.kt", tempDir.root.absolutePath)
        assertEquals(2, result.size)
        assertEquals(new, result[0].timestamp)
        assertEquals(old, result[1].timestamp)
    }

    @Test
    fun `listSnapshots only returns snapshots for the requested file`() {
        writeSnapshot("src/Main.kt", LocalDateTime.of(2026, 1, 1, 0, 0, 0))
        writeSnapshot("src/Other.kt", LocalDateTime.of(2026, 1, 2, 0, 0, 0))

        val result = SnapshotReader.listSnapshots("src/Main.kt", tempDir.root.absolutePath)
        assertEquals(1, result.size)
        assertTrue(result[0].file.name.startsWith("Main_"))
    }

    @Test
    fun `readContent returns the original file content`() {
        val content = "fun hello() = println(\"world\")"
        writeSnapshot("src/Hello.kt", LocalDateTime.of(2026, 3, 15, 12, 0, 0), content)

        val entries = SnapshotReader.listSnapshots("src/Hello.kt", tempDir.root.absolutePath)
        assertEquals(1, entries.size)
        assertEquals(content, SnapshotReader.readContent(entries[0]))
    }

    @Test
    fun `listSnapshots handles files without extension`() {
        writeSnapshot("Makefile", LocalDateTime.of(2026, 4, 1, 8, 0, 0), "all: build")

        val result = SnapshotReader.listSnapshots("Makefile", tempDir.root.absolutePath)
        assertEquals(1, result.size)
        assertTrue(result[0].file.name.startsWith("Makefile_"))
    }

    @Test
    fun `listSnapshots does not match files with similar but longer prefix`() {
        // "MainExtra_20260101000000.kt" should NOT match a query for "Main.kt"
        val request = SnapshotRequest(
            relativePath = "src/MainExtra.kt",
            fileName = "MainExtra",
            fileExtension = ".kt",
            content = "decoy",
            timestamp = LocalDateTime.of(2026, 1, 1, 0, 0, 0),
            projectBasePath = tempDir.root.absolutePath
        )
        SnapshotWriter.write(request)

        val result = SnapshotReader.listSnapshots("src/Main.kt", tempDir.root.absolutePath)
        assertTrue("Should not match file with longer prefix", result.isEmpty())
    }

    @Test
    fun `listSnapshots silently ignores files with invalid timestamp in name`() {
        // Usa um nome que passa no matchesSnapshot (14 dígitos) mas falha no parseEntry (mês 13)
        val historyDir = tempDir.newFolder(".history", "src")
        historyDir.resolve("Main_20261301000000.kt").writeText("invalid")

        val result = SnapshotReader.listSnapshots("src/Main.kt", tempDir.root.absolutePath)
        assertTrue("Invalid snapshot filename should be silently ignored", result.isEmpty())
    }
}
