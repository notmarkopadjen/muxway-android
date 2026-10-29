package app.muxway.android.protocol

/**
 * Colours for code: a small scanner per language that tells comments, strings, numbers,
 * keywords and names apart, nothing more. A language it does not know is shown plain. The
 * fence's language in Markdown and a file's extension pick the scanner. The browser's `code.ts`.
 */
object Code {
    enum class Kind { COMMENT, STRING, NUMBER, KEYWORD, NAME, PUNCT, TEXT }

    data class Run(val kind: Kind, val text: String)

    private class Lang(
        keywords: String,
        val line: List<String>,
        val block: List<Pair<String, String>>,
        val quotes: String,
        // Words before a colon are keys (json, yaml); html: tags and attributes.
        val keys: Boolean = false,
        val tags: Boolean = false,
    ) {
        val keywords: Set<String> = keywords.split(' ').filter { it.isNotEmpty() }.toSet()
    }

    private val C_LINE = listOf("//")
    private val C_BLOCK = listOf("/*" to "*/")
    private const val SQL = "select from where and or not in is null as join left right inner outer on group by order having limit offset insert into values update set delete create table drop alter index primary key unique default distinct union all case when then else end exists between like asc desc"

    private val LANGS: Map<String, Lang> = mapOf(
        "js" to Lang("async await break case catch class const continue debugger default delete do else enum export extends false finally for function if import in instanceof let new null of return static super switch this throw true try typeof undefined var void while with yield", C_LINE, C_BLOCK, "\"'`"),
        "ts" to Lang("abstract any as async await boolean break case catch class const continue declare default delete do else enum export extends false finally for from function if implements import in infer instanceof interface is keyof let namespace never new null number object of override private protected public readonly return satisfies static string super switch symbol this throw true try type typeof undefined unique unknown var void while with yield", C_LINE, C_BLOCK, "\"'`"),
        "cs" to Lang("abstract as async await base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach get goto if implicit in init int interface internal is lock long namespace new null object operator out override params private protected public readonly record ref return sbyte sealed set short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using var virtual void volatile where while yield", C_LINE, C_BLOCK, "\"'"),
        "go" to Lang("break case chan const continue default defer else fallthrough false for func go goto if import interface iota map nil package range return select struct switch true type var", C_LINE, C_BLOCK, "\"`'"),
        "py" to Lang("False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield self", listOf("#"), listOf("\"\"\"" to "\"\"\"", "'''" to "'''"), "\"'"),
        "sh" to Lang("if then else elif fi for while until do done case esac in function select return exit export local readonly set unset shift break continue true false", listOf("#"), emptyList(), "\"'"),
        "json" to Lang("true false null", emptyList(), emptyList(), "\"", keys = true),
        "yaml" to Lang("true false null yes no on off", listOf("#"), emptyList(), "\"'", keys = true),
        "toml" to Lang("true false", listOf("#"), emptyList(), "\"'", keys = true),
        "sql" to Lang(SQL.uppercase() + " " + SQL, listOf("--"), C_BLOCK, "'\""),
        "css" to Lang("important", emptyList(), C_BLOCK, "\"'", keys = true),
        "rs" to Lang("as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while", C_LINE, C_BLOCK, "\""),
        "java" to Lang("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new null package private protected public return short static strictfp super switch synchronized this throw throws transient true false try void volatile while var record", C_LINE, C_BLOCK, "\"'"),
        "kt" to Lang("abstract as break by catch class companion const constructor continue data do else enum false final finally for fun if import in infix init inline interface internal is lateinit null object open operator out override package private protected public return sealed super suspend this throw true try typealias val var vararg when while", C_LINE, C_BLOCK, "\"'"),
        "c" to Lang("auto break case char const continue default do double else enum extern float for goto if inline int long register return short signed sizeof static struct switch typedef union unsigned void volatile while bool true false nullptr class namespace new delete public private protected template this throw try catch using virtual", C_LINE, C_BLOCK, "\"'"),
        "html" to Lang("", emptyList(), listOf("<!--" to "-->"), "\"'", tags = true),
    )

    private val ALIASES = mapOf(
        "javascript" to "js", "jsx" to "js", "mjs" to "js", "cjs" to "js", "typescript" to "ts", "tsx" to "ts", "csharp" to "cs",
        "golang" to "go", "python" to "py", "bash" to "sh", "zsh" to "sh", "shell" to "sh", "fish" to "sh", "yml" to "yaml",
        "rust" to "rs", "kotlin" to "kt", "kts" to "kt", "cpp" to "c", "cc" to "c", "h" to "c", "hpp" to "c", "htm" to "html",
        "xml" to "html", "svg" to "html", "scss" to "css", "less" to "css", "ini" to "toml",
    )

    /** The scanner for a language name or a file name, if there is one. */
    fun languageOf(name: String): String? {
        val ext = if ('.' in name) name.substringAfterLast('.') else name
        val key = ext.lowercase()
        val lang = ALIASES[key] ?: key
        return if (lang in LANGS) lang else null
    }

