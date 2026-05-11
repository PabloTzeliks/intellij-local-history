# Fase 3 — Tool Window e Lista de Snapshots

## Objetivo

Criar a primeira interface visual do plugin: uma Tool Window que exibe, em tempo real, os
snapshots do arquivo aberto no editor. Esta é a fase em que o usuário finalmente "vê" o
trabalho silencioso feito pelas fases 1 e 2.

Ao final desta fase, o plugin deve:
- Exibir uma Tool Window "Local History" ancorada na parte inferior da IDE
- Atualizar automaticamente a lista de snapshots ao trocar de arquivo no editor
- Mostrar data/hora e tamanho de cada snapshot na lista
- Abrir o diff nativo do IntelliJ ao acionar "Compare with Current" sobre um snapshot selecionado
- Exibir estado vazio ("No history available") quando não há snapshots ou nenhum arquivo está aberto

## Pré-requisitos

- Leia `AGENTS.md` na raiz do projeto (regras críticas de threading, stack, estrutura)
- Leia `docs/decisions.md` — especialmente D-003 (java.nio vs VFS) e D-008 (DiffManager nativo)
- As Fases 1 e 2 estão completamente implementadas. Os seguintes artefatos existem e compilam:
  - `model/SnapshotEntry.kt` — data class com `file: File`, `timestamp: LocalDateTime`, `originalRelativePath: String`
  - `storage/SnapshotReader.kt` — `listSnapshots(relativePath, projectBasePath): List<SnapshotEntry>` e `readContent(entry): String`
  - `storage/SnapshotWriter.kt` — escreve snapshots em `.history/`
  - `service/SnapshotService.kt` — orquestra debounce + dedup, expõe `enqueue(request)`
  - `util/SnapshotGuard.kt` — `isActive()` e `withGuard { }` com `AtomicInteger`
  - `LocalHistoryBundle.kt` + `LocalHistoryBundle.properties` — bundle i18n já com as chaves:
    `toolWindow.title`, `toolWindow.empty`, `toolWindow.loading`, `action.compare`

## Critérios de Aceite

1. Uma Tool Window chamada **"Local History"** aparece na barra inferior (ou lateral) da IDE e pode ser aberta/fechada normalmente
2. Ao clicar em qualquer arquivo texto no editor, a lista de snapshots daquele arquivo é carregada automaticamente na Tool Window
3. Ao abrir um arquivo sem histórico, a Tool Window exibe a mensagem de estado vazio (chave `toolWindow.empty` do bundle)
4. Cada item da lista exibe: **data/hora formatada** (`dd/MM/yyyy HH:mm:ss`) e **tamanho legível** (ex: `4.2 KB`)
5. Um duplo-clique ou o botão **"Compare with Current"** abre o viewer de diff nativo do IntelliJ com o snapshot à esquerda e o arquivo atual à direita
6. Ao trocar rapidamente entre 10+ arquivos, a IDE **não apresenta freeze perceptível** (loading acontece em background, nunca na EDT)
7. Ao fechar e reabrir a Tool Window, ela retoma o arquivo ativo corretamente
8. `./gradlew build` compila sem erros

---

## Estrutura de Pacotes a Criar

Esta fase cria dois novos pacotes no projeto:

```
src/main/kotlin/.../
├── ui/                                   ← já existe (package-info.kt presente)
│   ├── LocalHistoryToolWindowFactory.kt  ← CRIAR
│   └── LocalHistoryPanel.kt              ← CRIAR
└── action/                               ← CRIAR (novo pacote)
    └── CompareWithCurrentAction.kt       ← CRIAR
```

O pacote `action` deve seguir o padrão: `com.github.pablotzeliks.intellijlocalhistory.action`.
Criar um `package-info.kt` no pacote `action/` documentando seu propósito (seguir o padrão
dos demais pacotes do projeto).

---

## Arquivos a Criar

### 1. `ui/LocalHistoryToolWindowFactory.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.ui`

Implementa `ToolWindowFactory`. É o ponto de entrada registrado no `plugin.xml`.
Responsável por:
- Instanciar o `LocalHistoryPanel` passando o `project`
- Adicionar o conteúdo do painel ao `toolWindow.contentManager`
- Registrar o `FileEditorManagerListener` conectado ao `toolWindow.disposable` (crítico para evitar memory leak)

