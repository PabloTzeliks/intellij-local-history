package com.github.pablotzeliks.intellijlocalhistory.service

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

/**
 * Serviço de projeto que fornece um [CoroutineScope] gerenciado pelo ciclo de vida do projeto
 * para uso nos componentes de UI do plugin (Tool Window, painéis).
 *
 * O IntelliJ Platform injeta automaticamente o [CoroutineScope] no construtor quando o serviço
 * é registrado como `projectService` no `plugin.xml`. O scope é cancelado automaticamente
 * ao fechar o projeto, sem necessidade de gerenciamento manual.
 *
 * **Não criar CoroutineScope manualmente** — isso causaria memory leak ao fechar o projeto.
 */
@Service(Service.Level.PROJECT)
class LocalHistoryUiScopeService(val scope: CoroutineScope)
