package com.github.pablotzeliks.intellijlocalhistory.util

import java.time.format.DateTimeFormatter

/**
 * Formatos de data/hora centralizados para uso em toda a UI do plugin.
 *
 * Centralizado aqui para garantir consistência entre a lista de snapshots e o
 * título do diff viewer, e facilitar a mudança futura via configurações.
 *
 * TODO: Phase 5 — substituir DISPLAY_FORMATTER por leitura de LocalHistorySettings
 */
object DateFormats {

    /**
     * Formato de exibição padrão para timestamps de snapshot.
     * Exemplo: "11/05/2026 15:06:19"
     */
    val DISPLAY_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    /** Formato usado no nome dos arquivos de snapshot em disco. Compartilhado por SnapshotWriter e SnapshotReader. */
    val SNAPSHOT_TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
}
