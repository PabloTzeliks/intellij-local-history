# Correções Identificadas no Code Review (pós pre-phase-4-fixes)

Este documento registra os problemas encontrados durante o code review das mudanças implementadas em `pre-phase-4-fixes.md`. Os fixes abaixo devem ser resolvidos antes de avançar para a Fase 4.

---

## RF-001 — FIX-002 implementado incorretamente: `file.length` ≠ conteúdo do documento (CRÍTICO)

**Arquivo:** `FileFilters.kt:40`  
**Severidade:** Crítica — regressão introduzida pelo próprio fix

### Descrição

A guarda adicionada em FIX-002 usa `file.length`, que é o tamanho do arquivo **em disco** (cache do VFS do IntelliJ). O `DocumentSaveListener.beforeDocumentSaving` dispara **antes** do flush para o disco — portanto, para qualquer arquivo novo que ainda não foi salvo nenhuma vez, `file.length` é sempre `0L`, enquanto `document.textLength` pode ser qualquer valor positivo.

O resultado: o primeiro save de qualquer arquivo recém-criado é silenciosamente descartado, perdendo permanentemente o estado inicial do arquivo. O `DocumentChangeListener` tem o mesmo problema: todos os keystrokes de um arquivo novo (que ainda não foi salvo) são rejeitados, pois `file.length` permanece `0` em disco até o primeiro flush.

### Cenário concreto de falha

```
1. Usuário cria "CategoriaService.java" (arquivo novo, 0 bytes em disco)
2. Digita 80 linhas de código (document.textLength ≈ 2.000)
3. Pressiona Ctrl+S
4. beforeDocumentSaving dispara (antes do flush)
5. FileFilters.shouldCapture → file.length == 0L → return false
6. Nenhum snapshot capturado
7. Arquivo é salvo em disco com 80 linhas — mas .history/ fica vazio
```

O `DocumentChangeListener` sofre exatamente o mesmo problema: cada keystroke nesse arquivo durante toda a sessão antes do primeiro save seria rejeitado pela mesma guarda.

### Causa raiz

A verificação de conteúdo vazio deve ser feita sobre **o conteúdo que será capturado** (`document.text`), não sobre o arquivo em disco. A guarda correta pertence aos listeners, onde `document` está disponível, e não ao `FileFilters`, que só enxerga o `VirtualFile`.

### Correção

Remover a guarda de `FileFilters.shouldCapture` e adicioná-la nos dois listeners, sobre `document.textLength`:

**`FileFilters.kt`** — remover a linha adicionada:
```kotlin
// remover:
if (file.length == 0L) return false
```

**`DocumentSaveListener.kt`** — adicionar após o check de `MAX_FILE_SIZE`:
```kotlin
if (document.textLength > FileFilters.MAX_FILE_SIZE) return
if (document.textLength == 0) return   // ← adicionar aqui
```

**`DocumentChangeListener.documentChanged()`** — adicionar junto ao check de tamanho existente:
```kotlin
if (document.textLength > FileFilters.MAX_FILE_SIZE) return
if (document.textLength == 0) return   // ← adicionar aqui
```

O teste `testShouldRejectEmptyFiles` em `FileFiltersTest` deve ser removido (a guarda deixará de existir em `FileFilters`) e um teste equivalente deve ser adicionado cobrindo o comportamento nos listeners.

---

## RF-002 — `catch (e: Exception)` em suspend fun captura `ProcessCanceledException` (LATENTE)

**Arquivo:** `SnapshotService.kt:115`  
**Severidade:** Média — não ativo hoje, mas estruturalmente incorreto para código de coroutine

### Descrição

`processSnapshot` é uma `suspend fun`. O bloco `catch (e: Exception)` dentro dela captura qualquer exceção derivada de `Exception`, incluindo `ProcessCanceledException` — que, em versões recentes do IntelliJ Platform, estende `java.util.concurrent.CancellationException → RuntimeException → Exception`.

Capturar e silenciar uma `ProcessCanceledException` viola o contrato do kotlinx-coroutines: essa exceção é o mecanismo pelo qual a plataforma sinaliza cancelamento de operações (ao fechar o projeto, ao cancelar uma indexação, etc.). Um coroutine que swallow essa exceção termina normalmente quando deveria terminar como cancelado, potencialmente corrompendo o estado do scope pai.

**Por que não é ativo hoje:** `GitignoreService.ensureHistoryIgnored()` — a única chamada dentro do `try` que poderia lançar `ProcessCanceledException` — já tem seu próprio `catch (e: Exception)` interno que absorve a exceção antes de ela propagar. `SnapshotWriter.write()` é NIO puro, sem checkpoints de cancelamento.

**Por que é latente:** qualquer nova chamada de API do IntelliJ adicionada dentro do bloco `try` (que não tenha seu próprio catch interno) propagará `ProcessCanceledException` até a linha 115, onde será swallowada silenciosamente.

### Correção

Seguir a convenção do kotlinx-coroutines para `suspend fun`: nunca capturar `CancellationException` (ou suas subclasses). A correção é re-lançar `CancellationException` se ela for capturada:

