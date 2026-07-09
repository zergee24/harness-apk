package com.harnessapk.ui.markdown

data class CodeToken(
    val literal: String,
    val kind: CodeTokenKind,
)

enum class CodeTokenKind {
    PLAIN,
    KEYWORD,
    STRING,
    NUMBER,
    COMMENT,
    PUNCTUATION,
}

fun tokenizeCode(
    code: String,
    language: String?,
): List<CodeToken> {
    val normalizedLanguage = language.orEmpty().trim().lowercase().substringBefore(' ')
    if (normalizedLanguage !in highlightedLanguages) return listOf(CodeToken(code, CodeTokenKind.PLAIN))
    val keywords = keywordsByLanguage[normalizedLanguage].orEmpty()
    val tokens = mutableListOf<CodeToken>()
    var index = 0
    while (index < code.length) {
        val comment = commentAt(code, index, normalizedLanguage)
        if (comment != null) {
            tokens += CodeToken(comment, CodeTokenKind.COMMENT)
            index += comment.length
            continue
        }
        val string = stringAt(code, index)
        if (string != null) {
            tokens += CodeToken(string, CodeTokenKind.STRING)
            index += string.length
            continue
        }
        val number = numberAt(code, index)
        if (number != null) {
            tokens += CodeToken(number, CodeTokenKind.NUMBER)
            index += number.length
            continue
        }
        val word = wordAt(code, index)
        if (word != null) {
            tokens += CodeToken(
                literal = word,
                kind = if (word in keywords) CodeTokenKind.KEYWORD else CodeTokenKind.PLAIN,
            )
            index += word.length
            continue
        }
        val char = code[index]
        tokens += CodeToken(
            literal = char.toString(),
            kind = if (char in punctuationChars) CodeTokenKind.PUNCTUATION else CodeTokenKind.PLAIN,
        )
        index += 1
    }
    return tokens.mergeAdjacentPlainTokens()
}

private fun commentAt(code: String, index: Int, language: String): String? {
    val tail = code.substring(index)
    return when {
        language in shellLanguages && tail.startsWith("#") -> tail.takeWhile { it != '\n' }
        tail.startsWith("//") -> tail.takeWhile { it != '\n' }
        tail.startsWith("/*") -> {
            val end = code.indexOf("*/", startIndex = index + 2)
            code.substring(index, if (end >= 0) end + 2 else code.length)
        }
        else -> null
    }
}

private fun stringAt(code: String, index: Int): String? {
    val quote = code[index].takeIf { it == '"' || it == '\'' } ?: return null
    var cursor = index + 1
    var escaped = false
    while (cursor < code.length) {
        val char = code[cursor]
        if (char == quote && !escaped) return code.substring(index, cursor + 1)
        escaped = char == '\\' && !escaped
        if (char != '\\') escaped = false
        cursor += 1
    }
    return code.substring(index)
}

private fun numberAt(code: String, index: Int): String? {
    if (!code[index].isDigit()) return null
    var cursor = index + 1
    while (cursor < code.length && (code[cursor].isDigit() || code[cursor] == '_')) {
        cursor += 1
    }
    return code.substring(index, cursor)
}

private fun wordAt(code: String, index: Int): String? {
    if (!code[index].isLetter() && code[index] != '_') return null
    var cursor = index + 1
    while (cursor < code.length && (code[cursor].isLetterOrDigit() || code[cursor] == '_')) {
        cursor += 1
    }
    return code.substring(index, cursor)
}

private fun List<CodeToken>.mergeAdjacentPlainTokens(): List<CodeToken> {
    val merged = mutableListOf<CodeToken>()
    forEach { token ->
        val previous = merged.lastOrNull()
        if (previous != null && previous.kind == token.kind && token.kind == CodeTokenKind.PLAIN) {
            merged[merged.lastIndex] = previous.copy(literal = previous.literal + token.literal)
        } else {
            merged += token
        }
    }
    return merged
}

private val highlightedLanguages = setOf(
    "json",
    "kotlin",
    "java",
    "python",
    "bash",
    "shell",
    "sh",
    "zsh",
    "javascript",
    "typescript",
    "xml",
    "html",
    "css",
    "sql",
    "markdown",
)

private val shellLanguages = setOf("bash", "shell", "sh", "zsh")

private val keywordsByLanguage = mapOf(
    "kotlin" to setOf("val", "var", "fun", "class", "object", "data", "when", "if", "else", "return", "null", "true", "false"),
    "java" to setOf("class", "public", "private", "protected", "static", "final", "void", "new", "return", "if", "else", "true", "false", "null"),
    "python" to setOf("def", "class", "import", "from", "if", "elif", "else", "return", "for", "while", "in", "True", "False", "None"),
    "javascript" to setOf("const", "let", "var", "function", "class", "return", "if", "else", "true", "false", "null", "undefined", "import", "export"),
    "typescript" to setOf("const", "let", "var", "function", "class", "interface", "type", "return", "if", "else", "true", "false", "null", "undefined", "import", "export"),
    "sql" to setOf("select", "from", "where", "join", "left", "right", "insert", "update", "delete", "group", "order", "by", "limit"),
)

private val punctuationChars = setOf('{', '}', '[', ']', '(', ')', '.', ',', ':', ';', '=', '+', '-', '*', '/', '|')
