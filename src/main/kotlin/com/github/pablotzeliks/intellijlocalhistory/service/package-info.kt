/**
 * Pacote: service
 *
 * Contém os serviços de nível de projeto (Project-level Services).
 * Services são singletons gerenciados pela IDE — um por projeto aberto.
 *
 * - SnapshotService: orquestra a lógica de snapshot (debounce, dedup, delegação)
 * - GitignoreService: gerencia a inclusão de .history/ no .gitignore
 * - RetentionService: limpeza automática de snapshots antigos (Fase 5)
 */
package com.github.pablotzeliks.intellijlocalhistory.service
