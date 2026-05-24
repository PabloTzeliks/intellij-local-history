package com.github.pablotzeliks.intellijlocalhistory.listener

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import com.github.pablotzeliks.intellijlocalhistory.service.SnapshotService
import com.github.pablotzeliks.intellijlocalhistory.util.FileFilters
import com.github.pablotzeliks.intellijlocalhistory.util.SnapshotGuard
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import java.time.LocalDateTime

class DocumentSaveListener : FileDocumentManagerListener {

    /**
     * Chamado pela IDE ANTES de cada save de documento.
     * Roda na EDT — fazer o mínimo possível aqui.
     */
    override fun beforeDocumentSaving(document: Document) {
        // 1. Guard ativo? (estamos no meio de um restore) → sair
        if (SnapshotGuard.isActive()) return

        // 2. Obter o VirtualFile associado ao Document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return

        // 3. Precisamos de um projeto para calcular o path relativo
        //    Como o listener é global (application-level), buscamos o projeto
        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return
        val projectDir = project.guessProjectDir() ?: return

        // 4. Filtros: binário? muito grande? diretório excluído?
        if (!FileFilters.shouldCapture(file, projectDir.path)) return

        // 5. Filtros sobre o conteúdo em memória (document, não o arquivo em disco)
        if (document.textLength > FileFilters.MAX_FILE_SIZE) return
        if (document.textLength == 0) return

        // 6. Montar o SnapshotRequest com os dados necessários
        val relativePath = VfsUtilCore.getRelativePath(file, projectDir) ?: return
        val nameWithoutExtension = file.nameWithoutExtension
        val extension = if (file.extension != null) ".${file.extension}" else ""

        val request = SnapshotRequest(
            relativePath = relativePath,
            fileName = nameWithoutExtension,
            fileExtension = extension,
            content = document.text,
            timestamp = LocalDateTime.now(),
            projectBasePath = projectDir.path
        )

        project.service<SnapshotService>().enqueue(request)
    }
}
