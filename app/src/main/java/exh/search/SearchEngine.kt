package exh.search

import exh.metadata.metadata.base.RaisedTag
import exh.metadata.sql.tables.SearchMetadataTable
import exh.metadata.sql.tables.SearchTagTable
import exh.metadata.sql.tables.SearchTitleTable

class SearchEngine {
    private val queryCache = mutableMapOf<String, List<QueryComponent>>()

    private fun textToSubQueries(
        namespace: String?,
        component: Text?
    ): Pair<String, List<String>>? {
        val maybeLenientComponent =
            component?.let {
                if (!it.exact) {
                    it.asLenientTagQueries()
                } else {
                    listOf(it.asQuery())
                }
            }
        val componentTagQuery =
            maybeLenientComponent?.let {
                val params = mutableListOf<String>()
                it.joinToString(separator = " OR ", prefix = "(", postfix = ")") { q ->
                    params += q
                    "${SearchTagTable.TABLE}.${SearchTagTable.COL_NAME} LIKE ?"
                } to params
            }
        return when {
            namespace != null -> {
                var query =
                    """
                    (SELECT ${SearchTagTable.COL_MANGA_ID} AS $COL_MANGA_ID FROM ${SearchTagTable.TABLE}
                        WHERE ${SearchTagTable.COL_NAMESPACE} IS NOT NULL
                        AND ${SearchTagTable.COL_NAMESPACE} LIKE ?
                    """.trimIndent()
                val params = mutableListOf(escapeLike(namespace))
                if (componentTagQuery != null) {
                    query += "\n    AND ${componentTagQuery.first}"
                    params += componentTagQuery.second
                }

                "$query)" to params
            }
            component != null -> {
                // Match title + tags
                val tagQuery =
                    """
                    SELECT ${SearchTagTable.COL_MANGA_ID} AS $COL_MANGA_ID FROM ${SearchTagTable.TABLE}
                        WHERE ${componentTagQuery!!.first}
                    """.trimIndent() to componentTagQuery.second

                val titleQuery =
                    """
                    SELECT ${SearchTitleTable.COL_MANGA_ID} AS $COL_MANGA_ID FROM ${SearchTitleTable.TABLE}
                        WHERE ${SearchTitleTable.COL_TITLE} LIKE ?
                    """.trimIndent() to listOf(component.asLenientTitleQuery())

                "(${tagQuery.first} UNION ${titleQuery.first})".trimIndent() to
                    (tagQuery.second + titleQuery.second)
            }
            else -> null
        }
    }

    fun queryToSql(q: List<QueryComponent>): Pair<String, List<String>> {
        val wheres = mutableListOf<String>()
        val whereParams = mutableListOf<String>()

        val include = mutableListOf<Pair<String, List<String>>>()
        val exclude = mutableListOf<Pair<String, List<String>>>()

        for (component in q) {
            val query =
                if (component is Text) {
                    textToSubQueries(null, component)
                } else if (component is Namespace) {
                    if (component.namespace == "uploader") {
                        wheres += "meta.${SearchMetadataTable.COL_UPLOADER} LIKE ?"
                        whereParams += component.tag!!.rawTextEscapedForLike()
                        null
                    } else {
                        if (component.tag!!.components.size > 0) {
                            // Match namespace + tags
                            textToSubQueries(component.namespace, component.tag)
                        } else {
                            // Perform namespace search
                            textToSubQueries(component.namespace, null)
                        }
                    }
                } else {
                    error("Unknown query component!")
                }

            if (query != null) {
                (if (component.excluded) exclude else include) += query
            }
        }

        val completeParams = mutableListOf<String>()
        var baseQuery =
            """
            SELECT ${SearchMetadataTable.COL_MANGA_ID}
            FROM ${SearchMetadataTable.TABLE} meta
            """.trimIndent()

        include.forEachIndexed { index, pair ->
            baseQuery += "\n" + (
                """
                INNER JOIN ${pair.first} i$index
                ON i$index.$COL_MANGA_ID = meta.${SearchMetadataTable.COL_MANGA_ID}
                """.trimIndent()
                )
            completeParams += pair.second
        }

        exclude.forEach {
            wheres +=
                """
                (meta.${SearchMetadataTable.COL_MANGA_ID} NOT IN ${it.first})
                """.trimIndent()
            whereParams += it.second
        }
        if (wheres.isNotEmpty()) {
            completeParams += whereParams
            baseQuery += "\nWHERE\n"
            baseQuery += wheres.joinToString("\nAND\n")
        }
        baseQuery += "\nORDER BY ${SearchMetadataTable.COL_MANGA_ID}"

        return baseQuery to completeParams
    }

