package com.github.pablotzeliks.intellijlocalhistory.listener

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import com.github.pablotzeliks.intellijlocalhistory.service.SnapshotService
import com.github.pablotzeliks.intellijlocalhistory.util.FileFilters
import com.github.pablotzeliks.intellijlocalhistory.util.SnapshotGuard
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Escuta mudanças no conteúdo de todos os documentos abertos e gera snapshots
 * independentemente de saves explícitos.
 *
 * Usa dual debounce por arquivo:
 * - [INACTIVITY_TIMEOUT_MS]: snapshot após N segundos de inatividade (sem digitação)
 * - [MAX_INTERVAL_MS]: garante snapshot a cada N segundos mesmo durante digitação contínua
 *
 * Registrado via EditorFactory.eventMulticaster em LocalHistoryStartupActivity —
 * atua em todos os documentos do projeto e é removido automaticamente ao fechar o projeto.
 *
 * Complementa o DocumentSaveListener (que captura em saves explícitos com debounce de 1s).
 * A deduplicação SHA-256 no SnapshotService descarta sobreposições entre os dois listeners.
 */
class DocumentChangeListener(
    private val project: Project,
    private val cs: CoroutineScope
) : BulkAwareDocumentListener {

    // Timers por relativePath do arquivo
    private val inactivityJobs = ConcurrentHashMap<String, Job>()
    private val maxIntervalJobs = ConcurrentHashMap<String, Job>()

    override fun documentChanged(event: DocumentEvent) {
        if (SnapshotGuard.isActive()) return

        val document = event.document
        if (document.textLength > FileFilters.MAX_FILE_SIZE) return

        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isValid) return

        val projectDir = project.guessProjectDir() ?: return
        if (!FileFilters.shouldCapture(file, projectDir.path)) return

        // Retorna null se o arquivo não pertence a este projeto (multi-projeto aberto)
        val relativePath = VfsUtilCore.getRelativePath(file, projectDir) ?: return

        scheduleCapture(
            relativePath = relativePath,
            fileName = file.nameWithoutExtension,
            fileExt = if (file.extension != null) ".${file.extension}" else "",
            basePath = projectDir.path,
            file = file,
            document = document
        )
    }

    private fun scheduleCapture(
        relativePath: String,
        fileName: String,
        fileExt: String,
        basePath: String,
        file: VirtualFile,
        document: Document
    ) {
        // Reseta o timer de inatividade a cada mudança
        inactivityJobs[relativePath]?.cancel()
        inactivityJobs[relativePath] = cs.launch(Dispatchers.IO) {
            delay(INACTIVITY_TIMEOUT_MS)
            // Ao disparar por inatividade, o timer máximo já não é necessário
            maxIntervalJobs.remove(relativePath)?.cancel()
            doCapture(relativePath, fileName, fileExt, basePath, file, document)
            inactivityJobs.remove(relativePath)
        }

        // Timer máximo: inicia uma vez e não é resetado por novas mudanças.
        // Garante snapshot durante digitação contínua sem pausas.
        if (maxIntervalJobs[relativePath]?.isActive != true) {
            maxIntervalJobs[relativePath] = cs.launch(Dispatchers.IO) {
                delay(MAX_INTERVAL_MS)
                // Ao disparar por intervalo máximo, cancela o timer de inatividade pendente
                inactivityJobs.remove(relativePath)?.cancel()
                doCapture(relativePath, fileName, fileExt, basePath, file, document)
                maxIntervalJobs.remove(relativePath)
            }
        }
    }

    private suspend fun doCapture(
        relativePath: String,
        fileName: String,
        fileExt: String,
        basePath: String,
        file: VirtualFile,
        document: Document
    ) {
        if (!file.isValid) return

        // readAction suspende a coroutine (sem bloquear a thread IO) até adquirir read lock
        val content = runCatching {
            readAction { document.text }
        }.getOrNull() ?: return

        val request = SnapshotRequest(
            relativePath = relativePath,
            fileName = fileName,
            fileExtension = fileExt,
            content = content,
            timestamp = LocalDateTime.now(),
            projectBasePath = basePath
        )

        project.service<SnapshotService>().captureNow(request)
    }

    companion object {
        /** Tempo de inatividade após o qual um snapshot é gerado. Candidato a configurável na Fase 5. */
        const val INACTIVITY_TIMEOUT_MS = 15_000L  // 15s

        /** Intervalo máximo entre snapshots durante digitação contínua. Candidato a configurável na Fase 5. */
        const val MAX_INTERVAL_MS = 30_000L          // 30s
    }
}
