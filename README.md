# 🕐 IntelliJ Local History

> Um plugin para IDEs JetBrains que salva automaticamente cópias timestamped de cada arquivo ao salvá-lo — replicando o comportamento do plugin [Local History do VS Code](https://marketplace.visualstudio.com/items?itemName=xyz.local-history) diretamente dentro do IntelliJ IDEA.

[![Build](https://github.com/PabloTzeliks/intellij-local-history/workflows/Build/badge.svg)](https://github.com/PabloTzeliks/intellij-local-history/actions)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2025.2-000000?logo=intellijidea&logoColor=white)](https://plugins.jetbrains.com/docs/intellij/)
[![License](https://img.shields.io/github/license/PabloTzeliks/intellij-local-history)](LICENSE)

---

## O Problema

O VS Code possui o plugin **Local History**, que cria um histórico local automático de cada arquivo salvo — algo extremamente útil para acompanhar a evolução de um código ao longo do tempo, sem depender do Git.

O IntelliJ IDEA, apesar de ser uma IDE mais completa para projetos Java/Kotlin, **não oferece esse recurso nativamente de forma exportável**. Sua funcionalidade interna de "Local History" é opaca, não gera arquivos no disco e não é compatível com outras ferramentas.

Este plugin resolve esse problema trazendo o mesmo fluxo de trabalho do VS Code para dentro do IntelliJ.

---

## O Que É Este Plugin

**IntelliJ Local History** monitora cada salvamento de arquivo na IDE e cria automaticamente uma cópia timestamped em uma pasta `.history/` na raiz do projeto. O formato é 100% compatível com o VS Code Local History:

```
.history/
└── src/
    └── main/
        └── kotlin/
            └── Main_20260508150619.kt   ← snapshot automático
            └── Main_20260522134502.kt   ← outro snapshot
```

Além de guardar os arquivos, o plugin oferece uma **Tool Window visual** dentro da própria IDE para navegar pelo histórico e comparar versões lado a lado.

---

## Funcionalidades

| Funcionalidade | Status |
|---|---|
| Captura automática a cada save | ✅ Implementado |
| Debounce de 1 segundo (evita flood de snapshots) | ✅ Implementado |
| Deduplicação por SHA-256 (evita snapshots idênticos) | ✅ Implementado |
| Filtro de arquivos binários e diretórios gerados | ✅ Implementado |
| Atualização automática do `.gitignore` | ✅ Implementado |
| Tool Window com lista de snapshots em tempo real | ✅ Implementado |
| Diff nativo do IntelliJ (snapshot × arquivo atual) | ✅ Implementado |
| Compatibilidade de formato com VS Code Local History | ✅ Implementado |
| Restauração de snapshot para o arquivo atual | 🔄 Em breve (Fase 4) |
| Painel de configurações (retenção, dias, revisões máximas) | 🔄 Em breve (Fase 5) |

---

## Como Funciona

### Fluxo de captura

```
Você pressiona Ctrl+S
       │
       ▼
DocumentSaveListener (EDT — ultra rápido, sem I/O)
       │  verifica: binário? diretório excluído? guard ativo?
       ▼
SnapshotService.enqueue()
       │  debounce de 1 segundo
       ▼
Dispatchers.IO (thread de background)
       │  compara SHA-256 com último snapshot
       │  se diferente → escreve flat file em .history/
       ▼
.history/src/.../Arquivo_20260522134502.kt  ← snapshot criado
```

### Fluxo da Tool Window

```
Você abre um arquivo no editor
       │
       ▼
LocalHistoryToolWindowFactory detecta a troca
       │
       ▼
LocalHistoryPanel.loadFile() — mostra "Loading..."
       │  lança coroutine em Dispatchers.IO
       ▼
SnapshotReader.listSnapshots() — lê .history/ do disco
       │  retorna lista ordenada (mais recente primeiro)
       ▼
JBList atualizada na EDT — lista exibida instantaneamente
```

---

## Instalação

> ⚠️ O plugin ainda está em desenvolvimento e **não foi publicado no JetBrains Marketplace**. Para testá-lo, use a instalação manual a partir do build local.

### Pré-requisitos

- IntelliJ IDEA 2025.1 ou superior (ou qualquer IDE JetBrains baseada na plataforma 2025+)
- JDK 21+

### Build e instalação manual

```bash
# 1. Clone o repositório
git clone https://github.com/PabloTzeliks/intellij-local-history.git
cd intellij-local-history

# 2. Gere o arquivo .zip do plugin
./gradlew buildPlugin

# 3. O arquivo gerado estará em:
#    build/distributions/intellij-local-history-<versão>.zip
```

Em seguida, na sua IDE JetBrains:

1. Acesse **Settings** → **Plugins** → ícone ⚙️ → **Install Plugin from Disk...**
2. Selecione o arquivo `.zip` gerado
3. Reinicie a IDE quando solicitado

### Testando em modo sandbox

Para abrir uma instância isolada da IDE com o plugin carregado, sem instalar em sua IDE principal:

```bash
./gradlew runIde
```

---

## Como Usar

### Visualizando o histórico

Após instalar o plugin, uma nova **Tool Window "Local History"** aparece na barra inferior da IDE.

1. Abra qualquer arquivo de texto no editor
2. A lista de snapshots é carregada automaticamente
3. Se não houver histórico, a mensagem *"No history available for this file"* é exibida

### Comparando versões

1. Selecione um snapshot na lista da Tool Window
2. Clique em **"Compare with Current"** (ou dê duplo-clique no item)
3. O viewer de diff nativo do IntelliJ abre com:
   - **Esquerda:** conteúdo do snapshot (com data/hora no título)
   - **Direita:** conteúdo atual do arquivo

---

## Arquivos Ignorados

O plugin **não cria snapshots** para:

| Categoria | Diretórios / Condições |
|---|---|
| Histórico próprio | `.history/` |
| Controle de versão | `.git/` |
| Configuração da IDE | `.idea/` |
| Build Gradle | `.gradle/`, `build/`, `out/` |
| Build Maven | `target/` |
| Dependências JS | `node_modules/`, `dist/` |
| Arquivos binários | Imagens, JARs, `.class`, etc. |
| Arquivos grandes | Acima de **2 MB** |

---

## Estrutura do Projeto

```
src/main/kotlin/.../
├── listener/
│   └── DocumentSaveListener.kt    # Intercepta eventos de save da IDE
├── service/
│   ├── SnapshotService.kt         # Orquestra debounce, dedup e escrita
│   └── GitignoreService.kt        # Mantém .gitignore atualizado
├── storage/
│   ├── SnapshotWriter.kt          # Escreve flat files em .history/
│   └── SnapshotReader.kt          # Lista e lê snapshots do disco
├── model/
│   ├── SnapshotRequest.kt         # Dados capturados no momento do save
│   └── SnapshotEntry.kt           # Representação de um snapshot existente
├── ui/
│   ├── LocalHistoryToolWindowFactory.kt
│   └── LocalHistoryPanel.kt       # Lista, loading state e botão de diff
├── action/
│   └── CompareWithCurrentAction.kt
└── util/
    ├── SnapshotGuard.kt           # Previne loops durante restore
    └── FileFilters.kt             # Decide quais arquivos capturar
```

---

## Stack Tecnológica

| Componente | Tecnologia |
|---|---|
| Linguagem | Kotlin 2.1.10 |
| Plataforma | IntelliJ Platform SDK 2025.2 |
| Build | Gradle (Kotlin DSL) + IntelliJ Platform Gradle Plugin 2.x |
| Testes | JUnit 4 + IntelliJ Platform TestFramework |
| Concorrência | Kotlin Coroutines (`project.coroutineScope`, `Dispatchers.IO`) |
| Storage | Flat files via `java.nio.file` |

---

## Desenvolvimento e Contribuição

### Rodando localmente

```bash
# Compilar o projeto
./gradlew build

# Abrir sandbox da IDE com o plugin
./gradlew runIde

# Rodar todos os testes
./gradlew test
```

### Decisões arquiteturais

As principais decisões de design do projeto estão documentadas em [`docs/decisions.md`](docs/decisions.md). Algumas das mais importantes:

- **`java.nio.file` em vez de VFS** para escrever snapshots — evita re-indexação e não bloqueia a EDT ([D-003](docs/decisions.md#d-003-escrita-de-snapshots--javaniofile-não-vfs))
- **Formato compatível com VS Code** — snapshots criados em um editor aparecem no outro ([D-004](docs/decisions.md#d-004-compatibilidade-com-vs-code-local-history))
- **Debounce de 1 segundo** — evita flood de snapshots em auto-saves agressivos ([D-005](docs/decisions.md#d-005-debounce-de-1-segundo))
- **Deduplicação por SHA-256** — `Ctrl+S` sem mudanças não gera snapshot ([D-006](docs/decisions.md#d-006-deduplicação-por-sha-256))

### Regra crítica: nunca bloqueie a EDT

A Event Dispatch Thread é a thread de UI do IntelliJ. Qualquer I/O nela congela a IDE inteira. Todo acesso ao disco neste plugin ocorre em `Dispatchers.IO` via coroutines.

### Enviando contribuições

1. Faça um fork do repositório
2. Crie uma branch descritiva: `git checkout -b feature/restore-action`
3. Implemente e teste localmente com `./gradlew test` e `./gradlew runIde`
4. Abra um Pull Request descrevendo o que mudou e por quê

---

## Licença

Distribuído sob a licença MIT. Consulte o arquivo [LICENSE](LICENSE) para mais detalhes.

---

*Plugin baseado no [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).*
