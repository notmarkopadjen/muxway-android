package app.muxway.android.protocol

import app.muxway.android.protocol.Code.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeTest {
    private fun kinds(lang: String, text: String) = Code.tokenize(lang, text).map { it.kind to it.text }

    @Test
    fun aLanguageComesFromItsNameOrAFilesExtension() {
        assertEquals("kt", Code.languageOf("Main.kt"))
        assertEquals("kt", Code.languageOf("kotlin"))
        assertEquals("sh", Code.languageOf("bash"))
        assertEquals("html", Code.languageOf("page.svg"))
        assertNull(Code.languageOf("notes.txt"))
        assertNull(Code.languageOf(""))
        assertEquals("make", Code.languageOf("src/Makefile"))
        assertEquals("dockerfile", Code.languageOf("Dockerfile"))
        assertEquals("html", Code.languageOf("Views/Index.cshtml"))
        assertEquals("hcl", Code.languageOf("main.tf"))
        assertEquals("ps1", Code.languageOf("run.ps1"))
        assertEquals("json", Code.languageOf("a.ipynb"))
    }

    @Test
    fun theLanguagesAProjectAddsAreKnown() {
        assertEquals(
            listOf(Kind.KEYWORD to "def", Kind.TEXT to " go ", Kind.COMMENT to "# run", Kind.TEXT to "\n  puts ", Kind.STRING to "'hi'", Kind.TEXT to "\n", Kind.KEYWORD to "end"),
            kinds("rb", "def go # run\n  puts 'hi'\nend"),
        )
        assertEquals(
            listOf(Kind.NAME to "all", Kind.PUNCT to ":", Kind.TEXT to " ", Kind.NAME to "\$(SRC)", Kind.TEXT to "\n\t@echo ", Kind.NAME to "\${X}", Kind.TEXT to " ", Kind.COMMENT to "# done"),
            kinds("make", "all: \$(SRC)\n\t@echo \${X} # done"),
        )
        assertEquals(listOf(Kind.KEYWORD to "FROM", Kind.TEXT to " alpine\n", Kind.KEYWORD to "RUN", Kind.TEXT to " echo ", Kind.NAME to "\$HOME"), kinds("dockerfile", "FROM alpine\nRUN echo \$HOME"))
        assertEquals(listOf(Kind.COMMENT to "; c", Kind.TEXT to "\n", Kind.NAME to "key", Kind.TEXT to " ", Kind.PUNCT to "=", Kind.TEXT to " ", Kind.NUMBER to "1"), kinds("ini", "; c\nkey = 1"))
        assertEquals(listOf(Kind.COMMENT to "' c", Kind.TEXT to "\n", Kind.KEYWORD to "Dim", Kind.TEXT to " s ", Kind.PUNCT to "=", Kind.TEXT to " ", Kind.STRING to "\"x\""), kinds("vb", "' c\nDim s = \"x\""))
    }

    @Test
    fun commentsStringsNumbersKeywordsAndNamesAreToldApart() {
        assertEquals(
            listOf(Kind.KEYWORD to "val", Kind.TEXT to " x ", Kind.PUNCT to "=", Kind.TEXT to " ", Kind.NUMBER to "42", Kind.TEXT to " ", Kind.COMMENT to "// hi"),
            kinds("kt", "val x = 42 // hi"),
        )
        assertEquals(listOf(Kind.KEYWORD to "def", Kind.TEXT to " f", Kind.PUNCT to "():", Kind.TEXT to " ", Kind.STRING to "'a'"), kinds("py", "def f(): 'a'"))
        assertEquals(listOf(Kind.COMMENT to "/* a\nb */", Kind.TEXT to " x"), kinds("go", "/* a\nb */ x"))
        // A quoted word before a colon is a key; a shell's $var is a name and its $# is not a comment.
        assertEquals(listOf(Kind.NAME to "\"a\"", Kind.PUNCT to ":", Kind.TEXT to " ", Kind.STRING to "\"b\""), kinds("json", "\"a\": \"b\""))
        assertEquals(listOf(Kind.KEYWORD to "if", Kind.TEXT to " ", Kind.NAME to "\$x", Kind.TEXT to " ", Kind.NAME to "\${y}"), kinds("sh", "if \$x \${y}"))
        assertEquals(listOf(Kind.PUNCT to "<", Kind.KEYWORD to "a", Kind.TEXT to " ", Kind.NAME to "href", Kind.PUNCT to "=", Kind.STRING to "\"x\"", Kind.PUNCT to ">", Kind.TEXT to "hi", Kind.PUNCT to "</", Kind.KEYWORD to "a", Kind.PUNCT to ">"), kinds("html", "<a href=\"x\">hi</a>"))
        assertEquals(listOf(Kind.TEXT to "anything goes"), kinds("brainfuck", "anything goes"))
    }

    @Test
    fun theRunsCutIntoLines() {
        val lines = Code.lines(Code.tokenize("kt", "val a = 1\n// two\n"))
        assertEquals(3, lines.size)
        assertEquals(listOf(Kind.KEYWORD, Kind.TEXT, Kind.PUNCT, Kind.TEXT, Kind.NUMBER), lines[0].map { it.kind })
        assertEquals(listOf(Kind.COMMENT to "// two"), lines[1].map { it.kind to it.text })
        assertEquals(emptyList<Code.Run>(), lines[2])
    }
}
