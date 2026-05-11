# Fase 2 — Storage: Escrita de Flat Files + Deduplicação

## Objetivo

Implementar a persistência real dos snapshots em disco.
Ao final desta fase, cada vez que um arquivo for salvo na IDE, uma cópia timestamped será criada
em `.history/` no formato compatível com o VS Code Local History.

## Pré-requisitos

- Leia `AGENTS.md` na raiz do projeto (regras críticas, stack, estrutura)
- Leia `docs/decisions.md` — especialmente D-002, D-003, D-005 e D-006
- A Fase 1 já foi completada: `DocumentSaveListener`, `SnapshotGuard`, `FileFilters`, `SnapshotRequest` e os respectivos testes existem e compilam

## Critérios de Aceite

1. Salvar um arquivo texto cria cópia em `.history/{relativePath-sem-nome}/{name}_{YYYYMMDDHHmmss}.{ext}`
2. Debounce: saves do mesmo arquivo em menos de 1 segundo geram apenas 1 snapshot (o último)
3. Deduplicação: se o conteúdo não mudou (mesmo SHA-256 do último snapshot), nenhum arquivo é escrito
4. Na primeira vez que `.history/` é criada, `.gitignore` na raiz do projeto ganha a entrada `.history/` (se ainda não tiver)
5. `./gradlew build` compila sem erros
6. Testes unitários de `SnapshotWriterTest` passam

---

## Arquivos a Criar

### 1. `src/main/kotlin/.../model/SnapshotEntry.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.model`

Data class imutável que representa um snapshot já persistido no disco.
Usada pelo `SnapshotReader` (Fase 2) e pela Tool Window (Fase 3).

```kotlin
data class SnapshotEntry(
    val file: java.io.File,             // arquivo físico em .history/
    val timestamp: java.time.LocalDateTime,  // parsed do nome do arquivo
    val originalRelativePath: String    // ex: "src/main/kotlin/Main.kt"
)
```

**Regras:**
- Sem lógica de negócio aqui — apenas dados
- `file` é um `java.io.File` apontando para o flat file em `.history/`
- `originalRelativePath` é o caminho do arquivo fonte (não do snapshot)

---

### 2. `src/main/kotlin/.../storage/SnapshotWriter.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.storage`

Responsável exclusivamente por escrever um snapshot no disco.
**Não decide se deve escrever** — essa lógica fica no `SnapshotService`.

```kotlin
package com.github.pablotzeliks.intellijlocalhistory.storage

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter

object SnapshotWriter {

    private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /**
     * Escreve o conteúdo do [request] como um flat file em .history/.
     * Cria os diretórios intermediários se necessário.
     * Deve ser chamado apenas a partir de Dispatchers.IO.
     *
     * Formato do path gerado:
     *   {projectBasePath}/.history/{dirRelativo}/{nome}_{timestamp}.{ext}
     *
     * Exemplo:
     *   request.relativePath = "src/model/Livro.java"
     *   → .history/src/model/Livro_20260508150619.java
     */
    fun write(request: SnapshotRequest) {
        val timestamp = request.timestamp.format(TIMESTAMP_FORMAT)
        val snapshotFileName = "${request.fileName}_$timestamp${request.fileExtension}"

        // Diretório relativo sem o nome do arquivo (ex: "src/model")
        val relativeDir = request.relativePath
            .replace('\\', '/')
            .substringBeforeLast('/', missingDelimiterValue = "")

        val snapshotDir: Path = if (relativeDir.isEmpty()) {
            Path.of(request.projectBasePath, ".history")
        } else {
            Path.of(request.projectBasePath, ".history", *relativeDir.split('/').toTypedArray())
        }

        Files.createDirectories(snapshotDir)

        val snapshotFile = snapshotDir.resolve(snapshotFileName)
        Files.writeString(snapshotFile, request.content, StandardCharsets.UTF_8)
    }
}
```

**Regras:**
- Usar `java.nio.file` — NÃO usar VFS (ver decisão D-003)
- Usar `StandardCharsets.UTF_8` explicitamente
- `Files.createDirectories` é idempotente (não lança exceção se já existir)
- Este objeto é stateless — sem campos mutáveis
- Imports necessários: `java.nio.charset.StandardCharsets`, `java.nio.file.Files`, `java.nio.file.Path`, `java.time.format.DateTimeFormatter`

---

### 3. `src/main/kotlin/.../storage/SnapshotReader.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.storage`

