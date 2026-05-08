# Blueprint e Arquitetura: IntelliJ Local History Plugin

## 1. Objetivo Principal

Criar um plugin leve e invisível para o IntelliJ Platform que intercepte qualquer salvamento de arquivo (automático ou manual) e gere cópias de backup versionadas por timestamp dentro de um diretório `.history` na raiz do projeto, espelhando o comportamento da extensão Local History do VS Code. A ferramenta deve gerenciar ativamente seu próprio armazenamento e oferecer uma interface nativa para visualizar/restaurar versões.

## 2. Requisitos Técnicos e Regras Restritas (Gotchas da JetBrains)

- **Virtual File System (VFS) vs. IO Padrão:** É estritamente proibido usar `java.io.File` ou `java.nio.file.Files` para manipulação de arquivos do projeto. Todas as operações de leitura, criação de diretórios e escrita devem usar a API `VfsUtil` ou instâncias de `VirtualFile` para que a IDE rastreie as mudanças.
- **Modelo de Concorrência:**
  - Leitura do VFS exige um bloco `ReadAction`.
  - Escrita/Modificação no VFS exige um bloco `WriteAction` e **deve** ser executada na Thread Principal (Event Dispatch Thread - EDT).
- **Prevenção de Loop Infinito:** O listener de salvamento (`FileDocumentManagerListener`) deve possuir um _guard clause_ explícito que ignore qualquer arquivo cujo caminho contenha a pasta `.history`.
- **Filtro de Diretórios Sensíveis:** O plugin deve ignorar silenciosamente arquivos dentro de `.git`, `node_modules`, `build`, `target` e `out`.
- **Integração com `.gitignore`:** Na primeira vez que a pasta `.history` for criada, o plugin deve verificar a existência de um `.gitignore` na raiz e anexar `.history/` a ele (usando `WriteAction`).

## 3. Stack Tecnológica

- **Linguagem:** Kotlin (aproveitando Coroutines para tarefas em background).
- **Interface (UI):** Kotlin UI DSL Version 2 para qualquer janela de diálogo ou Tool Window.
- **Base:** IntelliJ Platform Plugin Template (Gradle).

## 4. Gerenciamento de Retenção (Garbage Collection)

Implementar um processo de limpeza (ex: via `StartupActivity` ou tarefa agendada) que varra a pasta `.history` de forma assíncrona.

- **Regra de retenção:** Excluir fisicamente arquivos de backup mais antigos que 30 dias ou manter apenas as últimas 50 revisões por arquivo original.
