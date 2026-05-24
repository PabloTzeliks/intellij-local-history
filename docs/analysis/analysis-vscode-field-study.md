# Análise de Campo — VSCode Local History em Ambiente Real de Prova

**Data da sessão analisada:** 22/05/2026  
**Duração da sessão:** 163 minutos (18:15:28 → 20:58:56)  
**Fonte dos dados:** Extensão `xyz.local-history` para VSCode (formato flat files, compatível com nosso plugin)  
**Arquivos analisados:** `.history/` gerado durante uma prova de Back-End (Spring Boot, Java, DDD)  

---

## 1. Contexto

Esta análise parte de dados reais capturados durante uma prova acadêmica de desenvolvimento Back-End. O aluno utilizou o VSCode com a extensão Local History (mesmo formato de nomenclatura que nosso plugin adota: `{nome}_{yyyyMMddHHmmss}.{ext}`). A pasta `.history/` gerada foi preservada integralmente para análise.

O objetivo é triplo:

1. Entender **como o VSCode Local History se comporta em condições reais** de alta pressão (prova, salva frequente, múltiplos arquivos)
2. Identificar **onde o concorrente falha** — brechas que nosso plugin já preenche ou deve preencher
3. Identificar **onde o concorrente acerta** — comportamentos que precisamos garantir ou superar

---

## 2. Dados Brutos da Sessão

| Métrica | Valor |
|---|---|
| Total de snapshots capturados | 1.396 |
| Arquivos fonte únicos rastreados | 19 |
| Snapshots redundantes (conteúdo idêntico confirmado por MD5) | **161 (11,5%)** |
| Snapshots de arquivos em branco (0 bytes) | 16 |
| Arquivo mais rastreado | `CategoriaService.java` — 281 snapshots |
| Mediana de intervalo entre snapshots | **4 segundos** |
| Intervalo mínimo | 1 segundo |
| Intervalo máximo | 8.815 segundos (gap entre sessões) |
| Distribuição dominante | 2–5s: **746 capturas (54%)** |

---

## 3. Comportamento do VSCode Local History (concorrente)

### 3.1 Mecanismo de Captura

O VSCode Local History captura exclusivamente em eventos de **save explícito** (`Ctrl+S` ou equivalente). Não existe nenhum mecanismo de captura por inatividade, timer periódico ou detecção de mudança em memória.

**Evidência nos dados:** A distribuição de intervalos não apresenta nenhum pico em 15s, 30s ou qualquer valor periódico. Os 54% de intervalos no range 2–5s refletem diretamente o ritmo de `Ctrl+S` de um aluno sob pressão. Não há padrão temporal — há padrão comportamental.

**Consequência crítica:** Se o aluno escrever 50 linhas de código sem salvar, o VSCode não registra nenhum estado intermediário. O plugin é cego para o período entre dois saves. Em ambiente acadêmico, isso pode esconder:
- Reescrita completa de um método antes do save
- Copy-paste seguido de modificação antes do save
- Apagamento de tentativas fracassadas antes do save

### 3.2 Ausência de Deduplicação

O concorrente não implementa nenhuma forma de deduplicação de conteúdo. Isso foi confirmado por comparação de MD5 entre snapshots consecutivos:

**161 pares de snapshots com conteúdo byte-a-byte idêntico foram gravados em disco.**

Três padrões de redundância foram identificados:

**Padrão A — Burst de saves (1s de intervalo):**  
O aluno pressiona `Ctrl+S` duas vezes em menos de 1 segundo. O conteúdo não mudou. Dois arquivos idênticos são criados.

```
CategoriaMapper_20260522184711.java   [1.336 bytes]
CategoriaMapper_20260522184712.java   [1.336 bytes]  ← idêntico
```

**Padrão B — Save sem mudança após pausa:**  
O aluno abre o arquivo, lê, pressiona `Ctrl+S` por hábito sem editar nada. Um novo snapshot idêntico ao anterior é criado.

