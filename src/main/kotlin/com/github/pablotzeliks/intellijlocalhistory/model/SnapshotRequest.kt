package com.github.pablotzeliks.intellijlocalhistory.model

import java.time.LocalDateTime

data class SnapshotRequest(
    val relativePath: String,
    val fileName: String,
    val fileExtension: String,
    val content: String,
    val timestamp: LocalDateTime,
    val projectBasePath: String
)
