package dev.zolive.zviewer.data

import java.text.Normalizer

/** 复用 Houbb Pinyin 的字典文件，保留词组读音，避免构建额外的分词索引。 */
internal object PinyinOrder {
    private val marks = Regex("\\p{M}+")
    private val characters by lazy { load("pinyin_dict_char.txt", 56_000) }
    private val phrases by lazy { load("pinyin_dict_phrase.txt", 58_000) }
    private val longestPhrase by lazy { phrases.keys.groupingBy { it.first() }.fold(0) { length, word ->
        maxOf(length, word.length)
    } }

    fun reading(text: String): String {
        val reading = buildString {
            var start = 0
            while (start < text.length) {
                if (isNotEmpty()) append(' ')
                val characterEnd = text.offsetByCodePoints(start, 1)
                var end = minOf(text.length, start + (longestPhrase[text[start]] ?: 0))
                var phrase: String? = null
                while (end > characterEnd) {
                    phrase = phrases[text.substring(start, end)]
                    if (phrase != null) break
                    end--
                }
                if (phrase != null) {
                    append(phrase)
                    start = end
                } else {
                    val character = text.substring(start, characterEnd)
                    append(characters[character] ?: character)
                    start = characterEnd
                }
            }
        }
        return Normalizer.normalize(reading, Normalizer.Form.NFD).replace("u\u0308", "v").replace(marks, "")
    }

    private fun load(name: String, capacity: Int): Map<String, String> {
        val entries = HashMap<String, String>(capacity)
        val stream = requireNotNull(PinyinOrder::class.java.getResourceAsStream("/$name"))
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val colon = line.indexOf(':')
                if (colon > 0) entries[line.substring(0, colon)] = line.substring(colon + 1).substringBefore(',')
            }
        }
        return entries
    }
}
