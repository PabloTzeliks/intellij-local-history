package com.github.pablotzeliks.intellijlocalhistory.ui

import com.github.pablotzeliks.intellijlocalhistory.service.LocalHistoryUiScopeService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class LocalHistoryToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // O CoroutineScope vem do serviço gerenciado pela plataforma — sem memory leak
        val cs = project.service<LocalHistoryUiScopeService>().scope

        val panel = LocalHistoryPanel(project, cs)

        val contentManager = toolWindow.contentManager
        val content = contentManager.factory.createContent(panel, "", false)
        contentManager.addContent(content)

        // Listener conectado ao disposable da Tool Window — desregistrado ao fechar a janela
        project.messageBus.connect(toolWindow.disposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    panel.loadFile(event.newFile)
                }
            }
        )

        // Carregar arquivo já aberto (se houver) ao inicializar a Tool Window
        val initialFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        panel.loadFile(initialFile)
    }
}
