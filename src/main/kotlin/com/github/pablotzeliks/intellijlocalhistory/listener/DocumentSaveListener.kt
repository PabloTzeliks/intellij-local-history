package com.github.pablotzeliks.intellijlocalhistory.listener

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import com.github.pablotzeliks.intellijlocalhistory.util.FileFilters
import com.github.pablotzeliks.intellijlocalhistory.util.SnapshotGuard
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectLocator
<<<<<<< HEAD
import com.intellij.openapi.project.guessProjectDir
=======
>>>>>>> 7cef3ba2fe40ffc48b2275abf5867cf4fafe357f
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
<<<<<<< HEAD
        val projectDir = project.guessProjectDir() ?: return

        // 4. Filtros: binário? muito grande? diretório excluído?
        if (!FileFilters.shouldCapture(file, projectDir.path)) return

        // 5. Montar o SnapshotRequest com os dados necessários
        val relativePath = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, projectDir) ?: return
=======

        // 4. Filtros: binário? muito grande? diretório excluído?
        if (!FileFilters.shouldCapture(file, project.basePath)) return

        // 5. Montar o SnapshotRequest com os dados necessários
        val projectBasePath = project.basePath ?: return
        val relativePath = file.path.removePrefix(projectBasePath).trimStart('/')
>>>>>>> 7cef3ba2fe40ffc48b2275abf5867cf4fafe357f
        val nameWithoutExtension = file.nameWithoutExtension
        val extension = if (file.extension != null) ".${file.extension}" else ""

        val request = SnapshotRequest(
            relativePath = relativePath,
            fileName = nameWithoutExtension,
            fileExtension = extension,
            content = document.text,
            timestamp = LocalDateTime.now(),
<<<<<<< HEAD
            projectBasePath = projectDir.path
=======
            projectBasePath = projectBasePath
>>>>>>> 7cef3ba2fe40ffc48b2275abf5867cf4fafe357f
        )

        // 6. Por enquanto, apenas logar (Fase 2 conectará ao SnapshotService)
        thisLogger().info("Local History: captured save of '$relativePath' (${document.textLength} chars)")
    }
}