```kotlin
} catch (e: Exception) {
    if (e is CancellationException) throw e   // ← nunca suprimir cancelamento
    thisLogger().warn("Local History: failed to write snapshot for '${request.relativePath}'", e)
}
```

Ou alternativamente, restringir o catch para excluir `CancellationException`:

```kotlin
} catch (e: Exception) {
    if (e is CancellationException) throw e
    thisLogger().warn(...)
}
```

A importação necessária é `kotlinx.coroutines.CancellationException`.

---

## RF-003 — `mutexByPath` cresce sem limite: entradas nunca são removidas

**Arquivo:** `SnapshotService.kt:41`  
**Severidade:** Baixa — impacto prático negligível em projetos normais, mas assimétrico com o restante do design

### Descrição

`mutexByPath` acumula uma entrada por `relativePath` distinto já processado na sessão. Ao contrário de `debounceJobs` (que tem `remove()` explícito após cada job completar), não há nenhuma remoção de entradas do mapa de mutexes.

Em uma sessão longa num projeto com centenas de arquivos abertos e fechados, ou num monorepo com milhares de arquivos, o mapa cresce monotonicamente. Cada `Mutex` sem waiters ocupa ~16 bytes de heap — para 10.000 arquivos distintos, isso é ~160 KB, que é negligível e dentro do comportamento aceitável para um serviço de projeto.

O problema mais relevante é de consistência de design: arquivos renomeados ou deletados deixam entradas órfãs que vivem até o encerramento do projeto, sem nenhuma forma de liberação antecipada.

### Correção (baixa prioridade)

Duas abordagens possíveis:

**Opção A — aceitar o crescimento (recomendada para MVP):** documentar explicitamente no comentário do campo que o crescimento é intencional e bounded pelo número de arquivos do projeto:

```kotlin
/**
 * Mutex por relativePath. Cresce com o número de arquivos distintos processados na sessão —
 * bounded pelo tamanho do projeto e liberado com o escopo do serviço ao fechar o projeto.
 */
private val mutexByPath = ConcurrentHashMap<String, Mutex>()
```

**Opção B — limpeza sob demanda (Fase 5):** integrar a remoção de entradas ao `RetentionService` da Fase 5, quando este varrer arquivos deletados do `.history/`.

---

## RF-004 — Testes `SnapshotServiceTest` podem falhar por `.history/` residual entre execuções

**Arquivo:** `SnapshotServiceTest.kt:56`  
**Severidade:** Baixa — afeta apenas confiabilidade dos testes, não código de produção

### Descrição

`testCrossSessionDedupSkipsIfDiskContentMatches` e `testCrossSessionDedupWritesIfDiskContentDiffers` chamam `SnapshotWriter.write()` diretamente, escrevendo arquivos reais em `project.basePath!!/.history/`. O `BasePlatformTestCase` cria e destrói o projeto a cada método de teste, mas não garante limpeza explícita dos arquivos criados via `java.nio.file` diretamente (fora do VFS gerenciado pelo framework).

Se o framework reutilizar o mesmo caminho base entre métodos de teste dentro da mesma classe (comportamento observado em algumas versões do IntelliJ test runner), um snapshot escrito por um método pode ser visível ao próximo, fazendo `snapshotsFor(...)` retornar um count maior do que o esperado.

### Cenário concreto de falha

```
1. testCrossSessionDedupSkipsIfDiskContentMatches executa:
   - SnapshotWriter.write(..., ts(-60)) → cria arquivo em .history/src/Main_*.kt
   - service.captureNow(..., ts(0)).join() → dedup detecta, nenhum novo arquivo
   - assertEquals(1, ...) → PASSA

2. testCrossSessionDedupWritesIfDiskContentDiffers executa no mesmo basePath:
   - SnapshotWriter.write(..., ts(-60)) → cria segundo arquivo (basePath não foi limpo)
   - service.captureNow(content="class B", ts(0)).join() → escreve terceiro arquivo
   - assertEquals(2, ...) → FALHA (snapshotsFor retorna 3)
```

### Correção

Adicionar limpeza do diretório `.history/` no `setUp()` de `SnapshotServiceTest`:

```kotlin
override fun setUp() {
    super.setUp()
    // Garante que cada teste começa sem snapshots residuais de execuções anteriores
    val historyDir = java.nio.file.Path.of(project.basePath!!, ".history")
    if (java.nio.file.Files.exists(historyDir)) {
        java.nio.file.Files.walk(historyDir)
            .sorted(Comparator.reverseOrder())
            .forEach(java.nio.file.Files::delete)
    }
}
```

---

## Ordem de Execução Recomendada

```
RF-001  →  RF-002  →  RF-004  →  RF-003
(crítico)  (médio)    (testes)   (doc/baixo)
```

RF-001 é o único com impacto direto em comportamento de produção e deve ser feito imediatamente. RF-002 é uma boa prática de coroutines que protege contra regressões futuras. RF-004 é correção de infraestrutura de testes. RF-003 pode aguardar a Fase 5.
