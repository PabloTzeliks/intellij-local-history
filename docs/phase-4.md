# Fase 4 — Restauração de Snapshots

## Objetivo
Permitir que o usuário substitua o conteúdo do arquivo atual pelo conteúdo de um snapshot antigo.

## Critérios de Aceite
1. Botão/Ação "Restore" na Tool Window.
2. Pede confirmação antes de sobrescrever.
3. A sobrescrita usa `WriteCommandAction` na EDT.
4. O `SnapshotGuard` é ativado para impedir que esse salvamento gere um snapshot indesejado.

## Arquitetura

### `action/RestoreSnapshotAction.kt`
Ação principal que:
1. Pede confirmação via `Messages.showYesNoDialog`.
2. Ativa o guard: `SnapshotGuard.withGuard { ... }`.
3. Executa a escrita: `WriteCommandAction.runWriteCommandAction(project) { document.setText(snapshotText) }`.

### `action/DeleteSnapshotAction.kt` (Opcional, bom ter)
Remove fisicamente o flat file de snapshot e atualiza a lista da UI.
