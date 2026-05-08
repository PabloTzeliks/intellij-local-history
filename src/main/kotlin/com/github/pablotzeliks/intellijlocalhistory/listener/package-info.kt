/**
 * Pacote: listener
 *
 * Contém os listeners que interceptam eventos da IDE.
 * O principal é o DocumentSaveListener, que captura cada salvamento de arquivo
 * e enfileira um SnapshotRequest para processamento assíncrono.
 *
 * Listeners são registrados no plugin.xml em <applicationListeners>.
 */
package com.github.pablotzeliks.intellijlocalhistory.listener