    fun parseQuery(
        query: String,
        enableWildcard: Boolean = true
    ) = queryCache.getOrPut(query) {
        val res = mutableListOf<QueryComponent>()

        var inQuotes = false
        val queuedRawText = StringBuilder()
        val queuedText = mutableListOf<TextComponent>()
        var namespace: Namespace? = null

        var nextIsExcluded = false
        var nextIsExact = false

        fun flushText() {
            if (queuedRawText.isNotEmpty()) {
                queuedText += StringTextComponent(queuedRawText.toString())
                queuedRawText.setLength(0)
            }
        }

        fun flushToText() =
            Text().apply {
                components += queuedText
                queuedText.clear()
            }

        fun flushAll() {
            flushText()
            if (queuedText.isNotEmpty() || namespace != null) {
                val component =
                    namespace?.apply {
                        tag = flushToText()
                        namespace = null
                    } ?: flushToText()
                component.excluded = nextIsExcluded
                component.exact = nextIsExact
                res += component
            }
        }

        query.lowercase().forEach { char ->
            if (char == '"') {
                inQuotes = !inQuotes
            } else if (enableWildcard && (char == '?' || char == '_')) {
                flushText()
                queuedText.add(SingleWildcard(char.toString()))
            } else if (enableWildcard && (char == '*' || char == '%')) {
                flushText()
                queuedText.add(MultiWildcard(char.toString()))
            } else if (char == '-' && !inQuotes && (queuedRawText.isBlank() || queuedRawText.last() == ' ')) {
                nextIsExcluded = true
            } else if (char == '$') {
                nextIsExact = true
            } else if (char == ':') {
                flushText()
                var flushed = flushToText().rawTextOnly()
                // Map tag aliases
                flushed =
                    when (flushed) {
                        "a" -> "artist"
                        "c", "char" -> "character"
                        "f" -> "female"
                        "g", "creator", "circle" -> "group"
                        "l", "lang" -> "language"
                        "m" -> "male"
                        "p", "series" -> "parody"
                        "r" -> "reclass"
                        else -> flushed
                    }
                namespace = Namespace(flushed, null)
            } else if (char == ' ' && !inQuotes) {
                flushAll()
            } else {
                queuedRawText.append(char)
            }
        }
        flushAll()

        res
    }

    /**
     * Evaluates a parsed query against a manga's in-memory tags, mirroring the semantics of
     * [queryToSql]/[textToSubQueries]. This is used for library search of manga whose metadata
     * isn't indexed in the search tables (e.g. galleries added before indexing), so tag search
     * there behaves the same as source search: namespace aliases (f: -> female:), lenient
     * (prefix) matching, the `$` exact marker, bare tags across any namespace, and `-` exclusion.
     *
     * @param components the parsed query (see [parseQuery]).
     * @param tags the manga's tags; for sources without namespaces pass RaisedTag(null, genre, _).
     * @return true if every query component is satisfied.
     */
    fun matchesTags(
        components: List<QueryComponent>,
        tags: List<RaisedTag>
    ): Boolean {
        return components.all { component ->
            val hit =
                when (component) {
                    is Namespace -> {
                        if (component.namespace == "uploader") {
                            // Uploader isn't part of the tags, so it can't be matched offline.
                            false
                        } else {
                            val tag = component.tag
                            if (tag != null && tag.components.isNotEmpty()) {
                                val matchers = tagNameMatchers(tag)
                                tags.any { namespaceMatches(it, component.namespace) && nameMatches(it.name, matchers) }
                            } else {
                                tags.any { namespaceMatches(it, component.namespace) }
                            }
                        }
                    }
                    is Text -> {
                        val matchers = tagNameMatchers(component)
                        tags.any { nameMatches(it.name, matchers) }
                    }
                    else -> false
                }
            if (component.excluded) !hit else hit
        }
    }

    private fun namespaceMatches(
        tag: RaisedTag,
        namespace: String
    ) = tag.namespace?.equals(namespace, ignoreCase = true) == true

    // Reuse the exact SQL LIKE patterns the engine builds so matching can't drift from it.
    // Compiled once per query component and tested against every tag.
    private fun tagNameMatchers(text: Text): List<Regex> {
        val patterns = if (text.exact) listOf(text.asQuery()) else text.asLenientTagQueries()
        return patterns.map { likeToRegex(it) }
    }

    private fun nameMatches(
        name: String,
        matchers: List<Regex>
    ): Boolean {
        val value = name.lowercase()
        return matchers.any { it.matches(value) }
    }

    companion object {
        private const val COL_MANGA_ID = "cmid"

        fun escapeLike(string: String): String {
            return string.replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("%", "\\%")
        }

        /**
         * Compiles a SQL LIKE [pattern] (where `%`/`_` are wildcards and `\` escapes them, matching
         * [escapeLike]) into an anchored [Regex] for evaluating parsed queries in memory.
         */
        fun likeToRegex(pattern: String): Regex {
            val regex = StringBuilder("^")
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '\\' -> {
                        val next = if (i + 1 < pattern.length) pattern[++i] else '\\'
                        regex.append(Regex.escape(next.toString()))
                    }
                    '%' -> regex.append(".*")
                    '_' -> regex.append(".")
                    else -> regex.append(Regex.escape(c.toString()))
                }
                i++
            }
            regex.append('$')
            return Regex(regex.toString(), RegexOption.DOT_MATCHES_ALL)
        }
    }
}
