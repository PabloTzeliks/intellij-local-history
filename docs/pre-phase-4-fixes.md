# Correções Necessárias Antes da Fase 4

Este documento lista os problemas identificados no código atual que devem ser corrigidos **antes** de iniciar a Fase 4 (RestoreSnapshotAction + DeleteSnapshotAction). Executar a Fase 4 sobre esses bugs comprometeria a confiabilidade das novas ações e dificultaria o diagnóstico de problemas futuros.

---

## FIX-001 — Race condition TOCTOU em `processSnapshot()` (CRÍTICO)

**Arquivo:** `SnapshotService.kt:72-110`  
**Severidade:** Crítica — anula a garantia central do SHA-256

### Descrição

`processSnapshot()` é chamado por coroutines independentes em `Dispatchers.IO` — uma vinda de `enqueue()` (save explícito) e outra vinda de `captureNow()` (mudança de documento). Ambas podem processar o mesmo arquivo ao mesmo tempo.

A sequência de deduplicação não é atômica:

```kotlin
if (lastHashByPath[path] == newHash) return   // 1. check (leitura)
...
SnapshotWriter.write(request)                  // 2. write
lastHashByPath[path] = newHash                 // 3. update (escrita)
```

`ConcurrentHashMap` garante atomicidade por operação individual (`get`, `put`), não por sequências de operações. Duas coroutines podem ambas executar o passo 1 antes que qualquer uma execute o passo 3 — ambas veem o hash antigo, ambas passam, e gravam um snapshot idêntico simultaneamente.

**Impacto:** o plugin produz snapshots redundantes pelo mesmo mecanismo que o VS Code Local History, zerando a vantagem do SHA-256 precisamente nos momentos em que os dois listeners disparam próximos (ex: save rápido seguido de pausa de 15s).

### Correção

Introduzir um `Mutex` por arquivo (de `kotlinx.coroutines.sync`) envolvendo os três passos como seção crítica. `processSnapshot()` precisa se tornar `suspend` para usar `Mutex.withLock {}`.

```kotlin
// em SnapshotService:
private val mutexByPath = ConcurrentHashMap<String, Mutex>()

private suspend fun processSnapshot(request: SnapshotRequest) {
    val mutex = mutexByPath.getOrPut(request.relativePath) { Mutex() }
    mutex.withLock {
        // toda a lógica atual de check → write → update aqui dentro
    }
}
```

Os chamadores (`enqueue` e `captureNow`) já lançam coroutines em `Dispatchers.IO`, portanto a mudança para `suspend` não requer alteração neles além de trocar `launch { processSnapshot() }` por `launch { processSnapshot() }` — compatível.

---

## FIX-002 — Ausência de filtro para arquivos de 0 bytes

**Arquivo:** `FileFilters.kt:38`  
**Severidade:** Média — ruído na UI e no histórico de análise

### Descrição

`shouldCapture()` rejeita arquivos maiores que 2MB mas não rejeita arquivos vazios. Quando o IntelliJ cria um novo arquivo, ele começa com 0 bytes e o `DocumentSaveListener` pode capturar esse estado antes de qualquer conteúdo ser inserido. O resultado é um snapshot de 0 bytes no topo da lista — observado nos dados reais do estudo de campo (16 ocorrências em 163 minutos de prova).

Do ponto de vista de análise, um snapshot vazio não informa nada além de "o arquivo existia neste instante", informação redundante com o snapshot de 1s depois (primeiro conteúdo real).

### Correção

Adicionar uma verificação de comprimento zero em `FileFilters.shouldCapture()`, após o check de tamanho máximo:

```kotlin
if (file.length > MAX_FILE_SIZE) return false
if (file.length == 0L) return false   // ← adicionar aqui
```

---

## FIX-003 — Ausência de normalização de line endings no SHA-256

**Arquivo:** `SnapshotService.kt:113`  
**Severidade:** Média — deduplicação cross-session não confiável em Windows

### Descrição

`sha256()` converte o conteúdo diretamente para bytes sem normalizar line endings:

```kotlin
val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
```

O `Document` do IntelliJ usa `\n` internamente para todos os arquivos, independente do SO. No entanto, o conteúdo lido do disco via `SnapshotReader.readContent()` (usado no dedup cross-session) pode conter `\r\n` se o arquivo foi salvo em ambiente Windows ou com `git config core.autocrlf=true`.

**Resultado:** o hash do conteúdo em memória (`\n`) difere do hash do conteúdo em disco (`\r\n`) mesmo que o texto seja idêntico. O dedup cross-session falha silenciosamente e um snapshot redundante é gerado na primeira captura de cada sessão.

### Correção

Normalizar `\r\n` para `\n` antes de calcular o hash, tanto no conteúdo novo quanto no conteúdo do disco (o mesmo método `sha256()` é usado em ambos os casos):

```kotlin
private fun sha256(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val normalized = content.replace("\r\n", "\n")
    val bytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
```

---

## FIX-004 — Comentário incorreto em `enqueue()`

**Arquivo:** `SnapshotService.kt:56`  
**Severidade:** Baixa — inconsistência de documentação interna

### Descrição

O KDoc do método `enqueue()` descreve `"debounce de 1 segundo"`, mas o valor em uso é `DEBOUNCE_DELAY_MS = 500L` (500ms). O valor foi reduzido de 1s para 500ms em 22/05/2026 (ver decisão D-005) e o comentário não foi atualizado.

### Correção

Atualizar o comentário para refletir o valor atual:

```kotlin
/**
 * Enfileira um snapshot para o [request] dado, com debounce de 500ms.
 * ...
 */
```

---

## FIX-005 — SnapshotService sem cobertura de testes

**Arquivo:** `src/test/` (ausência)  
**Severidade:** Alta — componente mais crítico do plugin sem nenhum teste

### Descrição

`SnapshotService` é o núcleo do plugin — é onde o debounce, a deduplicação e o trigger de escrita vivem. As 4 classes de teste existentes (`FileFiltersTest`, `SnapshotGuardTest`, `SnapshotWriterTest`, `SnapshotReaderTest`) cobrem camadas de suporte e storage, mas nenhuma cobre o processamento central.

Em particular:
- O comportamento do debounce de 500ms não é testado
- A deduplicação in-memory não é testada
- A deduplicação cross-session não é testada
- O bug B-001 (FIX-001) não pode ser validado por testes enquanto não houver suite para o serviço

### Correção

Criar `SnapshotServiceTest` cobrindo ao menos:

| Caso | O que verifica |
|---|---|
| Conteúdo idêntico consecutivo | nenhum segundo arquivo é criado |
| Conteúdo diferente consecutivo | dois arquivos são criados |
| Dedup cross-session | primeiro `processSnapshot()` da sessão lê disco e não grava se igual |
| Dois `processSnapshot()` simultâneos com mesmo conteúdo | apenas 1 arquivo gerado (valida FIX-001) |
| Dois `processSnapshot()` simultâneos com conteúdo diferente | 2 arquivos gerados |

---

## Ordem de Execução Recomendada

```
FIX-004  →  FIX-002  →  FIX-003  →  FIX-001  →  FIX-005
(1 linha)   (1 linha)   (1 linha)   (Mutex)       (testes)
```

FIX-004, FIX-002 e FIX-003 são cirúrgicos — cada um é uma ou duas linhas. FIX-001 é a única mudança estrutural (introduz `Mutex`, torna `processSnapshot` `suspend`). FIX-005 deve vir depois de FIX-001 para que o teste de concorrência possa validar a correção.