```
CategoriaRepository_20260522191804.java   [503 bytes]
CategoriaRepository_20260522192208.java   [503 bytes]  ← idêntico (244s depois)
```

**Padrão C — Cross-session miss (o mais grave):**  
`Categoria.java` foi salva às 18:28 com 937 bytes. O IDE foi reiniciado (lacuna de 8.815 segundos). Às 20:54, o arquivo foi salvo novamente — com **exatamente o mesmo conteúdo**. Um snapshot idêntico foi criado 2h27min depois.

```
Categoria_20260522182800.java   [937 bytes]
Categoria_20260522205455.java   [937 bytes]  ← idêntico ao byte, 8.815s depois
```

### 3.3 Ausência de Debounce

Não há mecanismo que colapse saves consecutivos muito próximos em um único snapshot. Cada `Ctrl+S` que chega ao handler gera um arquivo em disco, independente de quantos foram disparados no mesmo segundo. Os 186 intervalos ≤ 1s na sessão analisada mostram que múltiplos arquivos redundantes foram criados em rajadas de save.

### 3.4 Captura de Arquivos em Branco (0 bytes)

O plugin captura o momento de criação do arquivo — quando o IntelliJ ou o VSCode cria um novo arquivo, ele começa vazio e a extensão captura esse estado zero antes de qualquer conteúdo ser inserido. Foram identificados 16 snapshots de 0 bytes na sessão.

```
CategoriaService_20260522183538.java   [0 bytes]      ← criação
CategoriaService_20260522183539.java   [106 bytes]    ← 1s depois, primeiro conteúdo
```

Do ponto de vista de análise, esses snapshots são ruído — não informam nada sobre o trabalho do aluno.

### 3.5 Gaps Invisíveis no Histórico

O plugin não registra nenhuma informação sobre os períodos em que o aluno estava trabalhando em outro arquivo. Ao analisar o histórico de `CategoriaController.java`, há um gap de 2.987 segundos (49 minutos) entre 19:52 e 20:42. Não é possível saber, olhando apenas esse histórico, se o aluno:
- Estava trabalhando em `ProdutoService.java` (o que foi o caso — confirmado pelos dados desse arquivo)
- Estava pesquisando no navegador
- Estava parado

A visão é puramente por arquivo, sem nenhum contexto cruzado.

---

## 4. O que o VSCode Local History acerta

Antes de criticar, é honesto reconhecer o que funciona bem:

- **Simplicidade zero-config:** A extensão funciona sem nenhuma configuração. Instala e começa a capturar.
- **Formato universal:** O flat file com naming `{nome}_{timestamp}.{ext}` é legível por qualquer ferramenta, navegável no filesystem, auditável manualmente.
- **Baixíssima latência de escrita:** Por não fazer nenhum processamento (sem hash, sem debounce), a escrita em disco é imediata após o save.
- **Histórico por arquivo:** O modelo mental é simples — cada arquivo tem sua linha do tempo individual, navegável no explorer do VSCode.

---

## 5. Comparação Direta: VSCode vs. Nosso Plugin

| Capacidade | VSCode Local History | Nosso Plugin (IntelliJ) |
|---|---|---|
| Captura em save explícito | ✅ Sim | ✅ Sim (`DocumentSaveListener`) |
| Captura por inatividade (sem save) | ❌ Não | ✅ Sim (`DocumentChangeListener`, 15s) |
| Captura durante digitação contínua | ❌ Não | ✅ Sim (timer máximo de 30s) |
| Deduplicação de conteúdo | ❌ Não | ✅ Sim (SHA-256) — *com bug TOCTOU* |
| Deduplicação cross-session | ❌ Não | ✅ Sim (lê último snapshot do disco) — *com bug de line ending* |
| Debounce de saves rápidos | ❌ Não | ✅ Sim (500ms) |
| Filtro de arquivos binários | Provável | ✅ Sim (`VirtualFile.fileType.isBinary`) |
| Filtro de tamanho (>2MB) | Desconhecido | ✅ Sim |
| Filtro de 0 bytes | ❌ Não | ❌ Não — *a corrigir* |
| Exclusão de diretórios gerados | Parcial | ✅ Sim (lista configurável na Fase 5) |
| Auto-update do `.gitignore` | ❌ Não | ✅ Sim (`GitignoreService`) |
| Diff viewer integrado ao IDE | ❌ Usa viewer externo | ✅ Sim (DiffManager nativo) |
| Tool Window persistente | ❌ Explorer lateral, não integrado | ✅ Sim (barra inferior) |
| Indicação de gaps temporais na UI | ❌ Não | ❌ Não — *a implementar* |
| Restore de snapshot | ✅ Sim | 🔄 Fase 4 |
| Configuração de retenção | ✅ Sim | 🔄 Fase 5 |
| Suporte a múltiplos workspaces | ✅ Sim | Por projeto (escopo atual) |

