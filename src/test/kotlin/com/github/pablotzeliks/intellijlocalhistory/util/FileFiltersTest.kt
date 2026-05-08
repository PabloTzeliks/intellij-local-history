package com.github.pablotzeliks.intellijlocalhistory.util

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

class FileFiltersTest : BasePlatformTestCase() {

    fun testShouldRejectFilesInExcludedDirectories() {
<<<<<<< HEAD
=======
        // We use LightVirtualFile to simulate files.
        // However, LightVirtualFile doesn't have a path that contains the projectBasePath in the same way 
        // a physical file does unless we simulate it.
        // Since FileFilters just removes projectBasePath from file.path, we can simulate the file.path behavior 
        // by creating an object that extends LightVirtualFile or just mocking if necessary.
        // Let's create a custom VirtualFile that returns a specific path.
        
>>>>>>> 7cef3ba2fe40ffc48b2275abf5867cf4fafe357f
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
<<<<<<< HEAD

        val nestedNodeModules = object : LightVirtualFile("index.js") {
            override fun getPath(): String = "/fake/project/frontend/node_modules/index.js"
        }
        assertFalse(FileFilters.shouldCapture(nestedNodeModules, projectPath))
=======
>>>>>>> 7cef3ba2fe40ffc48b2275abf5867cf4fafe357f
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
