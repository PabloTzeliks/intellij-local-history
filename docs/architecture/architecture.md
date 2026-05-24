# Arquitetura — IntelliJ Local History Plugin

**Versão documentada:** pós-Fase 3 (branch `main`, 2026-05-24)  
**Stack:** Kotlin 2.1.10 · IntelliJ Platform 2025.2 · Gradle + IntelliJ Platform Gradle Plugin 2.x · JUnit 4

---

## Índice

1. [Visão Geral](#1-visão-geral)
2. [Estrutura de Pacotes](#2-estrutura-de-pacotes)
3. [Registro na Plataforma (plugin.xml)](#3-registro-na-plataforma-pluginxml)
4. [Mecanismos de Captura](#4-mecanismos-de-captura)
5. [Pipeline de Processamento — SnapshotService](#5-pipeline-de-processamento--snapshotservice)
6. [Camada de Storage](#6-camada-de-storage)
7. [Camada de UI](#7-camada-de-ui)
8. [Componentes de Suporte](#8-componentes-de-suporte)
9. [Modelo de Threading](#9-modelo-de-threading)
10. [Fluxo Completo de Dados](#10-fluxo-completo-de-dados)
11. [Formato dos Arquivos em Disco](#11-formato-dos-arquivos-em-disco)

---

## 1. Visão Geral

O plugin captura snapshots automáticos de arquivos-fonte sempre que o usuário edita ou salva, armazenando-os em `.history/` na raiz do projeto no mesmo formato do VS Code Local History (`xyz.local-history`). O caso de uso principal é análise de evolução de código em ambiente acadêmico — os snapshots precisam ser frequentes e fiéis ao estado real do documento em memória, não apenas ao estado salvo em disco.

```
┌─────────────────────────────────────────────────────────────┐
│                        IntelliJ IDEA                        │
│                                                             │
│  ┌──────────────┐    ┌──────────────────────────────────┐  │
│  │   Usuário    │    │           Plugin                  │  │
│  │              │───▶│  DocumentSaveListener             │  │
│  │  digita /    │    │  DocumentChangeListener           │  │
│  │  salva       │    │         │                         │  │
│  └──────────────┘    │         ▼                         │  │
│                      │  SnapshotService (dedup + write)  │  │
│                      │         │                         │  │
│                      │         ▼                         │  │
│                      │  .history/ (flat files)           │  │
│                      │         │                         │  │
│                      │         ▼                         │  │
│                      │  LocalHistoryPanel (Tool Window)  │  │
│                      └──────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

**Propriedade central:** o plugin acessa o modelo de documento em memória (`Document`) do IntelliJ diretamente, capturando estados intermediários **independente de saves explícitos**. Isso o diferencia estruturalmente do VS Code Local History, que é cego ao que acontece entre dois `Ctrl+S`.

---

## 2. Estrutura de Pacotes

```
com.github.pablotzeliks.intellijlocalhistory/
│
├── action/
│   └── CompareWithCurrentAction.kt     — diff snapshot vs. estado atual
│
├── listener/
│   ├── DocumentSaveListener.kt         — captura em saves explícitos
│   └── DocumentChangeListener.kt       — captura entre saves (dual debounce)
│
├── model/
│   ├── SnapshotRequest.kt              — DTO de requisição de captura
│   └── SnapshotEntry.kt                — snapshot existente em disco
│
├── service/
│   ├── SnapshotService.kt              — orquestra dedup, debounce e escrita
│   ├── GitignoreService.kt             — garante .history/ no .gitignore
│   ├── LocalHistoryStartupActivity.kt  — inicialização por projeto
│   └── LocalHistoryUiScopeService.kt   — CoroutineScope gerenciado pela plataforma
│
├── storage/
│   ├── SnapshotWriter.kt               — escreve arquivo em .history/
│   └── SnapshotReader.kt               — lista e lê snapshots do disco
│
├── ui/
│   ├── LocalHistoryToolWindowFactory.kt — cria o Tool Window
│   └── LocalHistoryPanel.kt             — JBList de snapshots + toolbar
│
└── util/
    ├── FileFilters.kt                  — decide se arquivo deve ser capturado
    ├── SnapshotGuard.kt                — bloqueia capturas durante restore
    └── DateFormats.kt                  — formatos de timestamp centralizados
```

---

## 3. Registro na Plataforma (plugin.xml)

O `plugin.xml` define o que a IDE instancia e em que escopo:

```mermaid
graph TD
    subgraph "Application-level (global)"
        DSL["DocumentSaveListener<br/>(applicationListener)"]
    end

    subgraph "Project-level (por projeto aberto)"
        SS["SnapshotService<br/>(projectService)"]
        GS["GitignoreService<br/>(projectService)"]
        UIS["LocalHistoryUiScopeService<br/>(projectService)"]
        SA["LocalHistoryStartupActivity<br/>(projectActivity)"]
        TW["Tool Window 'Local History'<br/>(anchor=bottom)"]
    end

    subgraph "Actions"
        CA["CompareWithCurrentAction<br/>(id: LocalHistory.CompareWithCurrent)"]
    end

    DSL -->|"usa project.service()"| SS
    SA -->|"registra"| DCL["DocumentChangeListener<br/>(dinâmico via EditorFactory)"]
    SA -->|"subscribe messageBus"| SS
    TW -->|"cria"| LHP["LocalHistoryPanel"]
    LHP -->|"usa"| UIS
    LHP -->|"usa"| CA
```

**Observação de escopo:** `DocumentSaveListener` é application-level porque `FileDocumentManagerListener` é um tópico global — não existe versão project-scoped desse evento. Para obter o projeto, usa `ProjectLocator.getInstance().guessProjectForFile(file)` internamente.

---

## 4. Mecanismos de Captura

O plugin tem **dois caminhos independentes** que alimentam o mesmo pipeline de processamento.

### 4.1 DocumentSaveListener — captura em save explícito

Ativado por: `Ctrl+S`, auto-save da IDE (ao trocar aba, ao perder foco), `FileDocumentManager.saveAllDocuments()`.

```mermaid
flowchart TD
    E["beforeDocumentSaving(document)\n[EDT]"]
    G1{"SnapshotGuard\nisActive?"}
    G2{"FileDocumentManager\n.getFile(document)?"}
    G3{"ProjectLocator\n.guessProject?"}
    G4{"FileFilters\n.shouldCapture?"}
    G5{"document.textLength\n> 2MB?"}
    R["return — descarta"]
    B["monta SnapshotRequest\n(relativePath, content, timestamp)"]
    EN["SnapshotService\n.enqueue(request)"]

    E --> G1
    G1 -->|sim| R
    G1 -->|não| G2
    G2 -->|null| R
    G2 -->|file| G3
    G3 -->|null| R
    G3 -->|project| G4
    G4 -->|false| R
    G4 -->|true| G5
    G5 -->|sim| R
    G5 -->|não| B
    B --> EN
```

### 4.2 DocumentChangeListener — captura entre saves

Ativado por: qualquer modificação no conteúdo do documento (keystroke, paste, refactor). Registrado via `EditorFactory.eventMulticaster` — cobre todos os documentos do projeto sem duplicação.

Implementa `BulkAwareDocumentListener`: suprime eventos individuais durante operações bulk (refactor, paste massivo), entregando um único evento ao final.

```mermaid
flowchart TD
    E["documentChanged(event)\n[EDT]"]
    G1{"SnapshotGuard\nisActive?"}
    G2{"textLength > 2MB?"}
    G3{"FileDocumentManager\n.getFile?"}
    G4{"file.isValid?"}
    G5{"FileFilters\n.shouldCapture?"}
    G6{"getRelativePath?"}
    R["return"]
    SC["scheduleCapture()\n[EDT]"]

    E --> G1
    G1 -->|sim| R
    G1 -->|não| G2
    G2 -->|sim| R
    G2 -->|não| G3
    G3 -->|null| R
    G3 -->|file| G4
    G4 -->|false| R
    G4 -->|true| G5
    G5 -->|false| R
    G5 -->|true| G6
    G6 -->|null| R
    G6 -->|path| SC
```

### 4.3 Dual Debounce — estratégia de timers

O problema do debounce simples: um timer de inatividade reseta a cada keystroke, nunca disparando durante digitação contínua longa. A solução usa dois timers independentes por arquivo:

```mermaid
sequenceDiagram
    participant U as Usuário (digita)
    participant ID as inactivityJob[path]
    participant MX as maxIntervalJob[path]
    participant SV as SnapshotService

    Note over U,SV: === Digitação começa ===

    U->>ID: keystroke → cancela job anterior, agenda 15s
    U->>MX: keystroke → maxInterval não existe, cria job de 30s

    U->>ID: keystroke → cancela, reagenda 15s
    U->>ID: keystroke → cancela, reagenda 15s
    U->>ID: keystroke → cancela, reagenda 15s

    Note over MX: 30s se passaram (digitação contínua)
    MX->>ID: cancela inactivityJob pendente
    MX->>SV: doCapture() → captureNow()
    Note over MX: maxIntervalJob é removido

    U->>ID: keystroke → agenda novo 15s
    U->>MX: keystroke → maxInterval não existe, cria novo job de 30s

    Note over U: pausa de digitação (>15s)
    Note over ID: 15s se passaram sem keystroke
    ID->>MX: cancela maxIntervalJob pendente
    ID->>SV: doCapture() → captureNow()
    Note over ID: inactivityJob é removido
```

**Constantes atuais** (candidatas a configuráveis na Fase 5):

| Constante | Valor | Comportamento |
|---|---|---|
| `INACTIVITY_TIMEOUT_MS` | 15.000ms | snapshot 15s após última tecla |
| `MAX_INTERVAL_MS` | 30.000ms | snapshot garantido a cada 30s durante typing contínuo |

### 4.4 Coexistência dos dois listeners

```mermaid
graph LR
    U["Usuário"]
    DSL["DocumentSaveListener"]
    DCL["DocumentChangeListener"]
    SS["SnapshotService"]
    SHA["SHA-256 dedup"]

    U -->|"Ctrl+S"| DSL
    U -->|"digita"| DCL
    DSL -->|"enqueue()\ndebounce 500ms"| SS
    DCL -->|"captureNow()\napós 15s/30s"| SS
    SS --> SHA
    SHA -->|"conteúdo igual → descarta"| X["(noop)"]
    SHA -->|"conteúdo diferente"| W["SnapshotWriter.write()"]
```

Sobreposições entre os dois caminhos são eliminadas pelo dedup SHA-256. Se o `DocumentChangeListener` capturou um estado e o `DocumentSaveListener` chega logo depois com o mesmo conteúdo, o hash é igual e nenhum arquivo duplicado é criado.

---

## 5. Pipeline de Processamento — SnapshotService

`SnapshotService` é um `@Service(Level.PROJECT)` — uma instância por projeto aberto. Recebe o `CoroutineScope` injetado automaticamente pela plataforma.

### 5.1 Estrutura interna

```
SnapshotService
├── debounceJobs: ConcurrentHashMap<String, Job>    — Job de debounce por relativePath
├── lastHashByPath: ConcurrentHashMap<String, String> — último hash SHA-256 por relativePath
│
├── enqueue(request)
│   ├── cancela debounceJobs[path] anterior
│   └── lança Job em Dispatchers.IO:
│       ├── delay(500ms)
│       └── processSnapshot(request)
│
├── captureNow(request)
│   └── lança Job em Dispatchers.IO:
│       └── processSnapshot(request)
│
└── processSnapshot(request)   ← ponto central, não-atômico (ver seção 12)
    ├── sha256(content)
    ├── check lastHashByPath[path] == newHash → return (dedup in-memory)
    ├── if !lastHashByPath.containsKey(path):
    │   ├── SnapshotReader.listSnapshots()
    │   ├── SnapshotReader.readContent(snapshots.first())
    │   └── sha256(diskContent) == newHash → return (dedup cross-session)
    ├── SnapshotWriter.write(request)
    ├── lastHashByPath[path] = newHash
    ├── GitignoreService.ensureHistoryIgnored()
    └── messageBus.syncPublisher(TOPIC).onSnapshotAdded(path)
```

### 5.2 Lógica de deduplicação

```mermaid
flowchart TD
    A["processSnapshot(request)"]
    B["newHash = sha256(content)"]
    C{"lastHashByPath[path]\n== newHash?"}
    D["return — duplicata in-memory"]
    E{"path está em\nlastHashByPath?"}
    F["lê snapshots do disco\n(SnapshotReader)"]
    G{"disco vazio?"}
    H["diskHash = sha256(diskContent)"]
    I{"diskHash == newHash?"}
    J["lastHashByPath[path] = newHash\nreturn — duplicata cross-session"]
    K["SnapshotWriter.write(request)"]
    L["lastHashByPath[path] = newHash"]
    M["GitignoreService\n.ensureHistoryIgnored()"]
    N["messageBus.publish\nonSnapshotAdded(path)"]

    A --> B --> C
    C -->|sim| D
    C -->|não| E
    E -->|sim — já está em memória| K
    E -->|não — primeira captura desta sessão| F
    F --> G
    G -->|sim — sem histórico| K
    G -->|não| H --> I
    I -->|sim| J
    I -->|não| K
    K --> L --> M --> N
```

---

## 6. Camada de Storage

### 6.1 SnapshotWriter

Responsável exclusivamente por **escrever** um snapshot. Usa `java.nio.file` diretamente (não VFS) para evitar re-indexação pelo IntelliJ (ver decisão D-003).

```
write(request):
  timestamp = request.timestamp.format("yyyyMMddHHmmss")
  fileName  = "${request.fileName}_${timestamp}${request.fileExtension}"
  dir       = Path(projectBasePath, ".history", *relativeDir.split('/'))
  Files.createDirectories(dir)
  Files.writeString(dir/fileName, request.content, UTF_8)
```

### 6.2 SnapshotReader

Responsável por **listar** e **ler** snapshots. Nunca escreve.

```
listSnapshots(relativePath, projectBasePath):
  historyDir = Path(projectBasePath, ".history", relativeDir)
  Files.list(historyDir)
    .filter { matchesSnapshot(name, baseName, ext) }   — exige {baseName}_{14 dígitos}{ext}
    .mapNotNull { parseEntry(file, relativePath) }      — parseia timestamp, ignora inválidos
    .sortedByDescending { timestamp }                   — mais recente primeiro

readContent(entry):
  entry.file.readText(UTF_8)
```

**Proteção contra prefixos similares:** `matchesSnapshot` exige que o nome comece exatamente com `{baseName}_` seguido de 14 dígitos. `MainExtra_*.kt` não faz match para uma query de `Main.kt`.

### 6.3 Estrutura de diretórios em disco

```
{projectRoot}/
└── .history/
    ├── src/
    │   ├── main/
    │   │   └── kotlin/
    │   │       └── com/example/
    │   │           ├── Main_20260524103015.kt
    │   │           ├── Main_20260524103045.kt
    │   │           └── Main_20260524103120.kt
    │   └── model/
    │       ├── User_20260524091200.kt
    │       └── User_20260524093015.kt
    └── README_20260524080000.md
```

A estrutura espelha exatamente o projeto fonte — `relativePath` do arquivo determina o subdiretório dentro de `.history/`.

---

## 7. Camada de UI

### 7.1 Inicialização do Tool Window

```mermaid
sequenceDiagram
    participant IDE as IntelliJ Platform
    participant TWF as LocalHistoryToolWindowFactory
    participant UIS as LocalHistoryUiScopeService
    participant LHP as LocalHistoryPanel
    participant FEM as FileEditorManagerListener

    IDE->>TWF: createToolWindowContent(project, toolWindow)
    TWF->>UIS: project.service<LocalHistoryUiScopeService>().scope
    TWF->>LHP: new LocalHistoryPanel(project, cs)
    TWF->>LHP: loadFile(FileEditorManager.selectedFiles.first())
    TWF->>FEM: subscribe(toolWindow.disposable, FileEditorManagerListener)
    Note over FEM: desregistrado ao fechar a Tool Window

    IDE->>FEM: selectionChanged(event)
    FEM->>LHP: loadFile(event.newFile)
```

### 7.2 Carregamento da lista de snapshots

```mermaid
sequenceDiagram
    participant LHP as LocalHistoryPanel [EDT]
    participant IO as Dispatchers.IO
    participant SR as SnapshotReader
    participant EDT2 as EDT (withContext)

    LHP->>LHP: loadFile(virtualFile)
    LHP->>LHP: loadingJob?.cancel()
    LHP->>LHP: listModel.clear() / emptyText = "Loading..."
    LHP->>IO: cs.launch(Dispatchers.IO)
    IO->>SR: listSnapshots(relativePath, basePath)
    SR-->>IO: List<SnapshotEntry>
    IO->>EDT2: withContext(Dispatchers.EDT)
    EDT2->>LHP: listModel.clear() + addElement(entry) para cada snapshot
```

### 7.3 Atualização automática ao capturar snapshot

```mermaid
sequenceDiagram
    participant SS as SnapshotService
    participant MB as MessageBus
    participant LSA as LocalHistoryStartupActivity
    participant LHP as LocalHistoryPanel

    SS->>MB: invokeLater { syncPublisher(TOPIC).onSnapshotAdded(relativePath) }
    MB->>LSA: onSnapshotAdded(relativePath)
    LSA->>LSA: activeRelative = getRelativePath(panel.getCurrentFile())
    alt activeRelative == relativePath
        LSA->>LHP: panel.refresh()
        LHP->>LHP: loadFile(currentFile)
    else arquivo diferente do ativo
        LSA->>LSA: ignora
    end
```

### 7.4 Abertura do diff (CompareWithCurrentAction)

Acionado por: double-click na lista ou botão na toolbar do Tool Window.

```mermaid
sequenceDiagram
    participant U as Usuário
    participant LHP as LocalHistoryPanel
    participant CWC as CompareWithCurrentAction
    participant SR as SnapshotReader
    participant DM as DiffManager (IntelliJ)

    U->>LHP: double-click em SnapshotEntry
    LHP->>CWC: showDiff(project, entry, currentFile)
    CWC->>SR: readContent(entry)  — I/O < 2MB, aceitável na EDT
    CWC->>DM: create DiffContentFactory (snapshot + document atual)
    DM->>U: abre modal com diff nativo (syntax highlight, side-by-side)
```

---

## 8. Componentes de Suporte

### 8.1 FileFilters

Porteiro único que decide se um arquivo deve ser capturado. Chamado nos dois listeners antes de qualquer processamento.

```mermaid
flowchart LR
    F["VirtualFile"]
    C1{"projectBasePath\nnull?"}
    C2{"file.fileType\n.isBinary?"}
    C3{"file.length\n> 2MB?"}
    C4{"file está em\ndiretório excluído?"}
    YES["✅ capturar"]
    NO["❌ ignorar"]

    F --> C1
    C1 -->|sim| NO
    C1 -->|não| C2
    C2 -->|sim| NO
    C2 -->|não| C3
    C3 -->|sim| NO
    C3 -->|não| C4
    C4 -->|sim| NO
    C4 -->|não| YES
```

**Diretórios excluídos (hardcoded, Fase 5 tornará configurável):**
`.history` · `.git` · `.idea` · `.gradle` · `node_modules` · `build` · `target` · `out` · `dist`

**Ausência conhecida:** arquivos de 0 bytes passam por todos os filtros (ver seção 12 — F-004).

### 8.2 SnapshotGuard

Previne que um restore acione um novo snapshot do arquivo restaurado (loop).

```
SnapshotGuard (object — singleton de aplicação)
├── restoringCount: AtomicInteger
├── isActive(): Boolean    → restoringCount.get() > 0
└── withGuard(block):
    ├── restoringCount.incrementAndGet()
    ├── block()
    └── restoringCount.decrementAndGet()    [finally]
```

Ambos os listeners verificam `SnapshotGuard.isActive()` como primeiro passo, descartando a captura enquanto um restore estiver em andamento.

**Limitação:** `SnapshotGuard` é um `object` Kotlin — singleton de nível de aplicação. Se dois projetos estiverem abertos simultaneamente, um restore em um projeto bloqueia capturas no outro. Para o caso de uso atual (análise de prova = 1 projeto), não é um problema prático.

### 8.3 GitignoreService

Garante que `.history/` esteja no `.gitignore` raiz do projeto. Executado uma única vez por sessão por projeto.

```
GitignoreService (PROJECT service)
├── gitignoreEnsured: AtomicBoolean = false
└── ensureHistoryIgnored():
    ├── compareAndSet(false, true) → se false, já executado, retorna
    ├── WriteAction.runAndWait { ... }   — DEVE rodar em Dispatchers.IO (não EDT)
    │   ├── se .gitignore não existe → cria com ".history/"
    │   └── se existe e não contém ".history/" → appenda
    └── em caso de exceção → reseta AtomicBoolean para false (retry na próxima captura)
```

Usa VFS (`VfsUtil.saveText`) neste caso específico pois o `.gitignore` precisa ser rastreado pela IDE.

### 8.4 LocalHistoryUiScopeService

```kotlin
@Service(Service.Level.PROJECT)
class LocalHistoryUiScopeService(val scope: CoroutineScope)
```

Wrapper que expõe o `CoroutineScope` gerenciado pelo ciclo de vida do projeto para componentes que não recebem scope via injeção automática (como `LocalHistoryPanel`, instanciado manualmente pelo `ToolWindowFactory`). O IntelliJ Platform cancela automaticamente o scope ao fechar o projeto.

---

## 9. Modelo de Threading

```mermaid
graph TD
    subgraph "EDT (Event Dispatch Thread)"
        DSL_H["DocumentSaveListener\n.beforeDocumentSaving()"]
        DCL_H["DocumentChangeListener\n.documentChanged()"]
        MB_PUB["messageBus.syncPublisher\n.onSnapshotAdded()"]
        LSA_CB["LocalHistoryStartupActivity\nonSnapshotAdded callback"]
        LHP_UPD["LocalHistoryPanel\nlistModel update (withContext EDT)"]
        TWF_SEL["FileEditorManagerListener\n.selectionChanged()"]
    end

    subgraph "Dispatchers.IO (pool de threads)"
        SS_PROC["SnapshotService\n.processSnapshot()"]
        SS_ENQUEUE["SnapshotService.enqueue()\n(debounce delay)"]
        SS_CAP["SnapshotService.captureNow()"]
        SW["SnapshotWriter.write()"]
        SR["SnapshotReader.listSnapshots()\n+ readContent()"]
        GS["GitignoreService\n.ensureHistoryIgnored()"]
    end

    subgraph "readAction suspend"
        RA["document.text\n(DocumentChangeListener.doCapture)"]
    end

    DSL_H -->|"launch(Dispatchers.IO)"| SS_ENQUEUE
    DCL_H -->|"launch(Dispatchers.IO)"| SS_CAP
    SS_CAP --> SS_PROC
    SS_ENQUEUE -->|"delay 500ms"| SS_PROC
    SS_PROC --> SR
    SS_PROC --> SW
    SS_PROC --> GS
    SS_PROC -->|"invokeLater"| MB_PUB
    MB_PUB --> LSA_CB
    LSA_CB -->|"se mesmo arquivo"| LHP_UPD

    DCL_H -->|"launch(Dispatchers.IO)\ndelay 15s/30s"| RA
    RA -->|"captureNow()"| SS_PROC

    TWF_SEL -->|"launch(Dispatchers.IO)"| SR
    SR -->|"withContext(EDT)"| LHP_UPD
```

**Regras de threading respeitadas:**

| Operação | Thread | Justificativa |
|---|---|---|
| Leitura de `VirtualFile` (path, length) | EDT | Safe em 2025.2+ conforme documentação JetBrains |
| Leitura de `document.text` | `readAction {}` | Necessita read lock da plataforma |
| Escrita em `.history/` via `java.nio.file` | `Dispatchers.IO` | I/O blocking — nunca na EDT |
| `SnapshotReader.listSnapshots()` | `Dispatchers.IO` | I/O blocking |
| `GitignoreService.ensureHistoryIgnored()` | `Dispatchers.IO` | `WriteAction.runAndWait` bloqueia até EDT estar livre — deadlock se chamado da EDT |
| Atualização do `JBList` (listModel) | EDT via `withContext(Dispatchers.EDT)` | Swing não é thread-safe |
| Publicação no `messageBus` | EDT via `invokeLater` | Subscribers de UI esperam EDT |

---

## 10. Fluxo Completo de Dados

### Caminho 1 — Save explícito (Ctrl+S)

```mermaid
sequenceDiagram
    participant U as Usuário
    participant IDE as IntelliJ IDE
    participant DSL as DocumentSaveListener [EDT]
    participant FF as FileFilters
    participant SS as SnapshotService [IO]
    participant SR as SnapshotReader [IO]
    participant SW as SnapshotWriter [IO]
    participant GS as GitignoreService [IO]
    participant MB as MessageBus [EDT]
    participant LHP as LocalHistoryPanel [EDT]

    U->>IDE: Ctrl+S
    IDE->>DSL: beforeDocumentSaving(document)
    DSL->>DSL: SnapshotGuard.isActive()? → não
    DSL->>DSL: FileDocumentManager.getFile(document)
    DSL->>DSL: ProjectLocator.guessProjectForFile(file)
    DSL->>FF: shouldCapture(file, projectDir.path)
    FF-->>DSL: true
    DSL->>DSL: monta SnapshotRequest
    DSL->>SS: enqueue(request)

    Note over SS: launch(Dispatchers.IO)
    SS->>SS: cancela debounceJob anterior (se existir)
    SS->>SS: delay(500ms)
    SS->>SS: newHash = sha256(content)
    SS->>SS: lastHashByPath[path] == newHash? → não

    alt primeira captura da sessão para este arquivo
        SS->>SR: listSnapshots(path, basePath)
        SR-->>SS: [SnapshotEntry, ...]
        SS->>SR: readContent(snapshots.first())
        SR-->>SS: diskContent
        SS->>SS: sha256(diskContent) == newHash? → não
    end

    SS->>SW: write(request)
    SW->>SW: cria .history/{relDir}/
    SW->>SW: Files.writeString({name}_{ts}.{ext}, content, UTF_8)
    SS->>SS: lastHashByPath[path] = newHash
    SS->>GS: ensureHistoryIgnored()
    GS->>GS: compareAndSet(false,true) → primeira vez
    GS->>GS: WriteAction: appenda .history/ ao .gitignore

    SS->>MB: invokeLater { onSnapshotAdded(path) }
    MB->>LHP: onSnapshotAdded(relativePath)
    LHP->>LHP: activeRelative == relativePath? → sim
    LHP->>LHP: refresh() → loadFile(currentFile)
```

### Caminho 2 — Mudança de documento (entre saves)

```mermaid
sequenceDiagram
    participant U as Usuário
    participant DCL as DocumentChangeListener [EDT]
    participant FF as FileFilters
    participant IT as inactivityJob [IO]
    participant MX as maxIntervalJob [IO]
    participant SS as SnapshotService [IO]

    U->>DCL: documentChanged(event)
    DCL->>DCL: SnapshotGuard.isActive()? → não
    DCL->>FF: shouldCapture(file, projectDir.path) → true
    DCL->>DCL: scheduleCapture(relativePath, ...)

    DCL->>IT: cancela job anterior, agenda novo delay(15s)
    alt maxIntervalJob não está ativo
        DCL->>MX: agenda delay(30s)
    end

    alt usuário para de digitar por 15s
        IT->>MX: cancela maxIntervalJob
        IT->>IT: doCapture()
        IT->>IT: readAction { document.text }
        IT->>SS: captureNow(SnapshotRequest)
        Note over SS: → mesmo pipeline de processSnapshot()
    else digitação contínua por 30s
        MX->>IT: cancela inactivityJob
        MX->>MX: doCapture()
        MX->>MX: readAction { document.text }
        MX->>SS: captureNow(SnapshotRequest)
        Note over SS: → mesmo pipeline de processSnapshot()
    end
```

---

## 11. Formato dos Arquivos em Disco

### Naming convention

```
{nomeDoArquivoSemExtensão}_{yyyyMMddHHmmss}{.extensão}

Exemplos:
  CategoriaService_20260522183538.java
  Main_20260524103015.kt
  README_20260524080000.md
  Makefile_20260524120000          ← sem extensão
```

Compatível com o VS Code Local History (`xyz.local-history`) — snapshots gerados por um editor aparecem no outro.

### Parsing do timestamp (SnapshotReader)

```
"CategoriaService_20260522183538.java"
         └── nameWithoutExtension = "CategoriaService_20260522183538"
                    └── takeLast(14) = "20260522183538"
                              └── LocalDateTime.parse("yyyyMMddHHmmss")
```

Arquivos com timestamp inválido (ex: mês 13) são silenciosamente ignorados — `parseEntry` retorna `null`, `mapNotNull` os descarta.

### Estrutura de um SnapshotRequest (DTO de entrada)

```
SnapshotRequest(
  relativePath    = "src/service/CategoriaService.java"   — caminho relativo ao projeto
  fileName        = "CategoriaService"                    — sem extensão
  fileExtension   = ".java"                               — com ponto, ou "" se sem extensão
  content         = "public class ..."                    — texto completo do Document
  timestamp       = LocalDateTime.now()                   — capturado no momento do evento
  projectBasePath = "/Users/pablo/projeto"                — raiz absoluta do projeto
)
```

### Estrutura de um SnapshotEntry (DTO de saída)

```
SnapshotEntry(
  file                = File("/.../.history/src/service/CategoriaService_20260522183538.java")
  timestamp           = LocalDateTime(2026, 5, 22, 18, 35, 38)
  originalRelativePath = "src/service/CategoriaService.java"
  lengthBytes         = 2048L                             — file.length(), para exibição na UI
)
```

