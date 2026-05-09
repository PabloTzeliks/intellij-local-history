package com.github.pablotzeliks.intellijlocalhistory.util

import java.util.concurrent.atomic.AtomicInteger

object SnapshotGuard {
    private val restoringCount = AtomicInteger(0)

    /** Retorna true se o plugin está no meio de uma operação de restore */
    fun isActive(): Boolean = restoringCount.get() > 0

    /** Executa um bloco com o guard ativo — qualquer save durante este bloco é ignorado */
    fun <T> withGuard(block: () -> T): T {
        restoringCount.incrementAndGet()
        try {
            return block()
        } finally {
            restoringCount.decrementAndGet()
        }
    }
}
