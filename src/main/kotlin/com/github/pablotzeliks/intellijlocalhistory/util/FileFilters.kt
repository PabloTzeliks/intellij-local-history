package com.github.pablotzeliks.intellijlocalhistory.util

import com.intellij.openapi.vfs.VirtualFile

object FileFilters {

    /** Tamanho máximo de arquivo para capturar (2MB) */
    private const val MAX_FILE_SIZE: Long = 2 * 1024 * 1024

    /** Diretórios cujos arquivos são SEMPRE ignorados */
    private val EXCLUDED_DIRS = listOf(
        ".history",
        ".git",
        ".idea",
        ".gradle",
        "node_modules",
        "build",
        "target",
        "out",
        "dist"
    )

    /**
     * Retorna true se o arquivo DEVE ser capturado.
     * Retorna false se deve ser ignorado.
     *
     * @param file o VirtualFile sendo salvo
     * @param projectBasePath caminho raiz do projeto
     */
    fun shouldCapture(file: VirtualFile, projectBasePath: String?): Boolean {
        // 1. Sem path de projeto → não capturar
        if (projectBasePath == null) return false

        // 2. Arquivo binário → não capturar
        if (file.fileType.isBinary) return false

        // 3. Arquivo muito grande → não capturar
        if (file.length > MAX_FILE_SIZE) return false

        // 4. Verificar se está dentro de diretório excluído
        val relativePath = file.path.removePrefix(projectBasePath).trimStart('/', '\\')
        
        val pathSegments = relativePath.split('/', '\\')
        if (pathSegments.any { it in EXCLUDED_DIRS }) {
            return false
        }

        return true
    }
}
