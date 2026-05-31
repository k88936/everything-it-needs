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

        val lineNumber = editor?.let { it.caretModel.currentCaret.logicalPosition.line + 1 }

        val refId = if (editor != null) {
            resolveReferenceId(editor, virtualFile, project)
        } else {
            null
        }

        val codeContext = editor?.let { getSurroundingLines(it) }

        val content = buildCopyContent(filePath, lineNumber, refId, codeContext)

        CopyPasteManager.getInstance().setContents(StringSelection(content))
    }

    private fun buildCopyContent(
        filePath: @NonNls String,
        lineNumber: Int?,
        refId: String?,
        codeContext: String?
    ): @NonNls String {
        return buildString {
            append(filePath)
            if (lineNumber != null) {
                append(":$lineNumber")
            }
            if (refId != null) {
                append(" ($refId)")
            }
            if (codeContext != null) {
                append("\n\n").append(codeContext)
            }
        }
    }

    private fun resolveReferenceId(
        editor: Editor,
        virtualFile: VirtualFile,
        project: com.intellij.openapi.project.Project
    ): String? {
        val psiFile: PsiFile = PsiUtilBase.getPsiFileInEditor(editor, project) ?: return null
        val offset = editor.caretModel.offset

        // Try the most specific element at offset first
        val elementAtOffset = psiFile.findElementAt(offset)
        val namedElement = findBestNamedElement(elementAtOffset) ?: return null

        return buildQualifiedName(namedElement)
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


        return buildString {
            for (line in startLine..endLine) {
                val lineStart = document.getLineStartOffset(line)
                val lineEnd = document.getLineEndOffset(line)
                val lineText = document.getText(TextRange(lineStart, lineEnd))

                val prefix = if (line == caretLine) ">" else " "
                val formattedLineNumber = (line + 1).toString().padStart(4)

                append("$prefix$formattedLineNumber $lineText \n")
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
