/**
 * Pacote: storage
 *
 * Camada de persistência — lida com leitura e escrita de arquivos no disco.
 * Usa java.nio.file para escrever os snapshots em .history/ (flat files).
 *
 * - SnapshotWriter: cria o arquivo de snapshot com naming correto
 * - SnapshotReader: lista e lê snapshots existentes de um arquivo
 */
package com.github.pablotzeliks.intellijlocalhistory.storage