**Regras:**
- O listener deve ser conectado via `project.messageBus.connect(toolWindow.disposable).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, ...)` — usar o `disposable` da Tool Window, não do projeto, para que o listener seja desregistrado quando a janela fechar
- `createToolWindowContent()` é chamado uma única vez pela IDE — toda inicialização acontece aqui
- Não fazer I/O neste método

---

### 2. `ui/LocalHistoryPanel.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.ui`

O painel principal. Contém toda a lógica de exibição e interação com o usuário.

**Responsabilidades:**
- Manter um `JBList<SnapshotEntry>` com um `DefaultListModel<SnapshotEntry>`
- Expor um método `loadFile(virtualFile: VirtualFile?)` que:
  1. Exibe o estado de loading (`toolWindow.loading`)
  2. Lança uma coroutine em `Dispatchers.IO` para chamar `SnapshotReader.listSnapshots(...)`
  3. Após obter os resultados, atualiza a `JBList` de volta na EDT via `withContext(Dispatchers.Main)` ou `SwingUtilities.invokeLater`
  4. Se a lista estiver vazia ou `virtualFile` for null, exibe o estado vazio (`toolWindow.empty`)
- Expor um método `getSelectedEntry(): SnapshotEntry?` para que a `CompareWithCurrentAction` possa acessar a seleção atual (será reutilizado na Fase 4 para o Restore)

**Renderer da lista:**
- Implementar um `ListCellRenderer<SnapshotEntry>` customizado (pode ser uma inner class ou lambda com `SimpleColoredComponent`)
- Cada célula deve exibir:
  - Data/hora: formato `dd/MM/yyyy HH:mm:ss` (parsear de `entry.timestamp`)
  - Tamanho: `entry.file.length()` formatado como KB ou MB com 1 casa decimal (ex: `4.2 KB`, `1.1 MB`)
- Usar ícone de documento do IntelliJ para o item (opcional, mas desejável)

**Coroutine Scope:**
- Usar `project.coroutineScope` (API nativa do IntelliJ Platform) — **não criar `CoroutineScope` manualmente**
- Ao chamar `loadFile()` múltiplas vezes em sequência (troca rápida de arquivo), cancelar o job anterior antes de lançar o novo

**Toolbar da Tool Window:**
- Adicionar um botão "Compare with Current" na toolbar do painel (usando `ActionToolbar` ou `JButton`)
- O botão deve estar desabilitado quando nenhum item estiver selecionado
- Ao clicar, invoca a `CompareWithCurrentAction` passando o entry selecionado e o arquivo atual

**Regras:**
- Toda atualização de componentes Swing deve acontecer na EDT
- Todo I/O (chamada ao `SnapshotReader`) deve acontecer em `Dispatchers.IO`
- A lista deve suportar duplo-clique como atalho para "Compare with Current"

---

### 3. `action/CompareWithCurrentAction.kt`

**Pacote:** `com.github.pablotzeliks.intellijlocalhistory.action`

Responsável por abrir o viewer de diff nativo do IntelliJ.

**Responsabilidades:**
- Receber um `SnapshotEntry` (o selecionado na lista) e o `VirtualFile` atual do editor
- Ler o conteúdo do snapshot via `SnapshotReader.readContent(entry)` — esse I/O pode ser feito
  diretamente aqui pois o diff viewer é aberto de forma modal
- Construir um `SimpleDiffRequest` com dois `DiffContent`:
  - Lado esquerdo: conteúdo do snapshot (texto puro, com título mostrando o timestamp)
  - Lado direito: conteúdo atual do documento (via `FileDocumentManager.getInstance().getDocument(virtualFile)`)
- Chamar `DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT)`

**API relevante:**
- `DiffContentFactory.getInstance().create(project, text, fileType)` — para o conteúdo do snapshot
- `DiffContentFactory.getInstance().create(project, virtualFile)` — para o arquivo atual (preserva syntax highlight)
- `SimpleDiffRequest(title, content1, content2, title1, title2)` — construtor do request

**Regras:**
- O título do diff deve ser autoexplicativo: `"Local History — <nome do arquivo>"`
- Os títulos dos painéis devem mostrar: `"Snapshot (dd/MM/yyyy HH:mm:ss)"` e `"Current"`
- Não fazer leitura de disco na EDT — se necessário, lançar em background e abrir o diff após

