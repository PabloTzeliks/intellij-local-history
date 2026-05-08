# Fase 2 — Storage: Escrita de Flat Files + Deduplicação

## Objetivo
Implementar a persistência real dos snapshots no disco usando `java.nio.file`.

## Critérios de Aceite
1. Salvar arquivo cria cópia em `.history/{relativePath}/{name}_{YYYYMMDDHHmmss}.{ext}`.
2. Debounce: saves múltiplos em < 1s geram só 1 snapshot.
3. Deduplicação: saves sem mudança real de conteúdo (mesmo SHA-256) não geram snapshot.
4. `.gitignore` é atualizado automaticamente na primeira execução.

## Arquitetura (Pacotes)

### `storage/SnapshotWriter.kt`
Usa `java.nio.file.Files` para criar diretórios e escrever o conteúdo em disco. Roda em `Dispatchers.IO`.

### `storage/SnapshotReader.kt`
Usa `Files.list` para listar snapshots de um arquivo, parseia o timestamp do nome do arquivo e retorna ordenado.

### `service/SnapshotService.kt`
Serviço `@Service(Service.Level.PROJECT)` que orquestra:
- Recebe `SnapshotRequest` do listener (Fase 1).
- Faz o debounce (ex: usando um `Map<String, Job>` cancelando o anterior).
- Calcula o SHA-256 e compara com o último.
- Delega para o `SnapshotWriter`.

### `service/GitignoreService.kt`
Lê o `.gitignore` na raiz do projeto e anexa `.history/` se não existir.

## Integração
O `DocumentSaveListener` (da Fase 1) passa a chamar `project.service<SnapshotService>().enqueue(request)` em vez de apenas logar.
