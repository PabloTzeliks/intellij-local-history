package com.github.pablotzeliks.intellijlocalhistory.storage

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import com.github.pablotzeliks.intellijlocalhistory.util.DateFormats
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object SnapshotWriter {

    /**
     * Escreve o conteúdo do [request] como um flat file em .history/.
     * Cria os diretórios intermediários se necessário.
     * Deve ser chamado apenas a partir de Dispatchers.IO.
     *
     * Formato do path gerado:
     *   {projectBasePath}/.history/{dirRelativo}/{nome}_{timestamp}.{ext}
     *
     * Exemplo:
     *   request.relativePath = "src/model/Livro.java"
     *   → .history/src/model/Livro_20260508150619.java
     */
    fun write(request: SnapshotRequest) {
        val timestamp = request.timestamp.format(DateFormats.SNAPSHOT_TIMESTAMP_FORMAT)
        val snapshotFileName = "${request.fileName}_$timestamp${request.fileExtension}"

        // Diretório relativo sem o nome do arquivo (ex: "src/model")
        val relativeDir = request.relativePath
            .replace('\\', '/')
            .substringBeforeLast('/', missingDelimiterValue = "")

        val snapshotDir: Path = if (relativeDir.isEmpty()) {
            Path.of(request.projectBasePath, ".history")
        } else {
            Path.of(request.projectBasePath, ".history", *relativeDir.split('/').toTypedArray())
        }

        Files.createDirectories(snapshotDir)

        val snapshotFile = snapshotDir.resolve(snapshotFileName)
        Files.writeString(snapshotFile, request.content, StandardCharsets.UTF_8)
    }
}
