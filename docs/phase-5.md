# Fase 5 — Retenção e Configurações

## Objetivo
Impedir que a pasta `.history/` cresça infinitamente e dar opções de configuração ao usuário.

## Critérios de Aceite
1. Menu em Settings > Tools > Local History.
2. Opções para dias limite, número máximo de revisões e caminhos ignorados.
3. Serviço de background roda ao abrir projeto e purga snapshots antigos baseados nas regras.

## Arquitetura

### `settings/LocalHistorySettings.kt`
Usa `PersistentStateComponent` para armazenar as preferências em XML (ex: `daysLimit`, `maxRevisions`).

### `settings/LocalHistoryConfigurable.kt`
Implementa `Configurable`. A UI deve usar Kotlin UI DSL v2.

### `service/RetentionService.kt`
Roda assincronamente (ex: `project.coroutineScope.launch(Dispatchers.IO)`) periodicamente. Faz scan na `.history/` apagando arquivos cujo timestamp seja anterior ao limite ou cujo índice passe do limite por arquivo.