---

## 6. Falhas Identificadas no VSCode Local History (oportunidades para nós)

### F-001: Ausência de Deduplicação — 11,5% de snapshots inúteis

Em 163 minutos de prova, 161 dos 1.396 snapshots criados eram cópias exatas de snapshots anteriores. Isso é **desperdício puro** de disco e, mais importante, **ruído analítico** — ao analisar a evolução do código de um aluno, o professor encontra múltiplas entradas idênticas intercaladas na linha do tempo sem nenhum valor informacional.

**O que o nosso plugin faz:** SHA-256 garante que conteúdo idêntico não gera arquivo em disco.  
**O que precisamos garantir:** Corrigir o bug TOCTOU para que essa garantia seja efetiva (ver seção 7).

### F-002: Cegueira entre saves — risco real em provas

O maior ponto cego do VSCode Local History é o que acontece **entre** dois saves. Em uma prova de 163 minutos com mediana de 4s entre saves, o aluno salvou frequentemente. Mas e se um aluno estratégico aprender que o professor analisa o histórico? Basta não salvar enquanto copia, salvar depois de reescrever. O VSCode não veria nada.

**O que o nosso plugin faz:** `DocumentChangeListener` captura o estado do documento em memória a cada 15s de inatividade ou 30s de digitação contínua, **independente de save**. Isso fecha esse vetor.

### F-003: Cross-session miss — silencioso e enganoso

O save de `Categoria.java` às 20:54 criou um snapshot idêntico ao de 18:28. Para o professor, isso aparece como "o aluno abriu o arquivo e salvou" — o que é factualmente correto, mas não há como distinguir de "o aluno modificou e depois reverteu". O snapshot extra é ruído sem custo aparente, mas acumula em projetos grandes.

**O que o nosso plugin deve garantir:** Dedup cross-session funcional com line endings normalizados.

### F-004: Snapshots de 0 bytes — ruído na criação de arquivo

16 entradas vazias não comunicam nada além de "este arquivo foi criado neste momento", o que já é deduplicável a partir do snapshot de 1s depois (primeiro conteúdo real). Em uma UI de análise, esses itens apareceriam no topo do histórico de cada arquivo, potencialmente confundindo o analista.

**O que o nosso plugin deve fazer:** Rejeitar snapshots com conteúdo vazio no `FileFilters`.

---

## 7. Falhas Identificadas no Nosso Plugin (verificadas no código-fonte)

### B-001: Race Condition TOCTOU no SnapshotService (CRÍTICO)

**Confirmado por leitura do código** (`SnapshotService.kt`).

`processSnapshot()` é chamado por coroutines independentes: uma lançada por `captureNow()` (vinda do `DocumentChangeListener`) e outra lançada por `enqueue()` (vinda do `DocumentSaveListener`). Ambas rodam em `Dispatchers.IO` — pool de threads — sem nenhuma sincronização entre si.

A sequência de deduplicação não é atômica:

```kotlin
// Ambas as coroutines executam isso concorrentemente para o mesmo arquivo:
if (lastHashByPath[request.relativePath] == newHash) return   // 1. check (leitura)
...
SnapshotWriter.write(request)                                  // 2. write
lastHashByPath[request.relativePath] = newHash                 // 3. update (escrita)
```

