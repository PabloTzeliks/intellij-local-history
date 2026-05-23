package com.github.pablotzeliks.intellijlocalhistory.storage

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotEntry
import com.github.pablotzeliks.intellijlocalhistory.util.DateFormats
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

object SnapshotReader {

    /**
     * Lista todos os snapshots do arquivo [relativePath] em ordem decrescente
     * (snapshot mais recente primeiro).
     *
     * @param relativePath caminho relativo ao projeto (ex: "src/model/Livro.java")
     * @param projectBasePath raiz do projeto
     * @return lista de [SnapshotEntry] ordenada do mais recente ao mais antigo
     */
    fun listSnapshots(relativePath: String, projectBasePath: String): List<SnapshotEntry> {
        val normalized = relativePath.replace('\\', '/')
        val relativeDir = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        val nameWithoutExt = normalized.substringAfterLast('/').substringBeforeLast('.')
        val extension = normalized.substringAfterLast('.', missingDelimiterValue = "")
            .let { if (it.isNotEmpty()) ".$it" else "" }

        val historyDir: Path = if (relativeDir.isEmpty()) {
            Path.of(projectBasePath, ".history")
        } else {
            Path.of(projectBasePath, ".history", *relativeDir.split('/').toTypedArray())
        }

        if (!Files.isDirectory(historyDir)) return emptyList()

        return Files.list(historyDir).use { stream ->
            stream.toList()
                .map { it.toFile() }
                .filter { it.isFile && matchesSnapshot(it.name, nameWithoutExt, extension) }
                .mapNotNull { file -> parseEntry(file, relativePath) }
                .sortedByDescending { it.timestamp }
        }
    }

    /**
     * Lê o conteúdo de texto de um snapshot.
     */
    fun readContent(entry: SnapshotEntry): String =
        entry.file.readText(StandardCharsets.UTF_8)

    // ---- helpers privados ----

    /** Verifica se [fileName] é um snapshot do arquivo [baseName] com [extension]. */
    private fun matchesSnapshot(fileName: String, baseName: String, extension: String): Boolean {
        // Formato esperado: {baseName}_{14 dígitos}{extension}
        val prefix = "${baseName}_"
        val withoutPrefix = fileName.removePrefix(prefix)
        if (withoutPrefix == fileName) return false  // prefix não estava lá
        val withoutSuffix = if (extension.isNotEmpty()) {
            withoutPrefix.removeSuffix(extension)
        } else withoutPrefix
        return withoutSuffix.length == 14 && withoutSuffix.all { it.isDigit() }
    }

    /** Parseia o timestamp do nome do arquivo e retorna um [SnapshotEntry] ou null se inválido. */
    private fun parseEntry(file: File, originalRelativePath: String): SnapshotEntry? {
        return try {
            // Ex: "Livro_20260508150619.java" → "20260508150619"
            val nameWithoutExt = file.nameWithoutExtension
            val timestampStr = nameWithoutExt.takeLast(14)
            val timestamp = LocalDateTime.parse(timestampStr, DateFormats.SNAPSHOT_TIMESTAMP_FORMAT)
            SnapshotEntry(file = file, timestamp = timestamp, originalRelativePath = originalRelativePath)
        } catch (_: DateTimeParseException) {
            null  // arquivo com nome inválido é ignorado silenciosamente
        }
    }
}
