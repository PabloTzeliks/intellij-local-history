# AGENTS.md — IntelliJ Local History Plugin

## O Que É Este Projeto

Plugin para o IntelliJ IDEA (e demais IDEs JetBrains) que replica o comportamento do plugin **Local History** do VS Code (`xyz.local-history`). A cada salvamento de arquivo, o plugin cria uma cópia timestamped em `.history/`, permitindo visualizar diffs e restaurar versões anteriores.

## Stack Tecnológica

| Componente | Tecnologia |
|---|---|
| Linguagem | **Kotlin** (JVM) |
| Plataforma | IntelliJ Platform SDK 2025.2 |
| Build | Gradle (Kotlin DSL) com IntelliJ Platform Gradle Plugin 2.16.0 |
| Testes | JUnit 4 + IntelliJ Platform TestFramework |
| UI | Kotlin UI DSL v2 |
| Storage | Flat files em `.history/` (java.nio.file) |

## Estrutura do Projeto

```
src/main/kotlin/com/github/pablotzeliks/intellijlocalhistory/
├── LocalHistoryBundle.kt          # i18n — strings de UI
├── listener/                      # Intercepta eventos de save da IDE
│   └── DocumentSaveListener.kt    # FileDocumentManagerListener
├── service/                       # Lógica de negócio (project-level)
│   ├── SnapshotService.kt         # Orquestração: debounce, dedup, delegação
│   ├── GitignoreService.kt        # Atualiza .gitignore automaticamente
│   └── RetentionService.kt        # Limpeza de snapshots antigos
├── storage/                       # Leitura/escrita no disco
│   ├── SnapshotWriter.kt          # Cria flat files em .history/
│   └── SnapshotReader.kt          # Lista/lê snapshots existentes
├── model/                         # Data classes imutáveis
│   └── SnapshotRequest.kt         # Dados capturados no momento do save
├── ui/                            # Interface visual (Tool Window)
│   ├── LocalHistoryToolWindowFactory.kt
│   └── LocalHistoryPanel.kt
├── util/                          # Utilitários compartilhados
│   ├── SnapshotGuard.kt           # Previne loops durante restore
│   └── FileFilters.kt             # Decide quais arquivos capturar
└── settings/                      # Configurações persistentes (Fase 5)
    ├── LocalHistorySettings.kt
    └── LocalHistoryConfigurable.kt
```

## Configuração Principal

- **`plugin.xml`** (`src/main/resources/META-INF/`) — Registro central: listeners, extensions, services
- **`LocalHistoryBundle.properties`** (`src/main/resources/messages/`) — Strings de UI
- **`build.gradle.kts`** — Dependências e configuração de build
- **`gradle.properties`** — Group, version, flags do Gradle

## Regras Críticas de Desenvolvimento

### 1. Nunca Bloqueie a EDT
A Event Dispatch Thread (EDT) é a thread de UI do IntelliJ. Qualquer operação longa nela congela a IDE inteira. Regras:
- **Listeners de save** (`beforeDocumentSaving`) rodam na EDT → fazer o mínimo possível (copiar dados) e delegar para background
- **I/O de disco** (escrita de snapshots) → sempre em `Dispatchers.IO` via coroutines
- **Leitura de VFS** em background → usar `readAction { }`

### 2. Prevenção de Loop Infinito
Quando o plugin **restaura** um arquivo (escrevendo conteúdo de volta), isso dispara o listener de save novamente. O `SnapshotGuard` (AtomicInteger) bloqueia re-entradas.

Adicionalmente, **qualquer arquivo cujo path contenha `.history/`** é ignorado pelo listener.

### 3. Escrita de Snapshots usa java.nio.file
Ao contrário do que o BLUEPRINT.md sugere, snapshots em `.history/` NÃO usam a API VFS do IntelliJ. Motivos:
- VFS dispara re-indexação desnecessária
- VFS exige WriteAction que bloqueia a EDT
- `.history/` não é código-fonte — é dado auxiliar do plugin

### 4. Formato de Naming dos Snapshots
Compatível com VS Code Local History:
```
.history/{caminho-relativo}/{NomeSemExtensão}_{YYYYMMDDHHmmss}.{extensão}
```
Exemplo: `src/model/Livro.java` salvo em `08/05/2026 15:06:19`
→ `.history/src/model/Livro_20260508150619.java`

### 5. Diretórios Excluídos (hardcoded no MVP)
O plugin ignora arquivos dentro de: `.history/`, `.git/`, `.idea/`, `.gradle/`, `node_modules/`, `build/`, `target/`, `out/`, `dist/`

Também ignora: arquivos binários e arquivos maiores que 2MB.

### 6. Debounce
Saves do mesmo arquivo com menos de 1 segundo de intervalo são agrupados — apenas o último gera snapshot.

### 7. Deduplicação
Antes de escrever, o SHA-256 do conteúdo é comparado com o último snapshot. Se idêntico, a escrita é pulada.

## Documentação Adicional

- **`BLUEPRINT.md`** — Requisitos originais (contém algumas premissas revisadas, ver `docs/decisions.md`)
- **`docs/decisions.md`** — Registro de todas as decisões arquiteturais com contexto e trade-offs
- **IntelliJ Platform SDK Docs** — https://plugins.jetbrains.com/docs/intellij/

## Comandos Úteis

```bash
# Compilar o plugin
./gradlew build

# Abrir uma instância sandbox do IntelliJ com o plugin carregado
./gradlew runIde

# Rodar testes
./gradlew test
```
