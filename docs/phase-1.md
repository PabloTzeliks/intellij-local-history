# Fase 1 — Interceptação de Save + Guard + Filtros

## Objetivo

Implementar a captura de eventos de salvamento de arquivo na IDE.
Ao final desta fase, o plugin deve:

- Detectar quando qualquer arquivo do projeto é salvo
- Filtrar arquivos que NÃO devem ser capturados (binários, grandes, diretórios excluídos)
- Prevenir loops infinitos via guard
- Logar no console do IntelliJ o que seria capturado (sem escrever no disco ainda)

## Pré-requisitos

- Leia `AGENTS.md` na raiz do projeto para entender a stack e as regras críticas
- Leia `docs/decisions.md` para entender as decisões já tomadas
- A Fase 0 já foi completada: o template está limpo, pacotes criados, build funciona

## Critério de Aceite

1. Ao salvar um arquivo `.kt`, `.java`, `.xml`, `.py` (qualquer texto) no `runIde`, uma mensagem de log aparece no `idea.log` com o path do arquivo
2. Ao salvar um arquivo dentro de `.idea/`, `build/`, `node_modules/` — nada é logado
3. Ao salvar um arquivo binário (imagem, JAR) — nada é logado
4. O build compila sem erros: `./gradlew build`
5. Testes unitários passam para `SnapshotGuard` e `FileFilters`

---

## Arquivos a Criar

### 1. `src/main/kotlin/.../model/SnapshotRequest.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.model`

Data class imutável que carrega os dados capturados no momento do save.
Criada na EDT (thread de UI) e depois enviada para processamento em background.

```kotlin
data class SnapshotRequest(
    val relativePath: String,       // caminho relativo ao projeto (ex: "src/model/Livro.java")
    val fileName: String,           // nome sem extensão (ex: "Livro")
    val fileExtension: String,      // extensão com ponto (ex: ".java")
    val content: String,            // conteúdo completo do documento (document.getText())
    val timestamp: LocalDateTime,   // momento do save
    val projectBasePath: String     // raiz do projeto (project.basePath)
)
```

**Regras:**

- Usar `java.time.LocalDateTime` para o timestamp
- Todos os campos são `val` (imutável)
- Não colocar lógica de negócio aqui

---

### 2. `src/main/kotlin/.../util/SnapshotGuard.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.util`

Previne loops infinitos quando o plugin restaura um arquivo.
Quando fazemos restore, escrevemos no Document, o que dispara o listener de save novamente.
O guard bloqueia essa re-entrada.

```kotlin
object SnapshotGuard {
    private val restoring = AtomicBoolean(false)

    /** Retorna true se o plugin está no meio de uma operação de restore */
    fun isActive(): Boolean = restoring.get()

    /** Executa um bloco com o guard ativo — qualquer save durante este bloco é ignorado */
    fun <T> withGuard(block: () -> T): T {
        restoring.set(true)
        try {
            return block()
        } finally {
            restoring.set(false)
        }
    }
}
```

**Regras:**

- Usar `java.util.concurrent.atomic.AtomicBoolean` (thread-safe)
- `object` = singleton em Kotlin (uma única instância)
- O `finally` garante que o guard é desativado mesmo se der exceção

---

### 3. `src/main/kotlin/.../util/FileFilters.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.util`

Decide se um arquivo deve ser capturado ou ignorado.

```kotlin
object FileFilters {

    /** Tamanho máximo de arquivo para capturar (2MB) */
    private const val MAX_FILE_SIZE: Long = 2 * 1024 * 1024

    /** Diretórios cujos arquivos são SEMPRE ignorados */
    private val EXCLUDED_DIRS = listOf(
        ".history",
        ".git",
        ".idea",
        ".gradle",
        "node_modules",
        "build",
        "target",
        "out",
        "dist"
    )

    /**
     * Retorna true se o arquivo DEVE ser capturado.
     * Retorna false se deve ser ignorado.
     *
     * @param file o VirtualFile sendo salvo
     * @param projectBasePath caminho raiz do projeto
     */
    fun shouldCapture(file: VirtualFile, projectBasePath: String?): Boolean {
        // 1. Sem path de projeto → não capturar
        if (projectBasePath == null) return false

        // 2. Arquivo binário → não capturar
        if (file.fileType.isBinary) return false

        // 3. Arquivo muito grande → não capturar
        if (file.length > MAX_FILE_SIZE) return false

        // 4. Verificar se está dentro de diretório excluído
        val relativePath = file.path.removePrefix(projectBasePath).trimStart('/', '\\')
        for (excluded in EXCLUDED_DIRS) {
            if (relativePath.startsWith("$excluded/") || relativePath.startsWith("$excluded\\")) {
                return false
            }
        }

        return true
    }
}
```

**Regras:**

- Imports necessários: `com.intellij.openapi.vfs.VirtualFile`
- O path do `VirtualFile` usa `/` como separador (mesmo no Windows)
- `file.fileType.isBinary` é a API do IntelliJ para detectar binários
- A lista de diretórios é hardcoded no MVP (será configurável na Fase 5)

---

### 4. `src/main/kotlin/.../listener/DocumentSaveListener.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.listener`

O coração da Fase 1. Intercepta cada save de arquivo na IDE.