    private fun identStart(c: Char) = c.isLetter() && c.code < 128 || c == '_' || c == '$' || c == '@'
    private fun ident(c: Char) = c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '$'

    fun tokenize(lang: String, text: String): List<Run> {
        val l = LANGS[lang] ?: return listOf(Run(Kind.TEXT, text))
        val out = ArrayList<Run>()
        fun push(kind: Kind, s: String) {
            if (s.isEmpty()) return
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) out[out.size - 1] = Run(kind, last.text + s) else out.add(Run(kind, s))
        }
        var i = 0
        val n = text.length
        fun startsWith(s: String) = text.startsWith(s, i)
        // Inside an html tag: names are attributes, the first one the tag.
        var inTag = false
        while (i < n) {
            val c = text[i]
            val b = l.block.firstOrNull { startsWith(it.first) }
            if (b != null) {
                val end = text.indexOf(b.second, i + b.first.length)
                val stop = if (end < 0) n else end + b.second.length
                push(Kind.COMMENT, text.substring(i, stop))
                i = stop
                continue
            }
            val lc = l.line.firstOrNull { startsWith(it) }
            if (lc != null && !(lc == "#" && lang == "sh" && i > 0 && text[i - 1] == '$')) {
                val end = text.indexOf('\n', i)
                val stop = if (end < 0) n else end
                push(Kind.COMMENT, text.substring(i, stop))
                i = stop
                continue
            }
            if (c in l.quotes && !(c == '\'' && (lang == "rs" || lang == "go") && text.getOrNull(i + 2) != '\'')) {
                var j = i + 1
                while (j < n && text[j] != c) {
                    if (text[j] == '\\') j++
                    if (j < n && text[j] == '\n' && c != '`') break
                    j++
                }
                val stop = minOf(n, j + 1)
                val rest = text.substring(stop)
                push(if (l.keys && KEY_AFTER.containsMatchIn(rest)) Kind.NAME else Kind.STRING, text.substring(i, stop))
                i = stop
                continue
            }
            if (l.tags) {
                if (c == '<') {
                    val close = startsWith("</") || startsWith("<?") || startsWith("<!")
                    push(Kind.PUNCT, if (close) text.substring(i, i + 2) else "<")
                    i += if (close) 2 else 1
                    var j = i
                    while (j < n && ident(text[j])) j++
                    push(Kind.KEYWORD, text.substring(i, j))
                    i = j
                    inTag = true
                    continue
                }
                if (inTag && (c == '>' || startsWith("/>") || startsWith("?>"))) {
                    val len = if (c == '>') 1 else 2
                    push(Kind.PUNCT, text.substring(i, i + len))
                    i += len
                    inTag = false
                    continue
                }
                if (!inTag) {
                    val next = text.indexOf('<', i)
                    val stop = if (next < 0) n else next
                    push(Kind.TEXT, text.substring(i, stop))
                    i = stop
                    continue
                }
            }
            if (c.isDigit() || (c == '.' && text.getOrNull(i + 1)?.isDigit() == true)) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() && text[j].code < 128 || text[j] == '_' || text[j] == '.')) j++
                push(Kind.NUMBER, text.substring(i, j))
                i = j
                continue
            }
            if (lang == "sh" && c == '$' && text.getOrNull(i + 1) == '{') {
                val end = text.indexOf('}', i)
                val stop = if (end < 0) n else end + 1
                push(Kind.NAME, text.substring(i, stop))
                i = stop
                continue
            }
            if (identStart(c)) {
                var j = i + 1
                while (j < n && (ident(text[j]) || (lang == "css" && text[j] == '-'))) j++
                val word = text.substring(i, j)
                val rest = text.substring(j)
                when {
                    l.tags && inTag -> push(Kind.NAME, word)
                    word in l.keywords -> push(Kind.KEYWORD, word)
                    l.keys && KEY_ASSIGN.containsMatchIn(rest) && !EQUALS.containsMatchIn(rest) -> push(Kind.NAME, word)
                    lang == "sh" && c == '$' -> push(Kind.NAME, word)
                    else -> push(Kind.TEXT, word)
                }
                i = j
                continue
            }
            push(if (c.isWhitespace()) Kind.TEXT else Kind.PUNCT, c.toString())
            i++
        }
        return out
    }

    private val KEY_AFTER = Regex("^\\s*:")
    private val KEY_ASSIGN = Regex("^\\s*[:=]")
    private val EQUALS = Regex("^\\s*==")

    /** The runs of one text cut into lines, each line its own runs. */
    fun lines(runs: List<Run>): List<List<Run>> {
        val out = ArrayList<MutableList<Run>>()
        out.add(ArrayList())
        for (r in runs) {
            val parts = r.text.split('\n')
            parts.forEachIndexed { k, p ->
                if (k > 0) out.add(ArrayList())
                if (p.isNotEmpty()) out.last().add(Run(r.kind, p))
            }
        }
        return out
    }
}
