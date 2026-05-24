package com.github.pablotzeliks.intellijlocalhistory.service

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import com.github.pablotzeliks.intellijlocalhistory.storage.SnapshotReader
import com.github.pablotzeliks.intellijlocalhistory.storage.SnapshotWriter
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class SnapshotServiceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val historyDir = Path.of(project.basePath!!, ".history")
        if (Files.exists(historyDir)) {
            Files.walk(historyDir)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::delete)
        }
    }

    private fun basePath() = project.basePath!!

    private fun makeRequest(
        relativePath: String = "src/Main.kt",
        content: String = "fun main() {}",
        timestamp: LocalDateTime = LocalDateTime.of(2026, 1, 1, 10, 0, 0)
    ) = SnapshotRequest(
        relativePath = relativePath,
        fileName = relativePath.substringAfterLast('/').substringBeforeLast('.'),
        fileExtension = ".${relativePath.substringAfterLast('.')}",
        content = content,
        timestamp = timestamp,
        projectBasePath = basePath()
    )

    private fun snapshotsFor(relativePath: String) =
        SnapshotReader.listSnapshots(relativePath, basePath())

    // --- Deduplicação in-memory ---

    fun testSameContentDoesNotCreateDuplicateSnapshot() {
        val service = project.service<SnapshotService>()
        runBlocking {
            service.captureNow(makeRequest(content = "class A", timestamp = ts(0))).join()
            service.captureNow(makeRequest(content = "class A", timestamp = ts(1))).join()
        }
        assertEquals(1, snapshotsFor("src/Main.kt").size)
    }

    fun testDifferentContentCreatesTwoSnapshots() {
        val service = project.service<SnapshotService>()
        runBlocking {
            service.captureNow(makeRequest(content = "class A", timestamp = ts(0))).join()
            service.captureNow(makeRequest(content = "class B", timestamp = ts(1))).join()
        }
        assertEquals(2, snapshotsFor("src/Main.kt").size)
    }

    // --- Deduplicação cross-session ---

    fun testCrossSessionDedupSkipsIfDiskContentMatches() {
        // Simula snapshot gravado em sessão anterior (antes do SnapshotService ser instanciado)
        SnapshotWriter.write(makeRequest(content = "class A", timestamp = ts(-60)))

        val service = project.service<SnapshotService>()
        runBlocking {
            // SnapshotService não tem este path em memória — deve ler o disco e descartá-lo
            service.captureNow(makeRequest(content = "class A", timestamp = ts(0))).join()
        }
        assertEquals(1, snapshotsFor("src/Main.kt").size)
    }

    fun testCrossSessionDedupWritesIfDiskContentDiffers() {
        SnapshotWriter.write(makeRequest(content = "class A", timestamp = ts(-60)))

        val service = project.service<SnapshotService>()
        runBlocking {
            service.captureNow(makeRequest(content = "class B", timestamp = ts(0))).join()
        }
        assertEquals(2, snapshotsFor("src/Main.kt").size)
    }

    // --- Concorrência (regressão do TOCTOU — FIX-001) ---

    fun testConcurrentCapturesWithSameContentProduceSingleSnapshot() {
        val service = project.service<SnapshotService>()
        runBlocking {
            // Ambos os jobs são lançados antes de qualquer join — garantia de disparo concorrente.
            // Sem o Mutex: ambos passariam o hash check (map vazio) e gravariam dois arquivos distintos.
            // Com o Mutex: o segundo encontra o hash já registrado pelo primeiro e descarta.
            val jobs = listOf(
                service.captureNow(makeRequest(content = "class A", timestamp = ts(0))),
                service.captureNow(makeRequest(content = "class A", timestamp = ts(1)))
            )
            jobs.joinAll()
        }
        assertEquals(1, snapshotsFor("src/Main.kt").size)
    }

    fun testConcurrentCapturesWithDifferentContentProduceBothSnapshots() {
        val service = project.service<SnapshotService>()
        runBlocking {
            val jobs = listOf(
                service.captureNow(makeRequest(content = "class A", timestamp = ts(0))),
                service.captureNow(makeRequest(content = "class B", timestamp = ts(1)))
            )
            jobs.joinAll()
        }
        assertEquals(2, snapshotsFor("src/Main.kt").size)
    }

    // --- Line endings (FIX-003) ---

    fun testCrossSessionDedupNormalizesLineEndings() {
        // Disco com CRLF (Windows), Document em memória com LF (IntelliJ) — devem ser tratados como iguais
        SnapshotWriter.write(makeRequest(content = "class A\r\n  val x = 1\r\n", timestamp = ts(-60)))

        val service = project.service<SnapshotService>()
        runBlocking {
            service.captureNow(makeRequest(content = "class A\n  val x = 1\n", timestamp = ts(0))).join()
        }
        assertEquals(1, snapshotsFor("src/Main.kt").size)
    }

    // --- helper ---

    private fun ts(offsetSeconds: Long): LocalDateTime =
        LocalDateTime.of(2026, 1, 1, 10, 0, 0).plusSeconds(offsetSeconds)
}
