# Decisões Arquiteturais — IntelliJ Local History Plugin

Registro das decisões técnicas tomadas durante o desenvolvimento do plugin.
Cada decisão documenta o contexto, alternativas avaliadas e a escolha final.

---

## D-001: Linguagem — Kotlin

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Contexto
O template do IntelliJ Platform já vem configurado com Kotlin. A alternativa seria migrar para Java.

### Alternativas
| Opção | Prós | Contras |
|---|---|---|
| **Kotlin** | Coroutines nativas, null-safety, extension functions, template pronto, docs oficiais Kotlin-first | Curva de aprendizado para quem vem de Java |
| **Java** | Familiar para mais devs | Sem coroutines, verbose, precisaria reconfigurar todo o scaffold |

### Decisão
**Kotlin.** O IntelliJ Platform SDK 2025+ adota coroutines como modelo de concorrência preferido. APIs como `readAction {}`, `backgroundWriteAction {}`, e `ProjectActivity.execute(suspend)` são Kotlin-first. Migrar para Java significaria perder integração idiomática e reescrever o scaffold do zero.

---

## D-002: Storage — Flat Files (não SQLite)

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Contexto
Precisamos persistir snapshots de arquivos. Duas abordagens avaliadas.

### Alternativas
| Opção | Prós | Contras |
|---|---|---|
| **Flat Files** | Compatível com VS Code, transparente (navegável no filesystem), zero dependências, simples | Lento para queries massivas |
| **SQLite** | Queries rápidas, índices, transações ACID | Formato binário opaco, ~4MB de dependência, incompatível com VS Code |

### Decisão
**Flat files** em `.history/` na raiz do projeto, espelhando a estrutura de diretórios. Formato de naming: `{NomeSemExtensão}_{YYYYMMDDHHmmss}.{extensão}`.

**Motivo principal:** O requisito é gerar arquivos reais no disco como o plugin Local History do VS Code. SQLite armazenaria tudo dentro de um `.db` binário, impossibilitando esse modelo. Além disso, flat files garantem compatibilidade cross-editor — um dev que alterna entre VS Code e IntelliJ verá os mesmos snapshots.

---

## D-003: Escrita de Snapshots — `java.nio.file` (não VFS)

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Contexto
O BLUEPRINT.md afirmava: "É estritamente proibido usar `java.io.File` ou `java.nio.file.Files`". Essa regra foi revisada.

### Análise Crítica do Blueprint
A regra do blueprint é válida para **arquivos do projeto** (código-fonte), mas **incorreta** para dados auxiliares do plugin (backups em `.history/`). Escrever via VFS causa:

1. **Re-indexação:** A IDE detecta cada arquivo criado em `.history/` como "arquivo do projeto" e tenta indexá-lo
2. **Eventos cascata:** Cada escrita VFS dispara `VFileEvent`, podendo causar efeitos colaterais
3. **EDT blocking:** `WriteAction` na EDT congela a UI durante a escrita

A documentação oficial da JetBrains confirma: use `java.io`/`java.nio` para "cache files, temporary storage, log files, or plugin-specific auxiliary data that does not need to be indexed."

### Decisão
**`java.nio.file`** para escrita dos snapshots. VFS usado apenas para:
- Leitura de snapshots no contexto da Tool Window (via `LocalFileSystem.refreshAndFindFileByIoFile()`)
- Modificação do `.gitignore` (que a IDE precisa rastrear)
- Restore de arquivos (via `WriteCommandAction` no `Document`)

---

## D-004: Compatibilidade com VS Code Local History

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Contexto
O plugin VS Code `xyz.local-history` usa o formato `.history/{path}/{name}_{YYYYMMDDHHmmss}.{ext}`.

### Decisão
**Usar o mesmo formato.** Isso dá compatibilidade gratuita — snapshots criados no VS Code aparecem no IntelliJ e vice-versa. O risco de colisão de timestamps é quase zero (precisaria salvar o mesmo arquivo no mesmo segundo em dois editores).

### Exemplo
Arquivo `src/model/Livro.java` salvo em `08/05/2026 15:06:19`:
```
.history/src/model/Livro_20260508150619.java
```

---

## D-005: Debounce de 1 Segundo

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Contexto
O IntelliJ tem auto-save agressivo (a cada ~15s, ao trocar aba, ao perder foco). Sem debounce, cada auto-save geraria um snapshot, causando flood.

### Decisão
**Debounce de 1 segundo, hardcoded no MVP.** Se o mesmo arquivo for salvo múltiplas vezes dentro de 1 segundo, apenas o último save gera snapshot. Será tornado configurável na Fase 5.