---

## Arquivos a Modificar

### 4. `src/main/resources/META-INF/plugin.xml`

Adicionar o registro da Tool Window dentro de `<extensions defaultExtensionNs="com.intellij">`:

```xml
<toolWindow id="Local History"
            anchor="bottom"
            secondary="false"
            canCloseContents="false"
            factoryClass="com.github.pablotzeliks.intellijlocalhistory.ui.LocalHistoryToolWindowFactory"
            icon="/icons/localHistory.svg"/>
```

Adicionar o registro da action dentro de `<idea-plugin>` (criar bloco `<actions>` se não existir):

```xml
<actions>
    <action id="LocalHistory.CompareWithCurrent"
            class="com.github.pablotzeliks.intellijlocalhistory.action.CompareWithCurrentAction"
            text="Compare with Current"
            description="Opens diff between selected snapshot and the current file"/>
</actions>
```

**Atenção:**
- `anchor="bottom"` — posiciona na barra inferior (padrão para ferramentas de histórico/versionamento)
- `icon` é obrigatório para que o registro não lance `NullPointerException` em runtime — ver seção de recursos abaixo
- O `id` da tool window (`"Local History"`) é o identificador interno usado pela API `ToolWindowManager`

---

### 5. `src/main/resources/icons/localHistory.svg`

**Criar o diretório `icons/` dentro de `src/main/resources/`.**

O IntelliJ exige um ícone SVG para Tool Windows. Especificações:
- Tamanho: `13x13` pixels (padrão para ícones de tool window)
- Estilo: monocromático, deve funcionar em temas claro e escuro
- Sugestão: ícone de relógio ou histórico (compatível com o conceito do plugin)

Exemplo de SVG mínimo funcional (relógio simples):
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 13 13">
  <circle cx="6.5" cy="6.5" r="5.5" fill="none" stroke="currentColor" stroke-width="1.2"/>
  <path d="M6.5 3.5v3l2 1.5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
</svg>
```

O IntelliJ suporta `currentColor` para adaptar o ícone ao tema automaticamente.

---

### 6. `src/main/resources/messages/LocalHistoryBundle.properties`

As chaves necessárias para esta fase **já existem** no bundle:
- `toolWindow.title=Local History`
- `toolWindow.empty=No history available for this file`
- `toolWindow.loading=Loading history...`
- `action.compare=Compare with Current`

Verificar se todas estão presentes. Se alguma estiver faltando, adicionar ao arquivo.

---

## Regras Críticas de Threading (Fase 3)

Esta fase é onde a Regra Crítica #1 do `AGENTS.md` (nunca bloquear a EDT) é mais fácil de violar.

### Fluxo correto ao trocar de arquivo:

```
FileEditorManagerListener.selectionChanged()   ← roda na EDT
    └── panel.loadFile(newFile)                ← EDT: apenas atualiza label para "loading..."
            └── cs.launch(Dispatchers.IO) {    ← background thread
                    SnapshotReader.listSnapshots(...)   ← I/O de disco aqui
                    withContext(Dispatchers.Main) {     ← volta para EDT
                        listModel.clear()
                        entries.forEach { listModel.addElement(it) }
                    }
                }
```

### Anti-padrões a evitar:

```kotlin
// ❌ ERRADO — I/O na EDT
override fun selectionChanged(event: FileEditorManagerEvent) {
    val entries = SnapshotReader.listSnapshots(...)  // congela a IDE
    listModel.addElement(...)
}

// ❌ ERRADO — CoroutineScope manual (memory leak)
val cs = CoroutineScope(Dispatchers.IO)  // vaza ao fechar o projeto

// ❌ ERRADO — atualizar Swing fora da EDT
cs.launch(Dispatchers.IO) {
    listModel.addElement(entry)  // Swing não é thread-safe
}
```

---

## Contratos com Fases Seguintes

Esta fase deve estabelecer os pontos de extensão que as fases 4 e 5 precisarão:

**Para a Fase 4 (Restore):**
- `LocalHistoryPanel.getSelectedEntry(): SnapshotEntry?` — já deve existir e funcionar
- `LocalHistoryPanel.refresh()` — método público para recarregar a lista após um restore ou delete
- O `currentFile: VirtualFile?` que o painel tem referência deve ser acessível para a action de restore

**Para a Fase 5 (Settings):**
- O formato de data e o número máximo de itens exibidos na lista devem ser lidos de `LocalHistorySettings` (que não existe ainda) ou de constantes locais facilmente substituíveis
- Documentar esses pontos com `// TODO: Phase 5 — read from LocalHistorySettings` nos locais relevantes

