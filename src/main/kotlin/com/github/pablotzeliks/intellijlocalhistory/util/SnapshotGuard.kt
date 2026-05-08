package com.github.pablotzeliks.intellijlocalhistory.util

import java.util.concurrent.atomic.AtomicBoolean

object SnapshotGuard {
    private val restoring = AtomicBoolean(false)

    /** Retorna true se o plugin está no meio de uma operação de restore */
    fun isActive(): Boolean = restoring.get()

    /** Executa um bloco com o guard ativo — qualquer save durante este bloco é ignorado */
    fun <T> withGuard(block: () -> T): T {
        restoring.set(true)
        try {
            return block()
        } finally {
            restoring.set(false)
        }
    }
}
