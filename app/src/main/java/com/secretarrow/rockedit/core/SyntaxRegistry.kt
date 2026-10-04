package com.secretarrow.rockedit.core

/**
 * A syntax language definition consumed by [SyntaxTokenizer].
 *
 * Rock Edit ships deliberately small, self-contained language data:
 * keyword sets plus comment/string delimiters are enough for a useful and
 * fast editor-grade highlight without bundling external grammar files.
 */
class SyntaxLanguage(
    val id: String,
    val displayName: String,
    val keywords: Set<String>,
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val stringDelims: List<Char> = listOf('"', '\''),
    val caseInsensitive: Boolean = false
)

/**
 * Registry of languages recognized from file names/extensions.
 * Unknown extensions return null and the file stays plain text.
 */
object SyntaxRegistry {

    private val kotlin = SyntaxLanguage(
        id = "kotlin",
        displayName = "Kotlin",
        keywords = setOf(
            "package", "import", "class", "interface", "fun", "object", "val", "var",
            "if", "else", "when", "while", "do", "for", "return", "break", "continue",
            "in", "is", "as", "true", "false", "null", "this", "super", "private",
            "public", "protected", "internal", "final", "open", "abstract", "sealed",
            "data", "enum", "companion", "init", "override", "suspend", "lateinit",
            "by", "try", "catch", "finally", "throw", "typealias", "operator",
            "inline", "reified", "const", "expect", "actual", "where", "out", "vararg"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val java = SyntaxLanguage(
        id = "java",
        displayName = "Java",
        keywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "var", "record",
            "sealed", "permits", "yield", "true", "false", "null"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val c = SyntaxLanguage(
        id = "c",
        displayName = "C",
        keywords = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do",
            "double", "else", "enum", "extern", "float", "for", "goto", "if", "inline",
            "int", "long", "register", "restrict", "return", "short", "signed",
            "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned",
            "void", "volatile", "while", "true", "false", "NULL"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val cpp = SyntaxLanguage(
        id = "cpp",
        displayName = "C++",
        keywords = c.keywords + setOf(
            "class", "namespace", "using", "template", "typename", "public",
            "private", "protected", "virtual", "friend", "operator", "new", "delete",
            "this", "throw", "catch", "bool", "nullptr", "constexpr", "noexcept",
            "override", "final", "decltype", "concept", "requires"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val csharp = SyntaxLanguage(
        id = "csharp",
        displayName = "C#",
        keywords = setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch",
            "char", "checked", "class", "const", "continue", "decimal", "default",
            "delegate", "do", "double", "else", "enum", "event", "explicit", "extern",
            "false", "finally", "fixed", "float", "for", "foreach", "goto", "if",
            "implicit", "in", "int", "interface", "internal", "is", "lock", "long",
            "namespace", "new", "null", "object", "operator", "out", "override",
            "params", "private", "protected", "public", "readonly", "ref", "return",
            "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string",
            "struct", "switch", "this", "throw", "true", "try", "typeof", "uint",
            "ulong", "unchecked", "unsafe", "ushort", "using", "var", "virtual",
            "void", "volatile", "while", "async", "await", "record", "init"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val go = SyntaxLanguage(
        id = "go",
        displayName = "Go",
        keywords = setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else",
            "fallthrough", "for", "func", "go", "goto", "if", "import", "interface",
            "map", "package", "range", "return", "select", "struct", "switch", "type",
            "var", "nil", "true", "false", "string", "int", "int8", "int16", "int32",
            "int64", "uint", "uint8", "uint16", "uint32", "uint64", "float32",
            "float64", "bool", "byte", "rune", "error", "make", "new", "len", "cap",
            "append", "copy", "delete", "panic", "recover"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val rust = SyntaxLanguage(
        id = "rust",
        displayName = "Rust",
        keywords = setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn",
            "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let",
            "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self",
            "Self", "static", "struct", "super", "trait", "true", "type", "unsafe",
            "use", "where", "while", "i8", "i16", "i32", "i64", "u8", "u16", "u32",
            "u64", "usize", "isize", "f32", "f64", "str", "bool", "char", "String",
            "Vec", "Option", "Result", "Some", "None", "Ok", "Err"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val javascript = SyntaxLanguage(
        id = "javascript",
        displayName = "JavaScript",
        keywords = setOf(
            "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "false",
            "finally", "for", "function", "if", "import", "in", "instanceof", "new",
            "null", "return", "super", "switch", "this", "throw", "true", "try",
            "typeof", "var", "void", "while", "with", "yield", "async", "await",
            "let", "static", "of", "get", "set"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val typescript = SyntaxLanguage(
        id = "typescript",
        displayName = "TypeScript",
        keywords = javascript.keywords + setOf(
            "interface", "implements", "type", "enum", "namespace", "declare",
            "readonly", "public", "private", "protected", "abstract", "any",
            "string", "number", "boolean", "unknown", "never", "object", "symbol",
            "bigint", "keyof", "infer", "satisfies"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val python = SyntaxLanguage(
        id = "python",
        displayName = "Python",
        keywords = setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "False", "finally", "for",
            "from", "global", "if", "import", "in", "is", "lambda", "None",
            "nonlocal", "not", "or", "pass", "raise", "return", "True", "try",
            "while", "with", "yield", "match", "case", "self"
        ),
        lineComments = listOf("#"),
        // Triple-quoted strings are colored as comments (docstring style).
        blockComments = listOf("\"\"\"" to "\"\"\"")
    )

    private val ruby = SyntaxLanguage(
        id = "ruby",
        displayName = "Ruby",
        keywords = setOf(
            "alias", "and", "begin", "break", "case", "class", "def", "defined",
            "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in",
            "module", "next", "nil", "not", "or", "redo", "rescue", "retry",
            "return", "self", "super", "then", "true", "undef", "unless", "until",
            "when", "while", "yield", "attr_accessor", "attr_reader", "attr_writer",
            "require", "require_relative", "puts", "proc", "lambda"
        ),
        lineComments = listOf("#")
    )

    private val php = SyntaxLanguage(
        id = "php",
        displayName = "PHP",
        keywords = setOf(
            "abstract", "and", "array", "as", "break", "callable", "case", "catch",
            "class", "clone", "const", "continue", "declare", "default", "do",
            "echo", "else", "elseif", "empty", "endforeach", "endif", "endswitch",
            "endwhile", "enum", "extends", "final", "finally", "fn", "for",
            "foreach", "function", "global", "goto", "if", "implements", "include",
            "include_once", "instanceof", "interface", "isset", "list", "match",
            "namespace", "new", "or", "print", "private", "protected", "public",
            "readonly", "require", "require_once", "return", "static", "switch",
            "throw", "trait", "try", "unset", "use", "var", "while", "xor", "yield",
            "true", "false", "null"
        ),
        lineComments = listOf("//", "#"),
        blockComments = listOf("/*" to "*/")
    )

    private val swift = SyntaxLanguage(
        id = "swift",
        displayName = "Swift",
        keywords = setOf(
            "associatedtype", "class", "deinit", "enum", "extension", "fileprivate",
            "func", "import", "init", "inout", "internal", "let", "open", "operator",
            "private", "protocol", "public", "rethrows", "static", "struct",
            "subscript", "typealias", "var", "break", "case", "continue", "default",
            "defer", "do", "else", "fallthrough", "for", "guard", "if", "in",
            "repeat", "return", "switch", "where", "while", "as", "Any", "catch",
            "false", "is", "nil", "super", "self", "throw", "throws", "true", "try",
            "some", "async", "await"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val shell = SyntaxLanguage(
        id = "shell",
        displayName = "Shell",
        keywords = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do",
            "done", "case", "esac", "function", "in", "select", "time", "coproc",
            "break", "continue", "return", "exit", "export", "local", "readonly",
            "declare", "typeset", "unset", "set", "shift", "eval", "exec", "trap",
            "source", "alias", "echo", "cd", "pwd", "printf", "read", "test",
            "true", "false"
        ),
        lineComments = listOf("#")
    )

    private val sql = SyntaxLanguage(
        id = "sql",
        displayName = "SQL",
        keywords = setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set",
            "delete", "create", "table", "drop", "alter", "add", "column", "primary",
            "key", "foreign", "references", "constraint", "unique", "default",
            "check", "not", "null", "and", "or", "as", "on", "join", "inner", "left",
            "right", "full", "outer", "cross", "group", "by", "order", "having",
            "limit", "offset", "union", "all", "distinct", "exists", "between",
            "like", "in", "is", "case", "when", "then", "else", "end", "view",
            "index", "trigger", "begin", "commit", "rollback", "transaction",
            "asc", "desc", "count", "sum", "avg", "min", "max"
        ),
        lineComments = listOf("--"),
        blockComments = listOf("/*" to "*/"),
        stringDelims = listOf('\''),
        caseInsensitive = true
    )

    private val json = SyntaxLanguage(
        id = "json",
        displayName = "JSON",
        keywords = setOf("true", "false", "null"),
        stringDelims = listOf('"')
    )

    private val yaml = SyntaxLanguage(
        id = "yaml",
        displayName = "YAML",
        keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
        lineComments = listOf("#")
    )

    private val markupKeywords = setOf(
        "html", "head", "body", "div", "span", "p", "a", "img", "ul", "ol", "li",
        "table", "tr", "td", "th", "form", "input", "button", "script", "style",
        "link", "meta", "title", "h1", "h2", "h3", "h4", "h5", "h6", "br", "hr",
        "section", "header", "footer", "nav", "article", "aside", "iframe", "svg",
        "path", "class", "id", "href", "src", "alt", "width", "height", "xmlns",
        "version", "encoding", "standalone", "xml", "doctype", "public", "system"
    )

    private val xml = SyntaxLanguage(
        id = "xml",
        displayName = "XML",
        keywords = markupKeywords,
        blockComments = listOf("<!--" to "-->"),
        caseInsensitive = true
    )

    private val html = SyntaxLanguage(
        id = "html",
        displayName = "HTML",
        keywords = markupKeywords,
        blockComments = listOf("<!--" to "-->"),
        caseInsensitive = true
    )

    private val css = SyntaxLanguage(
        id = "css",
        displayName = "CSS",
        keywords = setOf(
            "color", "background", "background-color", "margin", "margin-top",
            "margin-right", "margin-bottom", "margin-left", "padding", "border",
            "border-radius", "display", "flex", "grid", "width", "height",
            "min-width", "max-width", "font-size", "font-family", "font-weight",
            "line-height", "text-align", "text-decoration", "position", "absolute",
            "relative", "fixed", "sticky", "top", "left", "right", "bottom",
            "z-index", "opacity", "overflow", "cursor", "transition", "transform",
            "box-shadow", "flex-direction", "justify-content", "align-items",
            "gap", "content", "media", "import", "keyframes", "important", "none",
            "block", "inline", "auto", "solid", "inherit", "initial", "unset"
        ),
        blockComments = listOf("/*" to "*/"),
        stringDelims = listOf('"', '\'')
    )

    private val languages: List<SyntaxLanguage> = listOf(
        kotlin, java, c, cpp, csharp, go, rust, javascript, typescript, python,
        ruby, php, swift, shell, sql, json, yaml, xml, html, css
    )

    private val byExtension: Map<String, SyntaxLanguage> = buildMap {
        fun link(language: SyntaxLanguage, vararg exts: String) {
            for (ext in exts) put(ext, language)
        }
        link(kotlin, "kt", "kts")
        link(java, "java")
        link(c, "c", "h")
        link(cpp, "cpp", "cc", "cxx", "hpp", "hh")
        link(csharp, "cs")
        link(go, "go")
        link(rust, "rs")
        link(javascript, "js", "mjs", "cjs", "jsx")
        link(typescript, "ts", "tsx")
        link(python, "py", "pyw")
        link(ruby, "rb")
        link(php, "php")
        link(swift, "swift")
        link(shell, "sh", "bash", "zsh")
        link(sql, "sql")
        link(json, "json")
        link(yaml, "yml", "yaml")
        link(xml, "xml", "svg", "plist", "xsl")
        link(html, "html", "htm")
        link(css, "css", "scss", "less")
    }

    /** Number of built-in languages (useful for tests and About dialogs). */
    val languageCount: Int get() = languages.size

    /**
     * Resolves the language for a file name by its extension (case-insensitive).
     * Returns null for unknown extensions, extension-less names and dotfiles.
     */
    fun languageForFileName(name: String?): SyntaxLanguage? {
        if (name.isNullOrEmpty()) return null
        val ext = FileNames.split(name).second.lowercase()
        if (ext.isEmpty()) return null
        return byExtension[ext]
    }

    fun languageById(id: String): SyntaxLanguage? = languages.firstOrNull { it.id == id }
}