---

## Verificação Final

Após implementar tudo:

1. **`./gradlew build`** → deve compilar sem erros ou warnings de import não utilizado
2. **`./gradlew runIde`** → na IDE sandbox:
   - A Tool Window "Local History" deve aparecer na barra inferior
   - Abrir um arquivo `.kt` que já tenha snapshots em `.history/` → a lista deve popular
   - Abrir um arquivo novo (sem snapshots) → deve mostrar a mensagem de estado vazio
   - Salvar um arquivo, esperar ~1s, recarregar a Tool Window → o novo snapshot deve aparecer
   - Clicar em um snapshot e acionar "Compare with Current" → o diff viewer nativo deve abrir
   - Trocar entre 5+ arquivos rapidamente → a IDE não deve congelar

---

## O Que NÃO Fazer Nesta Fase

- ❌ NÃO implementar a ação de Restore (será Fase 4)
- ❌ NÃO implementar Delete de snapshot (será Fase 4, opcional)
- ❌ NÃO implementar política de retenção ou configurações (será Fase 5)
- ❌ NÃO fazer I/O de disco na EDT — todo acesso ao `SnapshotReader` deve estar em `Dispatchers.IO`
- ❌ NÃO criar `CoroutineScope` manualmente — usar `project.coroutineScope`
- ❌ NÃO conectar o `FileEditorManagerListener` ao `project.messageBus` diretamente sem disposable — causa memory leak ao fechar o projeto com a Tool Window aberta
- ❌ NÃO atualizar componentes Swing fora da EDT

---

## Problemas Conhecidos e Soluções (Troubleshooting)

Durante o desenvolvimento e testes desta fase, foram identificados e resolvidos os seguintes problemas estruturais:

### 1. Erro fatal nas Coroutines (Debug metadata version mismatch)
**Sintoma:** Ao tentar renderizar a lista de snapshots na Tool Window, a interface ficava presa em "Loading history..." e a IDE disparava um *IDE Fatal Error* com a mensagem: `java.lang.IllegalStateException: Debug metadata version mismatch. Expected: 1, got 2. Please update the Kotlin standard library.`
**Causa:** Incompatibilidade entre a versão do Kotlin usada para compilar o plugin (que gerava metadados V2) e a versão do `kotlinx-coroutines-core` embutida no depurador da IDE Sandbox (que esperava metadados V1).
**Solução:**
- A versão do plugin Kotlin em `settings.gradle.kts` foi ajustada para `2.1.10` para parear perfeitamente com o código da plataforma IntelliJ (2025.2).
- O depurador de coroutines nativo do IntelliJ (`DebugProbes`) foi desabilitado durante a task `runIde` inserindo `jvmArgs("-Dkotlinx.coroutines.debug=off")` no `build.gradle.kts`, prevenindo a falha ao tentar recuperar a stack trace.

### 2. Atraso na exibição das atualizações (Falta de Real-time Refresh)
**Sintoma:** Os arquivos eram salvos e o snapshot era criado imediatamente no disco, porém o painel do "Local History" demorava cerca de 1 minuto para mostrar as novidades, e só atualizava instantaneamente ao mudar de arquivo.
**Causa:** Pela especificação original, a atualização só ocorria via `FileEditorManagerListener.selectionChanged`. A interface ficava estática esperando que algum evento da IDE forçasse a recarga, não sendo notificada diretamente sobre o evento de save. O suposto atraso de 1 minuto coincidia com sincronizações ocultas de background da IDE.
**Solução:**
- Foi criado o `SnapshotListener`, uma interface registrada como um Topic no MessageBus (`LocalHistorySnapshotAdded`).
- O `SnapshotService` passou a invocar `syncPublisher` disparando o evento assim que a escrita do arquivo em disco termina.
- O `LocalHistoryToolWindowFactory` passou a assinar esse evento e disparar `panel.refresh()` (sempre dentro da EDT, usando `ApplicationManager.getApplication().invokeLater { }`) caso o arquivo ativo seja o mesmo que acabou de sofrer snapshot.