`ConcurrentHashMap` garante atomicidade em operações individuais (`get`, `put`), mas não em sequências de operações. Dois coroutines podem ambos executar o `get` no passo 1 antes que qualquer um execute o `put` no passo 3 — ambos veem o hash antigo, ambos passam, ambos gravam um snapshot idêntico ao mesmo tempo.

**Impacto:** Nosso plugin produz snapshots redundantes pelo mesmo mecanismo que o VSCode, anulando a vantagem do SHA-256. A frequência depende do quão próximas os dois listeners disparam — em saves rápidos (Ctrl+S seguido de pausa de 15s), a janela de corrida existe.

**Correção necessária:** Substituir a sequência check-write-update por uma operação atômica. A solução idiomática em Kotlin com coroutines é um `Mutex` por arquivo (`kotlinx.coroutines.sync.Mutex`) envolvendo os três passos como seção crítica.

---

## 8. O que Aprendemos sobre o Comportamento Real do Usuário em Prova

Esses dados são a primeira base empírica real do projeto. Alguns padrões surpreendentes:

**Padrão de save:** A mediana de 4s entre saves revela que alunos em prova salvam de forma quase reflexa — provavelmente `Ctrl+S` embutido no ritmo de digitação, não como ato consciente. Isso significa que o `DocumentSaveListener` capta a maior parte da evolução real do código. O `DocumentChangeListener` serve como rede de segurança, não como fonte primária.

**Foco por arquivo:** Cada arquivo teve períodos de atividade intensa seguidos de abandono. `CategoriaService.java` acumulou 281 snapshots — mais do que muitos arquivos inteiros. O padrão mostra que o aluno "habitou" esse arquivo por longos períodos antes de passar para o próximo. Isso valida a Tool Window por arquivo como interface correta.

**Progressão arquitetural:** A linha do tempo mostra uma progressão DDD clara: entity → repository → DTO → mapper → service → controller, repetida para cada agregado (Categoria, depois Produto). Os arquivos de relatório (`RelatorioSimplificado*`) aparecem apenas nos últimos 40 minutos, como funcionalidade avançada. Isso confirma que o plugin captura o processo de raciocínio, não apenas o resultado.

**Typo revelador:** `CateogoriaRequestDTO.java` (com typo) tem 8 snapshots e depois desaparece do histórico — o aluno renomeou o arquivo. O histórico do arquivo com o nome errado fica permanentemente em `.history/`. Isso é comportamento correto (o passado não é apagado), mas a interface não oferece nenhuma conexão entre o arquivo renomeado e seu antecessor.

---

## 9. Resumo: Nossa Vantagem Competitiva Real

Frente ao VSCode Local History em uso acadêmico, nosso plugin tem uma vantagem estrutural não eliminável pelo concorrente sem uma reescrita de arquitetura: **a captura por estado em memória (DocumentChangeListener)**.

O VSCode é fundamentalmente um editor de texto com filesystem awareness. O histórico é um reflexo dos saves. Um aluno que entende isso pode gerenciar o que o histórico vê.

O IntelliJ é uma plataforma com modelo de documento em memória (`Document`). Nosso plugin acessa esse modelo diretamente, independente de saves. O aluno não consegue "controlar" o que é capturado sem desabilitar o plugin inteiro.

Essa assimetria é irreplicável pelo concorrente VSCode sem mudar a arquitetura da extensão.

**O que precisamos fazer antes de poder confiar nessa vantagem:**

Corrigir B-001 (TOCTOU) — a deduplicação por SHA-256 existe no código, mas a condição de corrida entre `DocumentSaveListener` e `DocumentChangeListener` permite que dois snapshots idênticos sejam gravados simultaneamente. Enquanto esse bug existir, a vantagem sobre o VSCode é apenas teórica.

Sem essa correção, o comportamento observado nos dados do VSCode — 11,5% de snapshots redundantes — pode se repetir no nosso plugin pelo mesmo mecanismo, porém com causa interna em vez de ausência de dedup.
