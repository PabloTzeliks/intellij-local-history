package com.github.pablotzeliks.intellijlocalhistory.service

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class GitignoreService(private val project: Project) {

    companion object {
        private const val HISTORY_ENTRY = ".history/"
    }

    // AtomicBoolean garante que apenas uma thread executa a escrita,
    // mesmo com saves simultâneos de arquivos diferentes em Dispatchers.IO
    private val gitignoreEnsured = AtomicBoolean(false)

    /**
     * Verifica se `.history/` já está no `.gitignore` da raiz do projeto.
     * Se não estiver, anexa a entrada. Operação idempotente.
     *
     * **DEVE ser chamado de uma thread não-EDT** (ex: `Dispatchers.IO`).
     * Chamar a partir da EDT causa deadlock pois [WriteAction.runAndWait]
     * aguarda a EDT estar livre para executar.
     */
    fun ensureHistoryIgnored() {
        // compareAndSet(false, true) retorna true apenas para a primeira thread que chegar
        // Threads subsequentes vêem true e retornam imediatamente, sem lock
        if (!gitignoreEnsured.compareAndSet(false, true)) return

        val projectDir = project.guessProjectDir() ?: return

        try {
            WriteAction.runAndWait<Throwable> {
                val gitignoreVFile = projectDir.findChild(".gitignore")

                if (gitignoreVFile == null) {
                    // Cria .gitignore com a entrada
                    val newFile = projectDir.createChildData(this, ".gitignore")
                    VfsUtil.saveText(newFile, "$HISTORY_ENTRY\n")
                    thisLogger().info("Local History: .gitignore created with $HISTORY_ENTRY")
                } else {
                    val current = VfsUtil.loadText(gitignoreVFile)
                    if (!current.lines().any { it.trim() == HISTORY_ENTRY.trim() }) {
                        val separator = if (current.endsWith("\n")) "" else "\n"
                        VfsUtil.saveText(gitignoreVFile, "$current$separator$HISTORY_ENTRY\n")
                        thisLogger().info("Local History: .gitignore updated with $HISTORY_ENTRY")
                    } else {
                        thisLogger().debug("Local History: .gitignore already contains $HISTORY_ENTRY")
                    }
                }
            }
        } catch (e: Exception) {
            gitignoreEnsured.set(false)
            thisLogger().warn("Local History: failed to update .gitignore", e)
        }
    }
}
