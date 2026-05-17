package com.github.pablotzeliks.intellijlocalhistory.service

import com.github.pablotzeliks.intellijlocalhistory.ui.LocalHistoryPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager

class LocalHistoryStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.messageBus.connect(project).subscribe(
            SnapshotListener.TOPIC,
            object : SnapshotListener {
                override fun onSnapshotAdded(relativePath: String) {
                    val toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow("Local History") ?: return
                    val panel = toolWindow.contentManager.contents
                        .firstOrNull()?.component as? LocalHistoryPanel ?: return

                    val activeFile = panel.getCurrentFile() ?: return
                    val activeDir = project.guessProjectDir() ?: return
                    val activeRelative = VfsUtilCore.getRelativePath(activeFile, activeDir) ?: return

                    if (activeRelative == relativePath) {
                        panel.refresh()
                    }
                }
            }
        )
    }
}