Responsável por listar e ler snapshots existentes em `.history/`.
Usado pela Tool Window na Fase 3.

```kotlin
package com.github.pablotzeliks.intellijlocalhistory.storage

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotEntry
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object SnapshotReader {

    private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /**
     * Lista todos os snapshots do arquivo [relativePath] em ordem decrescente
     * (snapshot mais recente primeiro).
     *
     * @param relativePath caminho relativo ao projeto (ex: "src/model/Livro.java")
     * @param projectBasePath raiz do projeto
     * @return lista de [SnapshotEntry] ordenada do mais recente ao mais antigo
     */
    fun listSnapshots(relativePath: String, projectBasePath: String): List<SnapshotEntry> {
        val normalized = relativePath.replace('\\', '/')
        val relativeDir = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        val nameWithoutExt = normalized.substringAfterLast('/').substringBeforeLast('.')
        val extension = normalized.substringAfterLast('.', missingDelimiterValue = "")
            .let { if (it.isNotEmpty()) ".$it" else "" }

        val historyDir: Path = if (relativeDir.isEmpty()) {
            Path.of(projectBasePath, ".history")
        } else {
            Path.of(projectBasePath, ".history", *relativeDir.split('/').toTypedArray())
        }

        if (!Files.isDirectory(historyDir)) return emptyList()

        return Files.list(historyDir).use { stream ->
            stream
                .map { it.toFile() }
                .filter { it.isFile && matchesSnapshot(it.name, nameWithoutExt, extension) }
                .mapNotNull { file -> parseEntry(file, relativePath) }
                .sortedByDescending { it.timestamp }
                .toList()
        }
    }

    /**
     * Lê o conteúdo de texto de um snapshot.
     */
    fun readContent(entry: SnapshotEntry): String =
        entry.file.readText(StandardCharsets.UTF_8)

    // ---- helpers privados ----

    /** Verifica se [fileName] é um snapshot do arquivo [baseName] com [extension]. */
    private fun matchesSnapshot(fileName: String, baseName: String, extension: String): Boolean {
        // Formato esperado: {baseName}_{14 dígitos}{extension}
        val prefix = "${baseName}_"
        val withoutPrefix = fileName.removePrefix(prefix)
        if (withoutPrefix == fileName) return false  // prefix não estava lá
        val withoutSuffix = if (extension.isNotEmpty()) {
            withoutPrefix.removeSuffix(extension)
        } else withoutPrefix
        return withoutSuffix.length == 14 && withoutSuffix.all { it.isDigit() }
    }

    /** Parseia o timestamp do nome do arquivo e retorna um [SnapshotEntry] ou null se inválido. */
    private fun parseEntry(file: File, originalRelativePath: String): SnapshotEntry? {
        return try {
            // Ex: "Livro_20260508150619.java" → "20260508150619"
            val nameWithoutExt = file.nameWithoutExtension
            val timestampStr = nameWithoutExt.takeLast(14)
            val timestamp = LocalDateTime.parse(timestampStr, TIMESTAMP_FORMAT)
            SnapshotEntry(file = file, timestamp = timestamp, originalRelativePath = originalRelativePath)
        } catch (_: DateTimeParseException) {
            null  // arquivo com nome inválido é ignorado silenciosamente
        }
    }
}
```

**Regras:**
- `Files.list` deve ser fechado com `.use { }` para evitar resource leak
- Retorno sempre ordenado do mais recente ao mais antigo (a Tool Window exibe assim)
- Arquivos com nome inválido (sem timestamp parseable) são ignorados silenciosamente com `null`

---

### 4. `src/main/kotlin/.../service/SnapshotService.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.service`

Serviço de nível de projeto que orquestra toda a lógica de persistência.
É o único ponto de entrada após o listener.

