package com.github.pablotzeliks.intellijlocalhistory.ui

import com.github.pablotzeliks.intellijlocalhistory.LocalHistoryBundle
import com.github.pablotzeliks.intellijlocalhistory.action.CompareWithCurrentAction
import com.github.pablotzeliks.intellijlocalhistory.model.SnapshotEntry
import com.github.pablotzeliks.intellijlocalhistory.storage.SnapshotReader
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.DecimalFormat
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import kotlin.math.log10
import kotlin.math.pow

class LocalHistoryPanel(
    private val project: Project,
    private val cs: CoroutineScope
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<SnapshotEntry>()
    private val snapshotList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = LocalHistoryBundle.message("toolWindow.empty")
        cellRenderer = SnapshotListCellRenderer()
    }

    private var currentFile: VirtualFile? = null
    private var loadingJob: Job? = null

    init {
        val scrollPane = JBScrollPane(snapshotList)
        add(scrollPane, BorderLayout.CENTER)

        setupToolbar()
        setupDoubleClickListener()
    }

    private fun setupToolbar() {
        val actionManager = ActionManager.getInstance()
        val compareAction = actionManager.getAction("LocalHistory.CompareWithCurrent")

        if (compareAction == null) {
            thisLogger().warn("Local History: action 'LocalHistory.CompareWithCurrent' not found — toolbar button will be missing")
        }

        val actionGroup = DefaultActionGroup().apply {
            if (compareAction != null) add(compareAction)
        }

        val toolbar = actionManager.createActionToolbar(
            ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
            actionGroup,
            true
        )
        toolbar.targetComponent = snapshotList
        add(toolbar.component, BorderLayout.NORTH)
    }

    private fun setupDoubleClickListener() {
        snapshotList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    val entry = getSelectedEntry() ?: return
                    val file = getCurrentFile() ?: return
                    // I/O permitido aqui: ação iniciada pelo usuário, arquivo < 2MB (garantido pelo FileFilters).
                    // Conforme especificado em phase-3.md, seção CompareWithCurrentAction.
                    CompareWithCurrentAction.showDiff(project, entry, file)
                }
            }
        })
    }

    fun loadFile(virtualFile: VirtualFile?) {
        loadingJob?.cancel()
        currentFile = virtualFile

        if (virtualFile == null) {
            listModel.clear()
            snapshotList.emptyText.text = LocalHistoryBundle.message("toolWindow.empty")
            return
        }

        snapshotList.emptyText.text = LocalHistoryBundle.message("toolWindow.loading")
        listModel.clear()

        loadingJob = cs.launch(Dispatchers.IO) {
            val projectDir = project.guessProjectDir() ?: return@launch
            val relativePath = VfsUtilCore.getRelativePath(virtualFile, projectDir) ?: return@launch

            val entries = SnapshotReader.listSnapshots(relativePath, projectDir.path)
            // TODO: Phase 5 — limitar número de entradas com base em LocalHistorySettings

            withContext(Dispatchers.Main) {
                listModel.clear()
                if (entries.isEmpty()) {
                    snapshotList.emptyText.text = LocalHistoryBundle.message("toolWindow.empty")
                } else {
                    entries.forEach { listModel.addElement(it) }
                }
            }
        }
    }

    /** Recarrega a lista do arquivo atual. Chamado pela Fase 4 após restore ou delete. */
    fun refresh() {
        loadFile(currentFile)
    }

    /** Retorna o snapshot selecionado ou null. Contrato para Fase 4 (Restore/Delete). */
    fun getSelectedEntry(): SnapshotEntry? = snapshotList.selectedValue

    /** Retorna o arquivo atualmente exibido. Contrato para Fase 4 (Restore/Delete). */
    fun getCurrentFile(): VirtualFile? = currentFile

    private class SnapshotListCellRenderer : ColoredListCellRenderer<SnapshotEntry>() {

        override fun customizeCellRenderer(
            list: JList<out SnapshotEntry>,
            value: SnapshotEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return

            icon = AllIcons.FileTypes.Text

            val dateStr = value.timestamp.format(DATE_FORMATTER)
            append(dateStr, SimpleTextAttributes.REGULAR_ATTRIBUTES)

            val sizeStr = formatSize(value.lengthBytes)
            append("  ($sizeStr)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (log10(bytes.toDouble()) / log10(1024.0))
                .toInt()
                .coerceIn(0, units.lastIndex)  // protege contra valores fora do range
            val format = DecimalFormat("#,##0.#")
            return format.format(bytes / 1024.0.pow(digitGroups)) + " " + units[digitGroups]
        }
    }

    companion object {
        /**
         * Formato de data exibido em cada item da lista.
         * TODO: Phase 5 — ler de LocalHistorySettings
         */
        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    }
}
