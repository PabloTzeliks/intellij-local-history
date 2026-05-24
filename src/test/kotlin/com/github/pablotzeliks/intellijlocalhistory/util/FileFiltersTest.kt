package com.github.pablotzeliks.intellijlocalhistory.util

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

class FileFiltersTest : BasePlatformTestCase() {

    fun testShouldRejectFilesInExcludedDirectories() {
        val projectPath = "/fake/project"
        
        val ideaFile = object : LightVirtualFile("workspace.xml") {
            override fun getPath(): String = "/fake/project/.idea/workspace.xml"
        }
        assertFalse(FileFilters.shouldCapture(ideaFile, projectPath))
        
        val buildFile = object : LightVirtualFile("classes.jar") {
            override fun getPath(): String = "/fake/project/build/classes.jar"
        }
        assertFalse(FileFilters.shouldCapture(buildFile, projectPath))
        
        val historyFile = object : LightVirtualFile("Snapshot.java") {
            override fun getPath(): String = "/fake/project/.history/src/Snapshot.java"
        }
        assertFalse(FileFilters.shouldCapture(historyFile, projectPath))

        val nestedNodeModules = object : LightVirtualFile("index.js") {
            override fun getPath(): String = "/fake/project/frontend/node_modules/index.js"
        }
        assertFalse(FileFilters.shouldCapture(nestedNodeModules, projectPath))
    }

    fun testShouldAcceptNormalSourceFiles() {
        val projectPath = "/fake/project"
        
        val ktFile = object : LightVirtualFile("Main.kt") {
            override fun getPath(): String = "/fake/project/src/main/kotlin/Main.kt"
        }
        assertTrue(FileFilters.shouldCapture(ktFile, projectPath))
        
        val txtFile = object : LightVirtualFile("README.md") {
            override fun getPath(): String = "/fake/project/README.md"
        }
        assertTrue(FileFilters.shouldCapture(txtFile, projectPath))
    }

    fun testShouldRejectFilesLargerThan2MB() {
        val projectPath = "/fake/project"

        val largeFile = object : LightVirtualFile("LargeFile.txt") {
            override fun getPath(): String = "/fake/project/LargeFile.txt"
            override fun getLength(): Long = (2 * 1024 * 1024) + 1L
        }
        assertFalse(FileFilters.shouldCapture(largeFile, projectPath))
    }

}