```kotlin
class DocumentSaveListener : FileDocumentManagerListener {

    /**
     * Chamado pela IDE ANTES de cada save de documento.
     * Roda na EDT — fazer o mínimo possível aqui.
     */
    override fun beforeDocumentSaving(document: Document) {
        // 1. Guard ativo? (estamos no meio de um restore) → sair
        if (SnapshotGuard.isActive()) return

        // 2. Obter o VirtualFile associado ao Document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return

        // 3. Precisamos de um projeto para calcular o path relativo
        //    Como o listener é global (application-level), buscamos o projeto
        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return

        // 4. Filtros: binário? muito grande? diretório excluído?
        if (!FileFilters.shouldCapture(file, project.basePath)) return

        // 5. Montar o SnapshotRequest com os dados necessários
        val projectBasePath = project.basePath ?: return
        val relativePath = file.path.removePrefix(projectBasePath).trimStart('/')
        val nameWithoutExtension = file.nameWithoutExtension
        val extension = if (file.extension != null) ".${file.extension}" else ""

        val request = SnapshotRequest(
            relativePath = relativePath,
            fileName = nameWithoutExtension,
            fileExtension = extension,
            content = document.text,
            timestamp = LocalDateTime.now(),
            projectBasePath = projectBasePath
        )

        // 6. Por enquanto, apenas logar (Fase 2 conectará ao SnapshotService)
        thisLogger().info("Local History: captured save of '$relativePath' (${document.textLength} chars)")
    }
}
```

**Regras:**

- Imports necessários:
  - `com.intellij.openapi.editor.Document`
  - `com.intellij.openapi.fileEditor.FileDocumentManager`
  - `com.intellij.openapi.fileEditor.FileDocumentManagerListener`
  - `com.intellij.openapi.diagnostic.thisLogger`
  - `com.intellij.openapi.project.ProjectLocator`
  - `java.time.LocalDateTime`
- `thisLogger()` é a API do IntelliJ para logging (escreve no `idea.log`)
- `ProjectLocator.guessProjectForFile` — deduz qual projeto contém o arquivo
- NÃO fazer I/O de disco neste método (roda na EDT)
- NÃO chamar o SnapshotService ainda (será conectado na Fase 2)

---

## Arquivo a Modificar

### 5. `src/main/resources/META-INF/plugin.xml`

Adicionar o bloco de listener DENTRO do `<idea-plugin>`, ANTES de `<extensions>`:

```xml
<applicationListeners>
    <listener class="com.github.pablotzeliks.intellijlocalhistory.listener.DocumentSaveListener"
              topic="com.intellij.openapi.fileEditor.FileDocumentManagerListener"/>
</applicationListeners>
```

**Explicação:**

- `applicationListeners` = listeners globais (não vinculados a um projeto específico)
- `topic` = a interface que estamos implementando (define quais eventos recebemos)
- A IDE instancia o listener de forma lazy (só quando o primeiro save acontece)

---

## Testes a Criar

### 6. `src/test/kotlin/.../util/SnapshotGuardTest.kt`

```kotlin
class SnapshotGuardTest {
    @Test
    fun `guard starts inactive`() {
        assertFalse(SnapshotGuard.isActive())
    }

    @Test
    fun `guard is active inside withGuard block`() {
        SnapshotGuard.withGuard {
            assertTrue(SnapshotGuard.isActive())
        }
    }

    @Test
    fun `guard is inactive after withGuard block`() {
        SnapshotGuard.withGuard { }
        assertFalse(SnapshotGuard.isActive())
    }

    @Test
    fun `guard is inactive even if block throws exception`() {
        try {
            SnapshotGuard.withGuard { throw RuntimeException("test") }
        } catch (_: RuntimeException) { }
        assertFalse(SnapshotGuard.isActive())
    }

    @Test
    fun `withGuard returns block result`() {
        val result = SnapshotGuard.withGuard { 42 }
        assertEquals(42, result)
    }
}
```

### 7. `src/test/kotlin/.../util/FileFiltersTest.kt`

```kotlin
class FileFiltersTest : BasePlatformTestCase() {
    // Usa o framework de testes do IntelliJ para ter acesso a VirtualFiles

    @Test
    fun `should reject files in excluded directories`() {
        // Testar com caminhos simulados contendo .idea/, build/, etc.
    }

    @Test
    fun `should accept normal source files`() {
        // Testar com .kt, .java, .xml
    }

    @Test
    fun `should reject files larger than 2MB`() {
        // Testar com arquivo grande
    }
}
```

**Nota:** Os testes de `FileFilters` podem precisar de mock ou do framework `BasePlatformTestCase` para criar `VirtualFile` simulados. Se for complexo demais para o MVP, marcar como TODO e focar no teste manual via `runIde`.

---

## Verificação Final

Após implementar tudo:

1. `./gradlew build` → deve compilar sem erros
2. `./gradlew test` → testes do SnapshotGuard devem passar
3. `./gradlew runIde` → na IDE sandbox:
   - Criar um arquivo `.java` ou `.kt` → salvar → verificar log
   - Criar um arquivo dentro de `.idea/` → salvar → NÃO deve logar
   - O log aparece em: Help > Show Log in Explorer > `idea.log`

---

## O Que NÃO Fazer Nesta Fase

- ❌ NÃO escrever arquivos no disco (será Fase 2)
- ❌ NÃO criar SnapshotService ainda (será Fase 2)
- ❌ NÃO criar Tool Window (será Fase 3)
- ❌ NÃO adicionar configurações/settings (será Fase 5)
- ❌ NÃO usar VFS para escrita — decisão D-003 em docs/decisions.md