```kotlin
package com.github.pablotzeliks.intellijlocalhistory.service

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import com.github.pablotzeliks.intellijlocalhistory.storage.SnapshotWriter
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SnapshotService(
    private val project: Project,
    private val cs: CoroutineScope   // injetado automaticamente pelo IntelliJ Platform (API de coroutines nativa)
) {
    /** Job de debounce por relativePath do arquivo */
    private val debounceJobs = ConcurrentHashMap<String, Job>()

    /** Último hash SHA-256 por relativePath (cache em memória) */
    private val lastHashByPath = ConcurrentHashMap<String, String>()

    companion object {
        private const val DEBOUNCE_DELAY_MS = 1_000L

        fun getInstance(project: Project): SnapshotService = project.service()
    }

    /**
     * Enfileira um snapshot para o [request] dado, com debounce de 1 segundo.
     * Chamado na EDT pelo DocumentSaveListener — retorna imediatamente.
     */
    fun enqueue(request: SnapshotRequest) {
        // Cancela qualquer job pendente para este arquivo
        debounceJobs[request.relativePath]?.cancel()

        debounceJobs[request.relativePath] = cs.launch(Dispatchers.IO) {
            delay(DEBOUNCE_DELAY_MS)
            processSnapshot(request)
        }
    }

    private fun processSnapshot(request: SnapshotRequest) {
        val newHash = sha256(request.content)

        // Deduplicação: mesmo conteúdo → não escreve
        if (lastHashByPath[request.relativePath] == newHash) {
            thisLogger().debug("Local History: skipping duplicate snapshot for '${request.relativePath}'")
            return
        }

        try {
            // Na primeira escrita, atualiza o .gitignore
            val isFirstWrite = !lastHashByPath.containsKey(request.relativePath)
            SnapshotWriter.write(request)
            lastHashByPath[request.relativePath] = newHash

            if (isFirstWrite) {
                project.service<GitignoreService>().ensureHistoryIgnored()
            }

            thisLogger().info("Local History: snapshot saved for '${request.relativePath}'")
        } catch (e: Exception) {
            thisLogger().warn("Local History: failed to write snapshot for '${request.relativePath}'", e)
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
```

**Regras:**
- `@Service(Service.Level.PROJECT)` — um por projeto aberto, gerenciado pelo DI da IDE
- O `CoroutineScope cs` é injetado pelo IntelliJ Platform automaticamente no construtor — **não criar `CoroutineScope` manualmente** para evitar memory leak (ver decisão D-001)
- `debounceJobs` e `lastHashByPath` são `ConcurrentHashMap` porque coroutines em `Dispatchers.IO` podem operar em threads diferentes
- `delay(1_000L)` em `Dispatchers.IO` é suspenso (não bloqueia thread)
- O `isFirstWrite` usa `!containsKey` antes do put para detectar a **primeira vez que um dado arquivo recebe snapshot**, não a primeira vez global — assim `GitignoreService` só é chamado uma vez por arquivo novo, mas na prática a verificação real está no `GitignoreService` (que é idempotente)
- O bloco `try/catch` garante que falha de I/O não propaga e derruba a coroutine
- Imports necessários: `kotlinx.coroutines.*`, `java.security.MessageDigest`, `java.util.concurrent.ConcurrentHashMap`

---

### 5. `src/main/kotlin/.../service/GitignoreService.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.service`

Responsável por garantir que `.history/` conste no `.gitignore` do projeto.
**Usa VFS** (e não `java.nio`) para que a aba de Git da IDE detecte a mudança instantaneamente (ver decisão D-003).

```kotlin
package com.github.pablotzeliks.intellijlocalhistory.service

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil

@Service(Service.Level.PROJECT)
class GitignoreService(private val project: Project) {

    private val HISTORY_ENTRY = ".history/"

    /**
     * Verifica se `.history/` já está no `.gitignore` da raiz do projeto.
     * Se não estiver, anexa a entrada. Operação idempotente.
     *
     * Deve ser chamado a partir de uma coroutine em Dispatchers.IO,
     * mas a escrita VFS é feita via WriteAction (volta para EDT internamente).
     */
    fun ensureHistoryIgnored() {
        val projectDir = project.guessProjectDir() ?: return

        // Tenta encontrar ou criar .gitignore via VFS
        val gitignoreVFile = projectDir.findChild(".gitignore")

        WriteAction.runAndWait<Throwable> {
            if (gitignoreVFile == null) {
                // Cria .gitignore com a entrada
                val newFile = projectDir.createChildData(this, ".gitignore")
                VfsUtil.saveText(newFile, "$HISTORY_ENTRY\n")
            } else {
                val current = VfsUtil.loadText(gitignoreVFile)
                if (!current.lines().any { it.trim() == HISTORY_ENTRY.trim() }) {
                    val separator = if (current.endsWith("\n")) "" else "\n"
                    VfsUtil.saveText(gitignoreVFile, "$current$separator$HISTORY_ENTRY\n")
                }
            }
        }

        thisLogger().info("Local History: .gitignore updated with $HISTORY_ENTRY")
    }
}
```

