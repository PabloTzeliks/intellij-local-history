package com.github.pablotzeliks.intellijlocalhistory.model

import java.io.File
import java.time.LocalDateTime

data class SnapshotEntry(
    val file: File,
    val timestamp: LocalDateTime,
    val originalRelativePath: String
)
