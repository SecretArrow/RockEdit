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

    // ---- Extended language set (v0.5.0) --------------------------------------

    private val lua = SyntaxLanguage(
        id = "lua",
        displayName = "Lua",
        keywords = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
            "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return",
            "then", "true", "until", "while", "self"
        ),
        lineComments = listOf("--"),
        blockComments = listOf("--[[" to "]]")
    )

    private val perl = SyntaxLanguage(
        id = "perl",
        displayName = "Perl",
        keywords = setOf(
            "my", "our", "local", "sub", "if", "elsif", "else", "unless", "while",
            "until", "for", "foreach", "do", "last", "next", "redo", "return",
            "use", "no", "require", "package", "new", "defined", "undef", "exists",
            "delete", "keys", "values", "each", "push", "pop", "shift", "unshift",
            "splice", "sort", "map", "grep", "join", "split", "print", "printf",
            "say", "open", "close", "die", "warn", "eval", "wantarray", "ref",
            "bless", "scalar", "abs", "chomp", "chop", "lc", "uc", "length",
            "substr", "index", "sprintf", "true", "false", "and", "or", "not", "eq", "ne"
        ),
        lineComments = listOf("#"),
        stringDelims = listOf('"', '\'')
    )

    private val r = SyntaxLanguage(
        id = "r",
        displayName = "R",
        keywords = setOf(
            "if", "else", "repeat", "while", "function", "for", "in", "next", "break",
            "TRUE", "FALSE", "NULL", "Inf", "NaN", "NA", "NA_integer_", "NA_real_",
            "NA_character_", "NA_complex_", "library", "require", "return", "source"
        ),
        lineComments = listOf("#")
    )

    private val objc = SyntaxLanguage(
        id = "objc",
        displayName = "Objective-C",
        keywords = c.keywords + setOf(
            "id", "self", "nil", "YES", "NO", "BOOL", "IBOutlet", "IBAction",
            "interface", "implementation", "property", "end", "protocol",
            "class", "import", "include", "@synthesize", "@dynamic", "nonatomic",
            "strong", "weak", "readonly", "readwrite", "alloc", "init", "new"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val dart = SyntaxLanguage(
        id = "dart",
        displayName = "Dart",
        keywords = setOf(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch",
            "class", "const", "continue", "default", "deferred", "do", "dynamic",
            "else", "enum", "export", "extends", "extension", "external", "factory",
            "false", "final", "finally", "for", "get", "if", "implements", "import",
            "in", "interface", "is", "late", "library", "mixin", "new", "null",
            "on", "operator", "part", "required", "rethrow", "return", "sealed",
            "set", "show", "static", "super", "switch", "sync", "this", "throw",
            "true", "try", "typedef", "var", "void", "while", "with", "yield"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/", "///" to "\n")
    )

    private val scala = SyntaxLanguage(
        id = "scala",
        displayName = "Scala",
        keywords = setOf(
            "abstract", "case", "catch", "class", "def", "do", "else", "extends",
            "false", "final", "finally", "for", "forSome", "if", "implicit", "import",
            "lazy", "match", "new", "null", "object", "override", "package", "private",
            "protected", "return", "sealed", "super", "this", "throw", "trait", "true",
            "try", "type", "val", "var", "while", "with", "yield", "given", "using",
            "enum", "export", "extension", "end", "then", "inline", "transparent"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val groovy = SyntaxLanguage(
        id = "groovy",
        displayName = "Groovy",
        keywords = setOf(
            "as", "assert", "break", "case", "catch", "class", "const", "continue",
            "def", "default", "do", "else", "enum", "extends", "false", "finally",
            "for", "goto", "if", "implements", "import", "in", "instanceof",
            "interface", "new", "null", "package", "return", "super", "switch",
            "this", "throw", "throws", "trait", "true", "try", "while", "closure",
            "it", "println", "task", "plugin", "apply", "repositories", "dependencies"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val haskell = SyntaxLanguage(
        id = "haskell",
        displayName = "Haskell",
        keywords = setOf(
            "case", "class", "data", "default", "deriving", "do", "else", "foreign",
            "if", "import", "in", "infix", "infixl", "infixr", "instance", "let",
            "module", "newtype", "of", "then", "type", "where", "mdo", "rec"
        ),
        lineComments = listOf("--"),
        blockComments = listOf("{-" to "-}"),
        stringDelims = listOf('"')
    )

    private val erlang = SyntaxLanguage(
        id = "erlang",
        displayName = "Erlang",
        keywords = setOf(
            "after", "and", "andalso", "band", "begin", "bnot", "bor", "bsl", "bsr",
            "bxor", "case", "catch", "cond", "div", "end", "fun", "if", "let", "not",
            "of", "or", "orelse", "receive", "rem", "try", "when", "xor", "query"
        ),
        lineComments = listOf("%"),
        stringDelims = listOf('"')
    )

    private val elixir = SyntaxLanguage(
        id = "elixir",
        displayName = "Elixir",
        keywords = setOf(
            "def", "defmodule", "defp", "defmacro", "defstruct", "defprotocol",
            "defimpl", "defexception", "defguard", "defoverridable", "do", "else",
            "end", "false", "fn", "if", "import", "require", "use", "alias", "case",
            "cond", "with", "for", "unless", "receive", "try", "rescue", "catch",
            "after", "raise", "throw", "true", "nil", "and", "or", "not", "in", "when"
        ),
        lineComments = listOf("#")
    )

    private val clojure = SyntaxLanguage(
        id = "clojure",
        displayName = "Clojure",
        keywords = setOf(
            "def", "defn", "defn-", "defmacro", "defonce", "defmulti", "defmethod",
            "defprotocol", "defrecord", "deftype", "let", "letfn", "fn", "loop",
            "recur", "if", "when", "when-not", "when-let", "if-let", "if-not", "cond",
            "condp", "case", "do", "doseq", "dotimes", "for", "while", "try", "catch",
            "finally", "throw", "ns", "require", "import", "use", "refer", "true",
            "false", "nil", "lambda", "quote", "var", "new", "set!", "assoc", "merge"
        ),
        lineComments = listOf(";"),
        stringDelims = listOf('"')
    )

    private val fsharp = SyntaxLanguage(
        id = "fsharp",
        displayName = "F#",
        keywords = setOf(
            "abstract", "and", "as", "assert", "base", "begin", "class", "default",
            "delegate", "do", "done", "downcast", "downto", "elif", "else", "end",
            "exception", "extern", "false", "finally", "fixed", "for", "fun",
            "function", "global", "if", "in", "inherit", "inline", "interface",
            "internal", "lazy", "let", "let!", "match", "match!", "member", "module",
            "mutable", "namespace", "new", "not", "null", "of", "open", "or",
            "override", "private", "public", "rec", "return", "return!", "select",
            "static", "struct", "then", "to", "true", "try", "type", "upcast", "use",
            "use!", "val", "void", "when", "while", "with", "yield", "yield!"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("(*" to "*)")
    )

    private val visualbasic = SyntaxLanguage(
        id = "vb",
        displayName = "Visual Basic",
        keywords = setOf(
            "addhandler", "andalso", "boolean", "byref", "byte", "byval", "call",
            "case", "catch", "cbool", "cbyte", "cchar", "cdate", "cdbl", "cdec",
            "char", "cint", "class", "clng", "cobj", "const", "continue", "csbyte",
            "cshort", "csng", "cstr", "ctype", "cuint", "culng", "cushort", "date",
            "decimal", "declare", "default", "delegate", "dim", "do", "double",
            "each", "else", "elseif", "end", "enum", "erase", "error", "event",
            "exit", "false", "finally", "for", "friend", "function", "get", "gettype",
            "global", "goto", "handles", "if", "implements", "imports", "in",
            "inherits", "integer", "interface", "is", "isnot", "long", "loop", "me",
            "mod", "module", "mustinherit", "mustoverride", "mybase", "myclass",
            "namespace", "new", "next", "not", "nothing", "notinheritable",
            "notoverridable", "object", "of", "on", "operator", "option", "optional",
            "or", "orelse", "overloads", "overridable", "overrides", "paramarray",
            "partial", "private", "property", "protected", "public", "raiseevent",
            "readonly", "redim", "rem", "removehandler", "resume", "return", "select",
            "set", "shadows", "shared", "short", "single", "static", "step", "stop",
            "string", "structure", "sub", "synclock", "then", "throw", "to", "true",
            "try", "trycast", "typeof", "until", "using", "when", "while", "widening",
            "with", "withevents", "writeonly", "xor"
        ),
        lineComments = listOf("'"),
        stringDelims = listOf('"'),
        caseInsensitive = true
    )

    private val assembly = SyntaxLanguage(
        id = "assembly",
        displayName = "Assembly",
        keywords = setOf(
            "mov", "push", "pop", "add", "sub", "mul", "imul", "div", "idiv", "inc",
            "dec", "and", "or", "xor", "not", "neg", "shl", "shr", "sar", "cmp",
            "test", "jmp", "je", "jne", "jz", "jnz", "jg", "jl", "jge", "jle", "ja",
            "jb", "call", "ret", "nop", "lea", "int", "syscall", "enter", "leave",
            "loop", "movzx", "movsx", "sete", "setne", "cmove", "cmovne", "xchg",
            "section", "global", "extern", "db", "dw", "dd", "dq", "resb", "resw",
            "resd", "equ", "times", "byte", "word", "dword", "qword", "ptr", "rax",
            "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp", "eax", "ebx", "ecx",
            "edx", "esi", "edi", "ebp", "esp", "al", "bl", "cl", "dl"
        ),
        lineComments = listOf(";", "#"),
        caseInsensitive = true
    )

    private val toml = SyntaxLanguage(
        id = "toml",
        displayName = "TOML",
        keywords = setOf("true", "false"),
        lineComments = listOf("#"),
        stringDelims = listOf('"', '\'')
    )

    private val ini = SyntaxLanguage(
        id = "ini",
        displayName = "INI",
        keywords = setOf("true", "false", "yes", "no", "on", "off"),
        lineComments = listOf(";", "#"),
        stringDelims = listOf('"', '\'')
    )

    private val makefile = SyntaxLanguage(
        id = "makefile",
        displayName = "Makefile",
        keywords = setOf(
            "all", "clean", "install", "uninstall", "dist", "check", "include",
            "ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "define", "endef",
            "export", "unexport", "override", "PHONY", "DEFAULT", "PRECIOUS",
            "INTERMEDIATE", "SECONDARY", "DELETE_ON", "IGNORE", "SILENT", "EXPORT_ALL"
        ),
        lineComments = listOf("#")
    )

    private val cmake = SyntaxLanguage(
        id = "cmake",
        displayName = "CMake",
        keywords = setOf(
            "cmake_minimum_required", "project", "set", "if", "elseif", "else",
            "endif", "foreach", "endforeach", "function", "endfunction", "macro",
            "endmacro", "add_executable", "add_library", "add_subdirectory",
            "add_custom_target", "add_definitions", "add_compile_options",
            "target_link_libraries", "target_include_directories",
            "target_compile_definitions", "target_compile_options",
            "include_directories", "link_directories", "find_package", "find_library",
            "find_path", "find_program", "message", "option", "unset", "return",
            "include", "install", "set_target_properties", "get_target_property",
            "enable_testing", "add_test", "string", "list", "file", "math", "exec_program"
        ),
        lineComments = listOf("#"),
        blockComments = listOf("#[[" to "]]"),
        caseInsensitive = false
    )

    private val batch = SyntaxLanguage(
        id = "batch",
        displayName = "Batch",
        keywords = setOf(
            "echo", "set", "setlocal", "endlocal", "if", "else", "for", "in", "do",
            "goto", "call", "exit", "pause", "cd", "md", "rd", "del", "copy", "move",
            "ren", "type", "dir", "cls", "start", "title", "color", "choice", "shift",
            "errorlevel", "exist", "not", "equ", "neq", "lss", "leq", "gtr", "geq",
            "defined", "off", "on", "delayedexpansion", "enabledelayedexpansion",
            "rem", "pushd", "popd", "xcopy", "robocopy", "findstr", "set /a", "set /p"
        ),
        lineComments = listOf("::", "REM"),
        stringDelims = listOf('"'),
        caseInsensitive = true
    )

    private val powershell = SyntaxLanguage(
        id = "powershell",
        displayName = "PowerShell",
        keywords = setOf(
            "begin", "break", "catch", "class", "continue", "data", "define", "do",
            "dynamicparam", "else", "elseif", "end", "enum", "exit", "filter",
            "finally", "for", "foreach", "from", "function", "hidden", "if", "in",
            "param", "process", "return", "static", "switch", "throw", "trap", "try",
            "until", "using", "var", "while", "workflow", "get-childitem", "get-content",
            "set-content", "write-host", "write-output", "new-object", "select-object",
            "where-object", "foreach-object", "sort-object", "measure-object",
            "true", "false", "null"
        ),
        lineComments = listOf("#"),
        blockComments = listOf("<#" to "#>"),
        caseInsensitive = true
    )

    private val vue = SyntaxLanguage(
        id = "vue",
        displayName = "Vue",
        keywords = markupKeywords + setOf(
            "template", "script", "style", "computed", "methods", "data", "props",
            "watch", "mounted", "created", "setup", "ref", "reactive", "emit",
            "v-if", "v-else", "v-for", "v-model", "v-bind", "v-on", "v-show",
            "component", "components", "export", "default", "import", "from"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("<!--" to "-->", "/*" to "*/"),
        caseInsensitive = true
    )

    private val graphql = SyntaxLanguage(
        id = "graphql",
        displayName = "GraphQL",
        keywords = setOf(
            "query", "mutation", "subscription", "fragment", "on", "type", "input",
            "interface", "union", "enum", "schema", "extend", "implements", "scalar",
            "directive", "true", "false", "null"
        ),
        lineComments = listOf("#"),
        stringDelims = listOf('"')
    )

    private val julia = SyntaxLanguage(
        id = "julia",
        displayName = "Julia",
        keywords = setOf(
            "baremodule", "begin", "break", "catch", "const", "continue", "do",
            "else", "elseif", "end", "export", "false", "finally", "for", "function",
            "global", "if", "import", "importall", "let", "local", "macro", "module",
            "mutable", "primitive", "quote", "return", "struct", "true", "try",
            "type", "using", "while", "in", "isa", "where", "abstract", "typealias"
        ),
        lineComments = listOf("#"),
        blockComments = listOf("#=" to "=#")
    )

    private val nim = SyntaxLanguage(
        id = "nim",
        displayName = "Nim",
        keywords = setOf(
            "addr", "and", "as", "asm", "bind", "block", "break", "case", "cast",
            "concept", "const", "continue", "converter", "defer", "discard",
            "distinct", "div", "do", "elif", "else", "end", "enum", "except",
            "export", "finally", "for", "from", "func", "if", "import", "include",
            "interface", "is", "isnot", "iterator", "let", "macro", "method", "mixin",
            "mod", "nil", "not", "notin", "object", "of", "or", "out", "proc",
            "ptr", "raise", "ref", "return", "shl", "shr", "static", "template",
            "try", "tuple", "type", "using", "var", "when", "while", "with", "without", "xor", "yield"
        ),
        lineComments = listOf("#")
    )

    private val ocaml = SyntaxLanguage(
        id = "ocaml",
        displayName = "OCaml",
        keywords = setOf(
            "and", "as", "assert", "asr", "begin", "class", "constraint", "do",
            "done", "downto", "else", "end", "exception", "external", "false",
            "for", "fun", "function", "functor", "if", "in", "include", "inherit",
            "initializer", "land", "lazy", "let", "lor", "lsl", "lsr", "lxor",
            "match", "method", "mod", "module", "mutable", "new", "nonrec", "object",
            "of", "open", "or", "private", "rec", "sig", "struct", "then", "to",
            "true", "try", "type", "val", "virtual", "when", "while", "with"
        ),
        lineComments = emptyList(),
        blockComments = listOf("(*" to "*)")
    )

    private val latex = SyntaxLanguage(
        id = "latex",
        displayName = "LaTeX",
        keywords = setOf(
            "documentclass", "usepackage", "begin", "end", "section", "subsection",
            "subsubsection", "paragraph", "title", "author", "date", "maketitle",
            "tableofcontents", "item", "itemize", "enumerate", "description",
            "figure", "table", "tabular", "includegraphics", "label", "ref", "cite",
            "bibliography", "bibliographystyle", "footnote", "textbf", "textit",
            "emph", "underline", "frac", "sqrt", "sum", "int", "prod", "alpha",
            "beta", "gamma", "delta", "omega", "pi", "left", "right", "newcommand",
            "renewcommand", "input", "include", "document", "chapter", "part", "appendix"
        ),
        lineComments = listOf("%"),
        stringDelims = emptyList(),
        caseInsensitive = false
    )

    private val zig = SyntaxLanguage(
        id = "zig",
        displayName = "Zig",
        keywords = setOf(
            "align", "allowzero", "and", "anyframe", "anytype", "asm", "async",
            "await", "break", "callconv", "catch", "comptime", "const", "continue",
            "defer", "else", "enum", "errdefer", "error", "export", "extern",
            "false", "fn", "for", "if", "inline", "noalias", "nosuspend", "noinline",
            "null", "opaque", "or", "orelse", "packed", "pub", "resume", "return",
            "linksection", "struct", "suspend", "switch", "test", "threadlocal",
            "true", "try", "undefined", "union", "unreachable", "usingnamespace",
            "var", "volatile", "while"
        ),
        lineComments = listOf("//")
    )

    private val protobuf = SyntaxLanguage(
        id = "protobuf",
        displayName = "Protocol Buffers",
        keywords = setOf(
            "syntax", "package", "import", "option", "message", "oneof", "map",
            "field", "enum", "service", "rpc", "returns", "stream", "reserved",
            "extend", "extensions", "to", "max", "group", "optional", "required",
            "repeated", "double", "float", "int32", "int64", "uint32", "uint64",
            "sint32", "sint64", "fixed32", "fixed64", "sfixed32", "sfixed64",
            "bool", "string", "bytes"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    // ------------------------------------------------------------- smart contracts

    private val solidity = SyntaxLanguage(
        id = "solidity",
        displayName = "Solidity",
        keywords = setOf(
            "pragma", "solidity", "import", "from", "as", "contract", "interface",
            "library", "abstract", "is", "function", "constructor", "receive",
            "fallback", "modifier", "event", "error", "enum", "struct", "using",
            "for", "returns", "return", "public", "private", "internal", "external",
            "pure", "view", "payable", "virtual", "override", "constant",
            "immutable", "storage", "memory", "calldata", "mapping", "if", "else",
            "for", "while", "do", "break", "continue", "try", "catch", "require",
            "revert", "assert", "emit", "new", "delete", "this", "super", "selfdestruct",
            "assembly", "unchecked", "indexed", "anonymous", "global", "true", "false"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val vyper = SyntaxLanguage(
        id = "vyper",
        displayName = "Vyper",
        keywords = setOf(
            "pragma", "version", "implements", "interface", "event", "enum",
            "struct", "struct", "constant", "external", "internal", "pure",
            "view", "payable", "nonpayable", "public", "private", "immutable",
            "deploy", "def", "return", "if", "elif", "else", "for", "in", "while",
            "break", "continue", "pass", "raise", "assert", "log", "send", "raw_call",
            "create_forwarder_to", "self", "msg", "block", "chain", "tx", "empty",
            "MAX_UINT256", "zero_address", "True", "False", "None", "and", "or", "not"
        ),
        lineComments = listOf("#")
    )

    private val move = SyntaxLanguage(
        id = "move",
        displayName = "Move",
        keywords = setOf(
            "module", "script", "address", "public", "entry", "friend", "use",
            "fun", "native", "inline", "const", "struct", "ability", "has", "copy",
            "drop", "store", "key", "resource", "acquires", "let", "mut", "move",
            "copy", "borrow", "return", "if", "else", "while", "loop", "break",
            "continue", "abort", "assert", "vector", "signer", "true", "false"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val cairo = SyntaxLanguage(
        id = "cairo",
        displayName = "Cairo",
        keywords = setOf(
            "mod", "use", "fn", "let", "mut", "const", "struct", "enum", "trait",
            "impl", "type", "match", "if", "else", "loop", "while", "for", "in",
            "return", "break", "continue", "assert", "panic", "nop", "with", "implicits",
            "felt252", "u8", "u16", "u32", "u64", "u128", "u256", "bool", "ContractState",
            "storage", "event", "constructor", "external", "view", "ref", "self",
            "pub", "as", "true", "false"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val clarity = SyntaxLanguage(
        id = "clarity",
        displayName = "Clarity",
        keywords = setOf(
            "define-constant", "define-data-var", "define-map", "define-fungible-token",
            "define-non-fungible-token", "define-public", "define-read-only",
            "define-private", "define-trait", "impl-trait", "use-trait", "let",
            "begin", "if", "match", "unwrap", "unwrap-panic", "unwrap-err",
            "try", "asserts", "ok", "err", "some", "none", "is-eq", "is-none",
            "map-get", "map-set", "map-delete", "var-get", "var-set", "ft-transfer",
            "ft-mint", "ft-burn", "ft-get-balance", "nft-get-owner", "nft-mint",
            "nft-transfer", "contract-call", "principal-construct", "as-contract",
            "at-block", "get-block-info", "print", "tuple", "list", "true", "false", "none"
        ),
        lineComments = listOf(";;")
    )

    private val cadence = SyntaxLanguage(
        id = "cadence",
        displayName = "Cadence",
        keywords = setOf(
            "access", "contract", "contractinterface", "resource", "struct",
            "event", "enum", "transaction", "prepare", "execute", "pre", "post",
            "init", "destroy", "fun", "let", "var", "return", "if", "else", "while",
            "for", "in", "emit", "create", "destroy", "import", "pub", "priv",
            "account", "available", "all", "self", "auth", "mapping", "attachment",
            "entitlement", "view", "native", "static", "require", "as", "true", "false", "nil"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val motoko = SyntaxLanguage(
        id = "motoko",
        displayName = "Motoko",
        keywords = setOf(
            "actor", "module", "object", "class", "import", "shared", "query",
            "composite", "public", "private", "system", "func", "let", "var",
            "type", "async", "await", "async*", "return", "if", "else", "switch",
            "case", "while", "loop", "for", "in", "break", "continue", "label",
            "try", "catch", "throw", "assert", "debug_show", "Null", "Bool", "Nat",
            "Int", "Text", "Blob", "Principal", "Error", "stable", "flexible",
            "and", "or", "not", "true", "false", "null"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val aiken = SyntaxLanguage(
        id = "aiken",
        displayName = "Aiken",
        keywords = setOf(
            "use", "pub", "fn", "const", "type", "let", "assert", "expect",
            "when", "is", "if", "else", "todo", "error", "trace", "validator",
            "opaque", "as", "match", "else", "and", "or", "not", "True", "False",
            "Void", "Option", "Some", "None", "G1Element", "G2Element"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val leo = SyntaxLanguage(
        id = "leo",
        displayName = "Leo",
        keywords = setOf(
            "program", "import", "mapping", "record", "struct", "transition",
            "inline", "function", "async", "function", "const", "let", "for",
            "if", "else", "else if", "return", "assert", "assert_eq", "input",
            "main", "public", "private", "constant", "u8", "u16", "u32", "u64",
            "u128", "i8", "i16", "i32", "i64", "i128", "field", "group", "scalar",
            "signature", "address", "bool", "final", "true", "false"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val fe = SyntaxLanguage(
        id = "fe",
        displayName = "Fe",
        keywords = setOf(
            "contract", "struct", "enum", "type", "event", "emit", "fn", "pub",
            "const", "let", "mut", "if", "else", "match", "for", "in", "while",
            "return", "revert", "assert", "break", "continue", "emboss", "self",
            "msg", "chain", "block", "tx", "create", "call", "u8", "u16", "u32",
            "u64", "u128", "u256", "i8", "i256", "bool", "address", "true", "false"
        ),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/")
    )

    private val michelson = SyntaxLanguage(
        id = "michelson",
        displayName = "Michelson",
        keywords = setOf(
            "parameter", "storage", "code", "PUSH", "DROP", "DUP", "SWAP",
            "DIG", "DUG", "PAIR", "UNPAIR", "CAR", "CDR", "IF", "IF_LEFT",
            "IF_CONS", "IF_NONE", "IF_SOME", "NIL", "CONS", "SOME", "NONE",
            "LAMBDA", "EXEC", "APPLY", "ADD", "SUB", "MUL", "EDIV", "COMPARE",
            "EQ", "NEQ", "LT", "GT", "LE", "GE", "OR", "AND", "XOR", "NOT",
            "CONTRACT", "TRANSFER_TOKENS", "SET_DELEGATE", "BALANCE", "AMOUNT",
            "SENDER", "SOURCE", "SELF", "IMPLICIT_ACCOUNT", "MAP", "EMPTY_MAP",
            "BIG_MAP", "GET", "UPDATE", "ITER", "LOOP", "LOOP_LEFT", "FAILWITH"
        ),
        lineComments = listOf("//", "#")
    )

    private val languages: List<SyntaxLanguage> = listOf(
        kotlin, java, c, cpp, csharp, go, rust, javascript, typescript, python,
        ruby, php, swift, shell, sql, json, yaml, xml, html, css,
        lua, perl, r, objc, dart, scala, groovy, haskell, erlang, elixir,
        clojure, fsharp, visualbasic, assembly, toml, ini, makefile, cmake,
        batch, powershell, vue, graphql, julia, nim, ocaml, latex, zig, protobuf,
        solidity, vyper, move, cairo, clarity, cadence, motoko, aiken, leo,
        fe, michelson
    )

    /** Files without (or with misleading) extensions that map by exact name. */
    private val byName: Map<String, SyntaxLanguage> = mapOf(
        "makefile" to makefile,
        "gnumakefile" to makefile,
        "dockerfile" to shell,
        "rakefile" to ruby,
        "gemfile" to ruby,
        "vagrantfile" to ruby,
        "cmakelists.txt" to cmake
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
        // Extended set (v0.5.0)
        link(lua, "lua")
        link(perl, "pl", "pm")
        link(r, "r")
        link(objc, "m", "mm")
        link(dart, "dart")
        link(scala, "scala", "sc")
        link(groovy, "groovy", "gradle", "jenkinsfile")
        link(haskell, "hs")
        link(erlang, "erl", "hrl")
        link(elixir, "ex", "exs")
        link(clojure, "clj", "cljs", "cljc", "edn")
        link(fsharp, "fs", "fsi", "fsx")
        link(visualbasic, "vb", "vbs", "bas")
        link(assembly, "asm", "s")
        link(toml, "toml")
        link(ini, "ini", "cfg", "conf", "properties")
        link(makefile, "mk", "mak")
        link(cmake, "cmake")
        link(batch, "bat", "cmd")
        link(powershell, "ps1", "psm1", "psd1")
        link(vue, "vue")
        link(graphql, "graphql", "gql")
        link(julia, "jl")
        link(nim, "nim")
        link(ocaml, "ml", "mli")
        link(latex, "tex", "latex")
        link(zig, "zig")
        link(protobuf, "proto")
        // Smart contracts (v0.10.0)
        link(solidity, "sol")
        link(vyper, "vy")
        link(move, "move")
        link(cairo, "cairo")
        link(clarity, "clar")
        link(cadence, "cdc")
        link(motoko, "mo")
        link(aiken, "aiken")
        link(leo, "leo")
        link(fe, "fe")
        link(michelson, "tz")
    }

    /** Number of built-in languages (useful for tests and About dialogs). */
    val languageCount: Int get() = languages.size

    /**
     * Resolves the language for a file name: exact names first (Makefile,
     * Dockerfile, ...), then the extension (case-insensitive).
     * Returns null for unknown extensions, extension-less names and dotfiles.
     */
    fun languageForFileName(name: String?): SyntaxLanguage? {
        if (name.isNullOrEmpty()) return null
        byName[name.lowercase()]?.let { return it }
        val ext = FileNames.split(name).second.lowercase()
        if (ext.isEmpty()) return null
        return byExtension[ext]
    }

    fun languageById(id: String): SyntaxLanguage? = languages.firstOrNull { it.id == id }
}
