package com.github.pablotzeliks.intellijlocalhistory.service

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil

@Service(Service.Level.PROJECT)
class GitignoreService(private val project: Project) {

    companion object {
        private const val HISTORY_ENTRY = ".history/"
    }

    private var gitignoreEnsured = false

    /**
     * Verifica se `.history/` já está no `.gitignore` da raiz do projeto.
     * Se não estiver, anexa a entrada. Operação idempotente.
     *
     * **DEVE ser chamado de uma thread não-EDT** (ex: `Dispatchers.IO`).
     * Chamar a partir da EDT causa deadlock pois [WriteAction.runAndWait]
     * aguarda a EDT estar livre para executar.
     */
    fun ensureHistoryIgnored() {
        if (gitignoreEnsured) return

        val projectDir = project.guessProjectDir() ?: return

        WriteAction.runAndWait<Throwable> {
            val gitignoreVFile = projectDir.findChild(".gitignore")

            if (gitignoreVFile == null) {
                // Cria .gitignore com a entrada
                val newFile = projectDir.createChildData(this, ".gitignore")
                VfsUtil.saveText(newFile, "$HISTORY_ENTRY\n")
            } else {
                val current = VfsUtil.loadText(gitignoreVFile)
                if (!current.lines().any { it.trim() == HISTORY_ENTRY.trim() }) {
                    val separator = if (current.endsWith("\n")) "" else "\n"
                    VfsUtil.saveText(gitignoreVFile, "$current$separator$HISTORY_ENTRY\n")
                }
            }
        }

        gitignoreEnsured = true
        thisLogger().info("Local History: .gitignore updated with $HISTORY_ENTRY")
    }
}