**Regras:**
- Usar `WriteAction.runAndWait<Throwable>` para escrita VFS fora da EDT (chamado de `Dispatchers.IO`)
- Verificação por `it.trim() == ".history"` para tolerar espaços extras na linha
- Se `.gitignore` não existir, cria um novo
- A operação é idempotente — chamar múltiplas vezes não duplica a entrada
- Imports necessários: `com.intellij.openapi.application.WriteAction`, `com.intellij.openapi.vfs.VfsUtil`, `com.intellij.openapi.project.guessProjectDir`

---

## Arquivos a Modificar

### 6. `src/main/kotlin/.../listener/DocumentSaveListener.kt`

Substituir o bloco de log (linha 52-53) para delegar ao `SnapshotService`:

```kotlin
// ANTES (Fase 1):
thisLogger().info("Local History: captured save of '$relativePath' (${document.textLength} chars)")

// DEPOIS (Fase 2):
project.service<SnapshotService>().enqueue(request)
```

Adicionar o import necessário:
```kotlin
import com.github.pablotzeliks.intellijlocalhistory.service.SnapshotService
import com.intellij.openapi.components.service
```

O restante do listener permanece idêntico.

---

### 7. `src/main/resources/META-INF/plugin.xml`

Adicionar os dois services dentro do bloco `<extensions defaultExtensionNs="com.intellij">`:

```xml
<extensions defaultExtensionNs="com.intellij">
    <projectService
        serviceImplementation="com.github.pablotzeliks.intellijlocalhistory.service.SnapshotService"/>
    <projectService
        serviceImplementation="com.github.pablotzeliks.intellijlocalhistory.service.GitignoreService"/>
</extensions>
```

**Explicação:**
- `projectService` = um por projeto aberto (não confundir com `applicationService`)
- O IntelliJ Platform injeta automaticamente `Project` e `CoroutineScope` no construtor do `SnapshotService`
- Sem esse registro, `project.service<SnapshotService>()` lança `ServiceNotFoundException` em runtime

---

## Testes a Criar

### 8. `src/test/kotlin/.../storage/SnapshotWriterTest.kt`

Estes testes usam apenas `java.nio.file` — **não precisam** de `BasePlatformTestCase` e rodam rapidamente.

```kotlin
package com.github.pablotzeliks.intellijlocalhistory.storage

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime

class SnapshotWriterTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun makeRequest(
        relativePath: String = "src/Main.kt",
        content: String = "fun main() {}",
        timestamp: LocalDateTime = LocalDateTime.of(2026, 5, 8, 15, 6, 19)
    ) = SnapshotRequest(
        relativePath = relativePath,
        fileName = relativePath.substringAfterLast('/').substringBeforeLast('.'),
        fileExtension = ".${relativePath.substringAfterLast('.')}",
        content = content,
        timestamp = timestamp,
        projectBasePath = tempDir.root.absolutePath
    )

    @Test
    fun `write creates snapshot file in correct location`() {
        val request = makeRequest()
        SnapshotWriter.write(request)

        val expected = tempDir.root.resolve(".history/src/Main_20260508150619.kt")
        assertTrue("Snapshot file should exist", expected.exists())
    }

    @Test
    fun `write creates intermediate directories`() {
        val request = makeRequest(relativePath = "a/b/c/Deep.java")
        SnapshotWriter.write(request)

        val dir = tempDir.root.resolve(".history/a/b/c")
        assertTrue("Nested directories should be created", dir.isDirectory)
    }

    @Test
    fun `write preserves file content with UTF-8 encoding`() {
        val content = "// áéíóú ñ 日本語"
        val request = makeRequest(content = content)
        SnapshotWriter.write(request)

        val snapshotDir = tempDir.root.resolve(".history/src")
        val snapshot = snapshotDir.listFiles()!!.first()
        val read = Files.readString(snapshot.toPath(), StandardCharsets.UTF_8)
        assertEquals(content, read)
    }

    @Test
    fun `write uses correct timestamp format in filename`() {
        val ts = LocalDateTime.of(2026, 12, 31, 23, 59, 59)
        val request = makeRequest(timestamp = ts)
        SnapshotWriter.write(request)

        val snapshotDir = tempDir.root.resolve(".history/src")
        val name = snapshotDir.listFiles()!!.first().name
        assertTrue("Filename should contain timestamp", name.contains("20261231235959"))
    }

    @Test
    fun `write handles file at project root (no subdirectory)`() {
        val request = makeRequest(relativePath = "README.md")
        SnapshotWriter.write(request)

        val expected = tempDir.root.resolve(".history/README_20260508150619.md")
        assertTrue("Root-level snapshot should be in .history directly", expected.exists())
    }
}
```

