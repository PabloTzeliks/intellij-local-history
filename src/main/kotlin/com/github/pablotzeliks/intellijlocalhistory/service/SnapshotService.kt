package com.github.pablotzeliks.intellijlocalhistory.service

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import com.github.pablotzeliks.intellijlocalhistory.storage.SnapshotReader
import com.github.pablotzeliks.intellijlocalhistory.storage.SnapshotWriter
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import com.intellij.util.messages.Topic

interface SnapshotListener {
    fun onSnapshotAdded(relativePath: String)
    companion object {
        val TOPIC = Topic.create("LocalHistorySnapshotAdded", SnapshotListener::class.java)
    }
}

@Service(Service.Level.PROJECT)
class SnapshotService(
    private val project: Project,
    private val cs: CoroutineScope   // injetado automaticamente pelo IntelliJ Platform (API de coroutines nativa)
) {
    /** Job de debounce por relativePath do arquivo */
    private val debounceJobs = ConcurrentHashMap<String, Job>()

    /** Último hash SHA-256 por relativePath (cache em memória) */
    private val lastHashByPath = ConcurrentHashMap<String, String>()

    /**
     * Mutex por relativePath — garante atomicidade da sequência check→write→update (FIX-001).
     * Populado via computeIfAbsent (atômico) para garantir instância única por chave sob concorrência.
     * Cresce com o número de arquivos distintos processados na sessão: bounded pelo tamanho
     * do projeto e liberado junto com o escopo do serviço ao fechar o projeto.
     */
    private val mutexByPath = ConcurrentHashMap<String, Mutex>()

    companion object {
        private const val DEBOUNCE_DELAY_MS = 500L

        fun getInstance(project: Project): SnapshotService = project.service()
    }

    /**
     * Captura imediatamente, sem debounce.
     * Chamado pelo DocumentChangeListener após seu próprio debounce de inatividade/intervalo.
     * Retorna o Job lançado — útil em testes para aguardar a conclusão via join().
     */
    fun captureNow(request: SnapshotRequest): Job {
        return cs.launch(Dispatchers.IO) {
            processSnapshot(request)
        }
    }

    /**
     * Enfileira um snapshot para o [request] dado, com debounce de 500ms.
     * Chamado na EDT pelo DocumentSaveListener — retorna imediatamente.
     */
    fun enqueue(request: SnapshotRequest) {
        // Cancela qualquer job pendente para este arquivo
        debounceJobs[request.relativePath]?.cancel()

        val job = cs.launch(Dispatchers.IO) {
            delay(DEBOUNCE_DELAY_MS)
            processSnapshot(request)
            // remove(key, value) é atômico: só remove se o job atual ainda for este mesmo.
            // Previne que um job concluído apague um job mais novo que chegou no mesmo instante.
            debounceJobs.remove(request.relativePath, coroutineContext[kotlinx.coroutines.Job])
        }
        debounceJobs[request.relativePath] = job
    }

    private suspend fun processSnapshot(request: SnapshotRequest) {
        val mutex = mutexByPath.computeIfAbsent(request.relativePath) { Mutex() }
        mutex.withLock {
            val newHash = sha256(request.content)

            // Deduplicação: cache em memória
            if (lastHashByPath[request.relativePath] == newHash) {
                thisLogger().debug("Local History: skipping duplicate snapshot for '${request.relativePath}'")
                return@withLock
            }

            // Deduplicação cross-session: ler o hash do último snapshot salvo no disco (se não estiver na memória)
            if (!lastHashByPath.containsKey(request.relativePath)) {
                val snapshots = SnapshotReader.listSnapshots(request.relativePath, request.projectBasePath)
                if (snapshots.isNotEmpty()) {
                    val latestContent = SnapshotReader.readContent(snapshots.first())
                    val latestHash = sha256(latestContent)
                    if (latestHash == newHash) {
                        lastHashByPath[request.relativePath] = newHash
                        thisLogger().debug("Local History: skipping duplicate snapshot for '${request.relativePath}' (cross-session)")
                        return@withLock
                    }
                }
            }

            try {
                SnapshotWriter.write(request)
                lastHashByPath[request.relativePath] = newHash

                // GitignoreService já possui controle interno para rodar apenas uma vez
                project.service<GitignoreService>().ensureHistoryIgnored()

                thisLogger().info("Local History: snapshot saved for '${request.relativePath}'")
                val path = request.relativePath
                ApplicationManager.getApplication().invokeLater {
                    project.messageBus.syncPublisher(SnapshotListener.TOPIC).onSnapshotAdded(path)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                thisLogger().warn("Local History: failed to write snapshot for '${request.relativePath}'", e)
            }
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
