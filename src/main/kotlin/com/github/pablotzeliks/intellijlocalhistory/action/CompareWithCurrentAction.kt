package com.github.pablotzeliks.intellijlocalhistory.action

import com.github.pablotzeliks.intellijlocalhistory.LocalHistoryBundle
import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotEntry
import com.github.pablotzeliks.intellijlocalhistory.storage.SnapshotReader
import com.github.pablotzeliks.intellijlocalhistory.ui.LocalHistoryPanel
import com.github.pablotzeliks.intellijlocalhistory.util.DateFormats
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

class CompareWithCurrentAction : AnAction(
    LocalHistoryBundle.messagePointer("action.compare"),
    LocalHistoryBundle.messagePointer("action.compare"),
    com.intellij.icons.AllIcons.Actions.Diff
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Local History") ?: return
        val panel = toolWindow.contentManager.contents.firstOrNull()?.component as? LocalHistoryPanel ?: return
        
        val entry = panel.getSelectedEntry() ?: return
        val currentFile = panel.getCurrentFile() ?: return
        
        showDiff(project, entry, currentFile)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Local History")
        val panel = toolWindow?.contentManager?.contents?.firstOrNull()?.component as? LocalHistoryPanel
        
        e.presentation.isEnabled = panel != null && panel.getSelectedEntry() != null && panel.getCurrentFile() != null
    }

    companion object {
        fun showDiff(project: Project, entry: SnapshotEntry, currentFile: VirtualFile) {
            try {
                // Ler conteúdo do snapshot (I/O). Como abrir o diff é uma ação iniciada pelo usuário, 
                // e os arquivos capturados são < 2MB, é aceitável a leitura rápida aqui antes de abrir o modal.
                val snapshotContent = SnapshotReader.readContent(entry)
                val document = FileDocumentManager.getInstance().getDocument(currentFile) ?: return

                val diffContentFactory = DiffContentFactory.getInstance()
                val leftContent = diffContentFactory.create(project, snapshotContent, currentFile.fileType)
                val rightContent = diffContentFactory.create(project, document)

                val title = "Local History \u2014 ${currentFile.name}"
                val leftTitle = "Snapshot (${entry.timestamp.format(DateFormats.DISPLAY_FORMATTER)})"
                val rightTitle = "Current"

                val request = SimpleDiffRequest(title, leftContent, rightContent, leftTitle, rightTitle)

                DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT)
            } catch (ex: Exception) {
                thisLogger().warn("Failed to open diff for snapshot ${entry.file.name}", ex)
            }
        }
    }
}