### 9. `src/test/kotlin/.../storage/SnapshotReaderTest.kt`

```kotlin
package com.github.pablotzeliks.intellijlocalhistory.storage

import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class SnapshotReaderTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun writeSnapshot(relativePath: String, timestamp: LocalDateTime, content: String = "x") {
        val request = SnapshotRequest(
            relativePath = relativePath,
            fileName = relativePath.substringAfterLast('/').substringBeforeLast('.'),
            fileExtension = ".${relativePath.substringAfterLast('.')}",
            content = content,
            timestamp = timestamp,
            projectBasePath = tempDir.root.absolutePath
        )
        SnapshotWriter.write(request)
    }

    @Test
    fun `listSnapshots returns empty list when no snapshots exist`() {
        val result = SnapshotReader.listSnapshots("src/Main.kt", tempDir.root.absolutePath)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `listSnapshots returns snapshots ordered newest first`() {
        val old = LocalDateTime.of(2026, 1, 1, 10, 0, 0)
        val new = LocalDateTime.of(2026, 6, 1, 10, 0, 0)
        writeSnapshot("src/Main.kt", old)
        writeSnapshot("src/Main.kt", new)

        val result = SnapshotReader.listSnapshots("src/Main.kt", tempDir.root.absolutePath)
        assertEquals(2, result.size)
        assertEquals(new, result[0].timestamp)
        assertEquals(old, result[1].timestamp)
    }

    @Test
    fun `listSnapshots only returns snapshots for the requested file`() {
        writeSnapshot("src/Main.kt", LocalDateTime.of(2026, 1, 1, 0, 0, 0))
        writeSnapshot("src/Other.kt", LocalDateTime.of(2026, 1, 2, 0, 0, 0))

        val result = SnapshotReader.listSnapshots("src/Main.kt", tempDir.root.absolutePath)
        assertEquals(1, result.size)
        assertTrue(result[0].file.name.startsWith("Main_"))
    }

    @Test
    fun `readContent returns the original file content`() {
        val content = "fun hello() = println(\"world\")"
        writeSnapshot("src/Hello.kt", LocalDateTime.of(2026, 3, 15, 12, 0, 0), content)

        val entries = SnapshotReader.listSnapshots("src/Hello.kt", tempDir.root.absolutePath)
        assertEquals(1, entries.size)
        assertEquals(content, SnapshotReader.readContent(entries[0]))
    }
}
```

---

## Verificação Final

Após implementar tudo:

1. **`./gradlew build`** → deve compilar sem erros
2. **`./gradlew test --tests "*.storage.*"`** → `SnapshotWriterTest` e `SnapshotReaderTest` devem passar (sem SDK completo)
3. **`./gradlew runIde`** → na IDE sandbox:
   - Criar um arquivo `.kt` e salvar → verificar que `.history/src/.../Arquivo_TIMESTAMP.kt` foi criado em disco
   - Salvar o mesmo arquivo sem mudar → verificar que **nenhum novo snapshot** é criado
   - Salvar o mesmo arquivo rapidamente 5 vezes → verificar que apenas **1 snapshot** é criado
   - Verificar que `.gitignore` ganhou a entrada `.history/`
4. **Verificar logs**: Help > Show Log in Explorer > `idea.log` deve conter `"Local History: snapshot saved for '...'"` e `"Local History: .gitignore updated"`

---

## O Que NÃO Fazer Nesta Fase

- ❌ NÃO criar Tool Window nem UI de lista (será Fase 3)
- ❌ NÃO implementar ação de restore (será Fase 4)
- ❌ NÃO implementar política de retenção (será Fase 5)
- ❌ NÃO usar `java.nio.file` para escrever no `.gitignore` — usar VFS (decisão D-003)
- ❌ NÃO usar `WriteAction` para escrever snapshots em `.history/` — usar `java.nio.file` (decisão D-003)
- ❌ NÃO bloquear a EDT — todo I/O deve estar em `Dispatchers.IO`
- ❌ NÃO criar `CoroutineScope` manualmente no `SnapshotService` — usar o `cs` injetado pelo construtor
