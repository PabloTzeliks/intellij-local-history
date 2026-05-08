package com.github.pablotzeliks.intellijlocalhistory

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

/**
 * Bundle de internacionalização (i18n) do plugin Local History.
 *
 * Centraliza todas as strings exibidas na UI (labels, mensagens, tooltips).
 * As strings ficam definidas no arquivo resources/messages/LocalHistoryBundle.properties.
 *
 * Uso:
 *   LocalHistoryBundle["chave.da.string"]
 *   LocalHistoryBundle.message("chave.da.string", param1, param2)
 */
@NonNls
private const val BUNDLE = "messages.LocalHistoryBundle"

object LocalHistoryBundle : DynamicBundle(BUNDLE) {

    /**
     * Atalho via operador [] para buscar uma string do bundle.
     * Exemplo: LocalHistoryBundle["toolWindow.title"]
     */
    operator fun get(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)

    /**
     * Busca uma string do bundle com parâmetros de formatação.
     * Exemplo: LocalHistoryBundle.message("snapshot.created", fileName, timestamp)
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)

    /**
     * Versão lazy da busca — retorna um Supplier em vez do valor direto.
     * Útil para registros em plugin.xml que aceitam lazy messages.
     */
    @Suppress("unused")
    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getLazyMessage(key, *params)
}
