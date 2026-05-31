package top.k88936

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiUtilBase
import org.jetbrains.annotations.NonNls
import java.awt.datatransfer.StringSelection

class EverythingItNeedsAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val filePath = virtualFile.path

        if (editor != null && editor.selectionModel.hasSelection()) {
            // Area selection: use selection start/end lines and show the selection context
            val selectionModel = editor.selectionModel
            val doc = editor.document
            val startLine = doc.getLineNumber(selectionModel.selectionStart)
            val endLine = doc.getLineNumber(selectionModel.selectionEnd)

            val refElement = resolveReferenceElement(editor, project)
            val refId = refElement?.let { buildQualifiedName(it) }
            val refKind = refElement?.let { getElementKind(it) }
            val codeContext = getSurroundingLinesForSelection(editor, startLine, endLine)
            val content =
                buildCopyContentForSelection(filePath, startLine + 1, endLine + 1, refId, refKind, codeContext)

            CopyPasteManager.getInstance().setContents(StringSelection(content))
        } else {
            // Single caret: existing behavior
            val lineNumber = editor?.let { it.caretModel.currentCaret.logicalPosition.line + 1 }

            val refElement = if (editor != null) {
                resolveReferenceElement(editor, project)
            } else {
                null
            }
            val refId = refElement?.let { buildQualifiedName(it) }
            val refKind = refElement?.let { getElementKind(it) }

            val codeContext = editor?.let { getSurroundingLines(it) }

            val content = buildCopyContent(filePath, lineNumber, refId, refKind, codeContext)

            CopyPasteManager.getInstance().setContents(StringSelection(content))
        }
    }

    private fun buildCopyContent(
        filePath: @NonNls String,
        lineNumber: Int?,
        refId: String?,
        refKind: String?,
        codeContext: String?
    ): @NonNls String {
        return buildString {
            append(filePath)
            if (lineNumber != null) {
                append(":$lineNumber")
            }
            if (refId != null) {
                append(" ($refId")
                if (refKind != null) {
                    append(", $refKind")
                }
                append(")")
            }
            if (codeContext != null) {
                append("\n\n").append(codeContext)
            }
        }
    }

    private fun buildCopyContentForSelection(
        filePath: @NonNls String,
        startLineNumber: Int,
        endLineNumber: Int,
        refId: String?,
        refKind: String?,
        codeContext: String?
    ): @NonNls String {
        return buildString {
            append(filePath)
            append(":$startLineNumber-$endLineNumber")
            if (refId != null) {
                append(" ($refId")
                if (refKind != null) {
                    append(", $refKind")
                }
                append(")")
            }
            if (codeContext != null) {
                append("\n\n").append(codeContext)
            }
        }
    }

    /**
     * Resolves the best [PsiNamedElement] at the editor's caret position.
     */
    private fun resolveReferenceElement(
        editor: Editor,
        project: com.intellij.openapi.project.Project
    ): PsiNamedElement? {
        val psiFile: PsiFile = PsiUtilBase.getPsiFileInEditor(editor, project) ?: return null
        val offset = editor.caretModel.offset

        // Try the most specific element at offset first
        val elementAtOffset = psiFile.findElementAt(offset)
        return findBestNamedElement(elementAtOffset)
    }

    /**
     * Finds the best [PsiNamedElement] by walking up the PSI tree from [element].
     *
     * Returns the first ancestor that is a [PsiNamedElement] with a meaningful name.
     */
    private fun findBestNamedElement(element: PsiElement?): PsiNamedElement? {
        if (element == null) return null

        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiNamedElement) {
                val name = current.name
                if (!name.isNullOrBlank() && current !is PsiFile) {
                    return current
                }
            }
            current = current.parent
        }
        return null
    }

    /**
     * Returns a human-readable element kind (e.g. "method", "class", "field") for a [PsiNamedElement].
     *
     * Uses the PSI node's element type string and maps common platform/language types to
     * concise names. Returns the raw type string in lowercase if no mapping is found.
     */
    private fun getElementKind(element: PsiNamedElement): String? {
        val psiElement = element as? PsiElement ?: return null
        val typeName = psiElement.node?.elementType?.toString() ?: return null
        return when {
            typeName.endsWith("METHOD", ignoreCase = true) ||
                    typeName.endsWith("FUNCTION", ignoreCase = true) ||
                    typeName == "KtNamedFunction" -> "method"

            typeName.endsWith("CLASS", ignoreCase = true) ||
                    typeName == "KtClassOrObject" -> "class"

            typeName.endsWith("FIELD", ignoreCase = true) ||
                    typeName.endsWith("PROPERTY", ignoreCase = true) ||
                    typeName == "KtProperty" -> "field"

            typeName.endsWith("VARIABLE", ignoreCase = true) -> "variable"
            typeName.endsWith("PARAMETER", ignoreCase = true) ||
                    typeName == "KtParameter" -> "parameter"

            typeName.endsWith("ENUM", ignoreCase = true) ||
                    typeName == "KtEnumEntry" -> "enum"

            typeName.endsWith("INTERFACE", ignoreCase = true) -> "interface"
            typeName.endsWith("ANNOTATION", ignoreCase = true) ||
                    typeName == "KtAnnotationEntry" -> "annotation"

            typeName.endsWith("CONSTRUCTOR", ignoreCase = true) -> "constructor"
            else -> typeName.lowercase()
        }
    }

    private fun buildQualifiedName(element: PsiNamedElement): String? {
        val name = element.name ?: return null

        val parts = mutableListOf(name)
        var current: PsiElement? = element.parent

        // Walk up to collect enclosing names, stop at PsiFile level
        while (current != null && current !is PsiFile) {
            if (current is PsiNamedElement) {
                val currentName = current.name
                if (!currentName.isNullOrBlank()) {
                    parts.add(0, currentName)
                }
            }
            current = current.parent
        }

        return parts.joinToString(".")
    }

    private fun getSurroundingLines(editor: Editor): String {
        val document = editor.document
        val caretLine = editor.caretModel.logicalPosition.line
        val lineCount = document.lineCount

        val startLine = (caretLine - 3).coerceAtLeast(0)
        val endLine = (caretLine + 5).coerceAtMost(lineCount - 1)

        if (startLine > endLine) return ""

        val width = (endLine + 1).toString().length

        return buildString {
            for (line in startLine..endLine) {
                val lineStart = document.getLineStartOffset(line)
                val lineEnd = document.getLineEndOffset(line)
                val lineText = document.getText(TextRange(lineStart, lineEnd))

                val prefix = if (line == caretLine) ">" else " "
                val formattedLineNumber = (line + 1).toString().padStart(width)

                append("$prefix$formattedLineNumber $lineText")
                if (line < endLine) {
                    append("\n")
                }
            }
        }
    }

    private fun getSurroundingLinesForSelection(editor: Editor, selStartLine: Int, selEndLine: Int): String {
        val document = editor.document
        val lineCount = document.lineCount

        val startLine = selStartLine.coerceAtLeast(0)
        val endLine = selEndLine.coerceAtMost(lineCount - 1)

        if (startLine > endLine) return ""

        val width = (endLine + 1).toString().length

        return buildString {
            for (line in startLine..endLine) {
                val lineStart = document.getLineStartOffset(line)
                val lineEnd = document.getLineEndOffset(line)
                val lineText = document.getText(TextRange(lineStart, lineEnd))

                val prefix = when (line) {
                    selStartLine -> ">"
                    selEndLine -> ">"
                    else -> " "
                }

                val formattedLineNumber = (line + 1).toString().padStart(width)

                append("$prefix$formattedLineNumber $lineText")
                if (line < endLine) {
                    append("\n")
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val enabled = project != null && (editor != null || virtualFile != null)
        e.presentation.isEnabled = enabled
        e.presentation.isVisible = enabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}