---

## D-006: Deduplicação por SHA-256

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Contexto
Ctrl+S acidental (sem mudanças) geraria snapshots idênticos ao anterior.

### Decisão
**Comparar SHA-256** do conteúdo novo com o último snapshot do disco. Se o hash for idêntico, pular a escrita. O hash SHA-256 é computacionalmente barato para arquivos de texto típicos (<1MB) e garante detecção precisa de duplicatas.

---

## D-007: Diretórios Excluídos

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Lista Hardcoded (MVP)
```
.history/       → loop prevention (obrigatório)
.git/           → versionamento git
.idea/          → config IntelliJ
.gradle/        → cache Gradle
node_modules/   → dependências JS
build/          → output Gradle/Maven
target/         → output Maven
out/            → output IntelliJ
dist/           → output frontend
```

### Filtros Adicionais
- Arquivos binários (`file.fileType.isBinary`) → ignorar
- Arquivos > 2MB → ignorar

Será tornado configurável na Fase 5.

---

## D-008: Diff Viewer — DiffManager Nativo

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Decisão
Usar o `DiffManager` nativo do IntelliJ. Oferece syntax highlighting, side-by-side, unified view, fold de trechos iguais, e temas — tudo com ~10 linhas de código. Construir um viewer customizado seria semanas de trabalho sem ganho.

---

## D-009: Integração com .gitignore

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Decisão
Na primeira vez que `.history/` for criada, o plugin verifica se `.gitignore` existe na raiz do projeto. Se existir e `.history/` não estiver listado, appenda a linha. Usa VFS para essa escrita específica pois a IDE precisa rastrear o `.gitignore`.

---

## D-011: Captura por Mudança de Documento (DocumentChangeListener)

**Data:** 2026-05-18  
**Status:** ✅ Aprovada

### Contexto
O `DocumentSaveListener` (baseado em `FileDocumentManagerListener.beforeDocumentSaving`) depende do ciclo de save do IntelliJ para disparar. O IntelliJ controla quando faz flush para o disco — o Ctrl+S agenda o flush, não o executa imediatamente. Na prática, isso resultava em atrasos de ~30s com save explícito e ~3min sem save, tornando os snapshots insuficientes para análise de evolução de código (ex: detecção de padrões/cola em ambiente acadêmico).

### Análise das Alternativas
| Opção | Prós | Contras |
|---|---|---|
| Reduzir debounce do save listener | Mudança mínima | Não resolve: o atraso é na IDE, não no debounce |
| Timer periódico fixo | Previsível, simples | Captura em intervalos de relógio, não de atividade |
| **DocumentChangeListener** | Captura por atividade real; independe de save | Mais snapshots; captura estados intermediários |
| Forçar `saveAllDocuments()` periodicamente | Zero mudança no pipeline | Interfere com formatters/linters on-save da IDE |

### Decisão
**`BulkAwareDocumentListener`** registrado via `EditorFactory.eventMulticaster.addDocumentListener(listener, project)`.

- `BulkAwareDocumentListener` (em vez de `DocumentListener` puro) suprime eventos individuais durante operações bulk (refactor, paste grande) e entrega um único evento ao final — evita storm de resets no debounce.
- Registro via `eventMulticaster` cobre todos os documentos do projeto sem duplicação (mesmo documento em 2 tabs = 1 evento).
- `project` como `Disposable` garante remoção automática ao fechar o projeto (zero memory leak).
- Leitura de `document.text` via `readAction { }` suspending (não bloqueia a thread IO).

### Dual Debounce
Um debounce simples de inatividade seria resetado indefinidamente durante digitação contínua, nunca gerando snapshots. A estratégia de dois timers resolve isso:

- **Inactivity timer (30s):** reseta a cada keystroke → snapshot 30s após o último evento
- **Max interval timer (60s):** inicia uma vez, não reseta → snapshot garantido durante typing contínuo

Ambos os valores são candidatos a tornar-se configuráveis na Fase 5 (`LocalHistorySettings`).

### Coexistência com DocumentSaveListener
Os dois listeners operam em paralelo. O `DocumentSaveListener` mantém a captura rápida (<2s) em saves explícitos. Sobreposições são eliminadas pela deduplicação SHA-256 existente no `SnapshotService.processSnapshot()`.

---

## D-010: Política de Retenção (Fase 5)

**Data:** 2026-05-08  
**Status:** ✅ Aprovada

### Decisão
- Excluir snapshots com mais de **30 dias** (default, configurável)
- Manter máximo de **50 revisões** por arquivo (default, configurável)
- Cleanup assíncrono no startup do projeto e a cada 1h
