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
        // Words before a colon are keys (json, yaml); html: tags and attributes; sh, make, php: $name is a name.
        val keys: Boolean = false,
        val tags: Boolean = false,
        val dollar: Boolean = false,
    ) {
        val keywords: Set<String> = keywords.split(' ').filter { it.isNotEmpty() }.toSet()
    }

    private fun both(s: String) = s + " " + s.uppercase()
    // Lower, upper and capitalised, for languages that do not care.
    private fun cased(s: String) = both(s) + " " + s.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    // The browser's table (web/src/term/code.ts), kept the same.
    private val LANGS: Map<String, Lang> = mapOf(
        "js" to Lang("async await break case catch class const continue debugger default delete do else enum export extends false finally for function if import in instanceof let new null of return static super switch this throw true try typeof undefined var void while with yield", listOf("//"), listOf("/*" to "*/"), "\"'`"),
        "ts" to Lang("abstract any as async await boolean break case catch class const continue declare default delete do else enum export extends false finally for from function if implements import in infer instanceof interface is keyof let namespace never new null number object of override private protected public readonly return satisfies static string super switch symbol this throw true try type typeof undefined unique unknown var void while with yield", listOf("//"), listOf("/*" to "*/"), "\"'`"),
        "cs" to Lang("abstract as async await base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach get goto if implicit in init int interface internal is lock long namespace new null object operator out override params private protected public readonly record ref return sbyte sealed set short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using var virtual void volatile where while yield", listOf("//"), listOf("/*" to "*/"), "\"'"),
        "go" to Lang("break case chan const continue default defer else fallthrough false for func go goto if import interface iota map nil package range return select struct switch true type var", listOf("//"), listOf("/*" to "*/"), "\"`'"),
        "py" to Lang("False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield self", listOf("#"), listOf("\"\"\"" to "\"\"\"", "'''" to "'''"), "\"'"),
        "sh" to Lang("if then else elif fi for while until do done case esac in function select return exit export local readonly set unset shift break continue true false", listOf("#"), emptyList(), "\"'", dollar = true),
        "json" to Lang("true false null", emptyList(), emptyList(), "\"", keys = true),
        "yaml" to Lang("true false null yes no on off", listOf("#"), emptyList(), "\"'", keys = true),
        "toml" to Lang("true false", listOf("#"), emptyList(), "\"'", keys = true),
        "ini" to Lang("true false yes no on off", listOf("#", ";"), emptyList(), "\"'", keys = true),
        "sql" to Lang(both("select from where and or not in is null as join left right inner outer on group by order having limit offset insert into values update set delete create table drop alter index primary key unique default distinct union all case when then else end exists between like asc desc"), listOf("--"), listOf("/*" to "*/"), "'\""),
        "css" to Lang("important", emptyList(), listOf("/*" to "*/"), "\"'", keys = true),
        "rs" to Lang("as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while", listOf("//"), listOf("/*" to "*/"), "\""),
        "java" to Lang("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new null package private protected public return short static strictfp super switch synchronized this throw throws transient true false try void volatile while var record", listOf("//"), listOf("/*" to "*/"), "\"'"),
        "kt" to Lang("abstract as break by catch class companion const constructor continue data do else enum false final finally for fun if import in infix init inline interface internal is lateinit null object open operator out override package private protected public return sealed super suspend this throw true try typealias val var vararg when while", listOf("//"), listOf("/*" to "*/"), "\"'"),
        "c" to Lang("auto break case char const continue default do double else enum extern float for goto if inline int long register return short signed sizeof static struct switch typedef union unsigned void volatile while bool true false nullptr class namespace new delete public private protected template this throw try catch using virtual", listOf("//"), listOf("/*" to "*/"), "\"'"),
        "html" to Lang("", emptyList(), listOf("<!--" to "-->"), "\"'", tags = true),
        "rb" to Lang("alias and begin break case class def defined? do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield require attr_accessor attr_reader private public protected", listOf("#"), listOf("=begin" to "=end"), "\"'"),
        "php" to Lang("abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield true false null", listOf("//", "#"), listOf("/*" to "*/"), "\"'", dollar = true),
        "lua" to Lang("and break do else elseif end false for function goto if in local nil not or repeat return then true until while", listOf("--"), listOf("--[[" to "]]"), "\"'"),
        "pl" to Lang("my our local sub if elsif else unless while until for foreach do last next redo return package use require no print printf die eq ne lt gt le ge and or not shift push pop splice", listOf("#"), emptyList(), "\"'", dollar = true),
        "r" to Lang("if else repeat while function for in next break TRUE FALSE NULL Inf NaN NA library require return", listOf("#"), emptyList(), "\"'"),
        "jl" to Lang("abstract baremodule begin break catch const continue do else elseif end export false finally for function global if import in let local macro module mutable primitive quote return struct true try type using while", listOf("#"), listOf("#=" to "=#"), "\""),
        "swift" to Lang("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private precedencegroup protocol public rethrows static struct subscript typealias var break case catch continue default defer do else fallthrough for guard if in repeat return throw switch where while as any await false is nil self Self super throws true try some", listOf("//"), listOf("/*" to "*/"), "\""),
        "dart" to Lang("abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory false final finally for Function get hide if implements import in interface is late library mixin new null on operator part required rethrow return set show static super switch sync this throw true try typedef var void while with yield", listOf("//"), listOf("/*" to "*/"), "\"'"),
        "scala" to Lang("abstract case catch class def do else extends false final finally for forSome if implicit import lazy match new null object override package private protected return sealed super this throw trait true try type val var while with yield given using enum then", listOf("//"), listOf("/*" to "*/"), "\""),
        "groovy" to Lang("abstract as assert boolean break byte case catch char class const continue def default do double else enum extends false final finally float for goto if implements import in instanceof int interface long native new null package private protected public return short static super switch synchronized this threadsafe throw throws transient true try void volatile while apply plugins dependencies implementation testImplementation android", listOf("//"), listOf("/*" to "*/"), "\"'", dollar = true),
        "hs" to Lang("case class data default deriving do else foreign if import in infix infixl infixr instance let module newtype of then type where forall qualified as hiding", listOf("--"), listOf("{-" to "-}"), "\""),
        "ex" to Lang("after and case catch cond def defimpl defmacro defmodule defp defprotocol defstruct do else end false fn for if import in nil not or quote raise receive require rescue true try unless unquote use when with", listOf("#"), emptyList(), "\"'"),
        "erl" to Lang("after and andalso band begin bnot bor bsl bsr bxor case catch cond div end fun if let not of or orelse receive rem try when xor module export import record", listOf("%"), emptyList(), "\"'"),
        "ml" to Lang("and as assert begin class constraint do done downto else end exception external false for fun function functor if in include inherit initializer lazy let match method module mutable new object of open or private rec sig struct then to true try type val virtual when while with abstract base default delegate elif extern finally global inline interface internal lazy member namespace null override return static use yield", listOf("//"), listOf("(*" to "*)"), "\""),
        "vb" to Lang(cased("addhandler addressof alias and andalso as boolean byref byte byval call case catch cbool cbyte cchar cdate cdbl cdec char cint class clng cobj const continue csbyte cshort csng cstr ctype cuint culng cushort date decimal declare default delegate dim directcast do double each else elseif end enum erase error event exit false finally for friend function get gettype getxmlnamespace global gosub goto handles if implements imports in inherits integer interface is isnot let lib like long loop me mod module mustinherit mustoverride mybase myclass namespace narrowing new next not nothing notinheritable notoverridable object of on operator option optional or orelse overloads overridable overrides paramarray partial private property protected public raiseevent readonly redim rem removehandler resume return sbyte select set shadows shared short single static step stop string structure sub synclock then throw to true try trycast typeof uinteger ulong ushort using variant wend when while widening with withevents writeonly xor"), listOf("'"), emptyList(), "\""),
        "ps1" to Lang(both("begin break catch class continue data define do dynamicparam else elseif end enum exit filter finally for foreach from function hidden if in param process return static switch throw trap try until using var while true false null -eq -ne -gt -ge -lt -le -like -notlike -match -notmatch -and -or -not"), listOf("#"), listOf("<#" to "#>"), "\"'", dollar = true),
        "bat" to Lang(both("echo set if else exist not errorlevel goto call exit for in do pause start setlocal endlocal shift cd copy del dir move md rd ren type off on"), listOf("REM ", "rem ", "::"), emptyList(), "\""),
        "zig" to Lang("align allowzero and anyframe anytype asm async await break catch comptime const continue defer else enum errdefer error export extern false fn for if inline noalias null or orelse packed pub resume return linksection struct suspend switch test threadlocal true try undefined union unreachable usingnamespace var volatile while", listOf("//"), emptyList(), "\""),
        "nim" to Lang("addr and as asm bind block break case cast concept const continue converter defer discard distinct div do elif else end enum except export finally for from func if import in include interface is isnot iterator let macro method mixin mod nil not notin object of or out proc ptr raise ref return shl shr static template try tuple type using var when while xor yield true false", listOf("#"), listOf("#[" to "]#"), "\""),
        "d" to Lang("abstract alias align asm assert auto body bool break byte case cast catch char class const continue dchar debug default delegate delete deprecated do double else enum export extern false final finally float for foreach function goto if immutable import in inout int interface invariant is lazy long mixin module new nothrow null out override package pragma private protected public pure real ref return scope shared short static struct super switch synchronized template this throw true try typeof ubyte uint ulong union unittest ushort version void while with", listOf("//"), listOf("/*" to "*/"), "\"'"),
        "lisp" to Lang("defn defn- def defmacro let let* fn if when cond do loop recur ns require import lambda define defun defvar defparameter setq progn case else true false nil", listOf(";"), listOf("#|" to "|#"), "\""),
        "tex" to Lang("begin end section subsection subsubsection chapter documentclass usepackage item", listOf("%"), emptyList(), ""),
        "fortran" to Lang(cased("program end subroutine function module use implicit none integer real double precision character logical complex dimension allocatable allocate deallocate do if then else elseif endif enddo call return contains type intent in out inout parameter print write read open close stop continue select case while"), listOf("!"), emptyList(), "\"'"),
        "asm" to Lang("section global extern db dw dd dq mov push pop call ret jmp je jne cmp add sub lea xor and or", listOf(";", "#"), emptyList(), "\"'"),
        "make" to Lang("ifeq ifneq ifdef ifndef else endif include define endef export unexport override vpath", listOf("#"), emptyList(), "\"'", keys = true, dollar = true),
        "dockerfile" to Lang(both("from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as"), listOf("#"), emptyList(), "\"'", dollar = true),
        "cmake" to Lang("cmake_minimum_required project add_executable add_library target_link_libraries target_include_directories include set option if else elseif endif foreach endforeach function endfunction macro endmacro message find_package install add_subdirectory ON OFF TRUE FALSE", listOf("#"), emptyList(), "\"", dollar = true),
        "proto" to Lang("syntax package import option message enum service rpc returns repeated optional required oneof map reserved stream extend extensions true false double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes", listOf("//"), listOf("/*" to "*/"), "\"'"),
        "graphql" to Lang("query mutation subscription fragment on type interface union enum input scalar schema extend implements directive repeatable true false null", listOf("#"), emptyList(), "\""),
        "hcl" to Lang("resource variable output module provider data locals terraform true false null for in if else each count depends_on", listOf("#", "//"), listOf("/*" to "*/"), "\"", keys = true),
        "nix" to Lang("let in rec with inherit if then else assert import true false null or", listOf("#"), listOf("/*" to "*/"), "\"", keys = true),
        "http" to Lang("GET POST PUT PATCH DELETE HEAD OPTIONS TRACE CONNECT HTTP", listOf("#", "//"), emptyList(), "\"", keys = true),
    )

    private val ALIASES = mapOf(
        "javascript" to "js", "jsx" to "js", "mjs" to "js", "cjs" to "js", "coffee" to "js", "sol" to "js", "typescript" to "ts", "tsx" to "ts",
        "csharp" to "cs", "csx" to "cs", "golang" to "go", "python" to "py", "pyi" to "py", "pyx" to "py", "pxd" to "py", "pxi" to "py",
        "bash" to "sh", "zsh" to "sh", "shell" to "sh", "fish" to "sh", "awk" to "sh", "yml" to "yaml", "ics" to "yaml", "rust" to "rs",
        "kotlin" to "kt", "kts" to "kt", "cpp" to "c", "cc" to "c", "cxx" to "c", "h" to "c", "hpp" to "c", "hh" to "c",
        "hxx" to "c", "inl" to "c", "cu" to "c", "cuh" to "c", "m" to "c", "mm" to "c", "glsl" to "c", "hlsl" to "c",
        "vert" to "c", "frag" to "c", "htm" to "html", "xhtml" to "html", "xml" to "html", "svg" to "html", "xsd" to "html", "xsl" to "html",
        "xslt" to "html", "dtd" to "html", "plist" to "html", "cshtml" to "html", "razor" to "html", "vue" to "html", "svelte" to "html", "astro" to "html",
        "xaml" to "html", "axaml" to "html", "csproj" to "html", "vbproj" to "html", "fsproj" to "html", "targets" to "html", "props" to "html", "nuspec" to "html",
        "pubxml" to "html", "resx" to "html", "config" to "html", "tpl" to "html", "tmpl" to "html", "mako" to "html", "jinja" to "html", "jinja2" to "html",
        "j2" to "html", "hbs" to "html", "mustache" to "html", "ejs" to "html", "njk" to "html", "liquid" to "html", "scss" to "css", "sass" to "css",
        "less" to "css", "styl" to "css", "cfg" to "ini", "conf" to "ini", "properties" to "ini", "service" to "ini", "timer" to "ini", "socket" to "ini",
        "mount" to "ini", "sln" to "ini", "lock" to "toml", "jsonl" to "json", "ndjson" to "json", "json5" to "json", "jsonc" to "json", "geojson" to "json",
        "ipynb" to "json", "map" to "json", "webmanifest" to "json", "ruby" to "rb", "erb" to "rb", "rake" to "rb", "gemspec" to "rb", "gemfile" to "rb",
        "rakefile" to "rb", "brewfile" to "rb", "vagrantfile" to "rb", "perl" to "pl", "pm" to "pl", "rmd" to "r", "julia" to "jl", "gradle" to "groovy",
        "jenkinsfile" to "groovy", "sbt" to "scala", "haskell" to "hs", "lhs" to "hs", "elixir" to "ex", "exs" to "ex", "erlang" to "erl", "hrl" to "erl",
        "ocaml" to "ml", "mli" to "ml", "fs" to "ml", "fsx" to "ml", "fsi" to "ml", "fsharp" to "ml", "powershell" to "ps1", "psm1" to "ps1",
        "psd1" to "ps1", "cmd" to "bat", "batch" to "bat", "cr" to "rb", "v" to "go", "elm" to "hs", "clj" to "lisp", "cljs" to "lisp",
        "cljc" to "lisp", "edn" to "lisp", "el" to "lisp", "scm" to "lisp", "rkt" to "lisp", "clojure" to "lisp", "wat" to "lisp", "latex" to "tex",
        "bib" to "tex", "f" to "fortran", "f90" to "fortran", "f95" to "fortran", "f03" to "fortran", "for" to "fortran", "s" to "asm", "mk" to "make",
        "makefile" to "make", "gnumakefile" to "make", "docker" to "dockerfile", "containerfile" to "dockerfile", "tf" to "hcl", "tfvars" to "hcl", "terraform" to "hcl", "gql" to "graphql",
        "rest" to "http", "protobuf" to "proto",
    )

    /** The scanner for a language name or a file name, if there is one. */
    fun languageOf(name: String): String? {
        val base = name.substringAfterLast('/')
        val ext = if ('.' in base) base.substringAfterLast('.') else base
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
            if (lc != null && !(lc == "#" && l.dollar && i > 0 && text[i - 1] == '$')) {
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
            if (l.dollar && c == '$' && (text.getOrNull(i + 1) == '{' || text.getOrNull(i + 1) == '(')) {
                val end = text.indexOf(if (text[i + 1] == '{') '}' else ')', i)
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
                    l.dollar && c == '$' -> push(Kind.NAME, word)
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
