# Fase 3 — Tool Window e Lista de Snapshots

## Objetivo
Criar a interface gráfica para visualizar os snapshots.

## Critérios de Aceite
1. Uma "Tool Window" chamada "Local History" aparece no rodapé ou lateral.
2. Ao focar em um arquivo no editor, a lista de snapshots dele aparece na Tool Window.
3. A lista mostra a data e tamanho.
4. Ação "Compare with Current" abre o diff nativo.

## Arquitetura

### `ui/LocalHistoryToolWindowFactory.kt`
Implementa `ToolWindowFactory`. Registrada no `plugin.xml`.
Usa `FileEditorManagerListener` para ouvir trocas de arquivo ativo e atualizar a view.

### `ui/LocalHistoryPanel.kt`
Usa Kotlin UI DSL v2. Contém uma `JBList` para exibir os snapshots.
Quando o arquivo ativo muda, chama `SnapshotReader` para popular a lista.

### `action/CompareWithCurrentAction.kt`
Usa `DiffManager.getInstance().showDiff` para mostrar lado a lado o conteúdo do snapshot e o atual.
