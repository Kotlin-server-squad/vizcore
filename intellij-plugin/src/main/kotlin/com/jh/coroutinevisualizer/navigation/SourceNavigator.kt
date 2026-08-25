package com.jh.coroutinevisualizer.navigation

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Resolves a backend-reported (fileName, className?, line) to a project source file and opens the
 * editor there. The pure [bestMatch] picks the best candidate path (className disambiguates when the
 * same simple filename exists in multiple modules); the IntelliJ glue ([jumpTo]) looks files up via
 * FilenameIndex and navigates via OpenFileDescriptor. Returns false when unresolved so the caller can
 * render the path as plain (non-clickable) text instead.
 */
class SourceNavigator(
    private val project: Project,
) {
    /** Open the editor at [fileName]:[line] (1-based). Returns false if no project file matches. */
    fun jumpTo(
        fileName: String,
        className: String?,
        line: Int,
    ): Boolean {
        val files: Collection<VirtualFile> =
            FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
        val chosenPath = bestMatch(fileName, className, files.map { it.path })
        val file = chosenPath?.let { path -> files.firstOrNull { it.path == path } } ?: return false
        val targetLine = (line - 1).coerceAtLeast(0)
        OpenFileDescriptor(project, file, targetLine, 0).navigate(true)
        return true
    }

    companion object {
        /**
         * Pick the best candidate path for a simple file name. With multiple candidates and a
         * [className], prefer the one whose path contains the class's package directory; otherwise
         * fall back to the first candidate (best-effort). Null only when there are no candidates.
         *
         * [fileName] is part of the resolution contract (the candidates are looked up by it) but is
         * not needed for disambiguation among already-matched candidates.
         */
        @Suppress("UnusedParameter")
        fun bestMatch(
            fileName: String,
            className: String?,
            candidates: List<String>,
        ): String? {
            val packagePath =
                className
                    ?.substringBeforeLast('.', "")
                    ?.replace('.', '/')
                    ?.takeIf { it.isNotEmpty() }
            return when {
                candidates.isEmpty() -> null
                candidates.size == 1 -> candidates.first()
                packagePath != null ->
                    candidates.firstOrNull { it.contains(packagePath) } ?: candidates.first()
                else -> candidates.first()
            }
        }
    }
}
