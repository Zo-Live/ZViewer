package dev.zolive.zviewer.data

import java.lang.Character.UnicodeScript
import java.text.CollationKey
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

object NaturalOrder : Comparator<String> {
    private enum class Kind { NUMBER, LATIN, HAN, JAPANESE, OTHER }

    private data class Part(val kind: Kind, val text: String, val collation: CollationKey? = null) : Comparable<Part> {
        override fun compareTo(other: Part): Int {
            val kindOrder = kind.compareTo(other.kind)
            if (kindOrder != 0) return kindOrder
            if (kind == Kind.NUMBER) return compareNumbers(text, other.text)
            return collation?.compareTo(other.collation!!) ?: text.compareTo(other.text)
        }
    }

    private val rootCollator = collator(Locale.ROOT)
    private val japaneseCollator = collator(Locale.JAPANESE)

    private fun collator(locale: Locale) = ThreadLocal.withInitial {
        Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    }

    private class Key(val name: String, val parts: List<Part>) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            for (index in 0 until minOf(parts.size, other.parts.size)) {
                val order = parts[index].compareTo(other.parts[index])
                if (order != 0) return order
            }
            return parts.size.compareTo(other.parts.size).takeIf { it != 0 } ?: name.compareTo(other.name)
        }
    }

    private val pathParts = Regex("""[^/\\]+|[/\\]""")
    private val kana = Regex("""[\p{sc=Hiragana}\p{sc=Katakana}ー]""")

    override fun compare(first: String, second: String): Int = key(first).compareTo(key(second))

    fun <T> sorted(items: Iterable<T>, name: (T) -> String): List<T> =
        items.map { it to key(name(it)) }.sortedBy { it.second }.map { it.first }

    private fun key(name: String): Key {
        val parts = buildList {
            for (pathPart in pathParts.findAll(Normalizer.normalize(name, Normalizer.Form.NFKC))) {
                val text = pathPart.value
                val japanese = kana.containsMatchIn(text)
                var start = 0
                while (start < text.length) {
                    val first = text.codePointAt(start)
                    val kind = kind(first, japanese)
                    var end = start + Character.charCount(first)
                    while (end < text.length) {
                        val next = text.codePointAt(end)
                        if (kind(next, japanese) != kind && Character.getType(next) != Character.NON_SPACING_MARK.toInt()) break
                        end += Character.charCount(next)
                    }
                    val value = text.substring(start, end)
                    add(when (kind) {
                        Kind.NUMBER -> Part(kind, buildString {
                            value.codePoints().forEach { digit ->
                                val number = Character.digit(digit, 10)
                                if (number >= 0) append('0' + number)
                            }
                        }.trimStart('0'))
                        Kind.HAN -> Part(kind, PinyinOrder.reading(value))
                        Kind.JAPANESE -> Part(kind, value, japaneseCollator.get()!!.getCollationKey(value))
                        else -> Part(kind, value, rootCollator.get()!!.getCollationKey(value))
                    })
                    start = end
                }
            }
        }
        return Key(name, parts)
    }

    private fun kind(codePoint: Int, japanese: Boolean): Kind {
        if (Character.isDigit(codePoint)) return Kind.NUMBER
        return when (UnicodeScript.of(codePoint)) {
            UnicodeScript.LATIN -> Kind.LATIN
            UnicodeScript.HAN -> if (japanese) Kind.JAPANESE else Kind.HAN
            UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> Kind.JAPANESE
            else -> if (codePoint == 'ー'.code) Kind.JAPANESE else Kind.OTHER
        }
    }
}

private fun compareNumbers(first: String, second: String): Int =
    first.length.compareTo(second.length).takeIf { it != 0 } ?: first.compareTo(second)

/** 旧版以排序后的页序号保存进度和视频位置。 */
internal object PreviousPageOrder : Comparator<String> {
    private val parts = Regex("[0-9]+|[^0-9]+")

    override fun compare(first: String, second: String): Int {
        val left = parts.findAll(first.lowercase(Locale.ROOT)).iterator()
        val right = parts.findAll(second.lowercase(Locale.ROOT)).iterator()
        while (left.hasNext() && right.hasNext()) {
            val leftPart = left.next().value
            val rightPart = right.next().value
            val order = if (leftPart.first().isDigit() && rightPart.first().isDigit()) {
                compareNumbers(leftPart.trimStart('0').ifEmpty { "0" }, rightPart.trimStart('0').ifEmpty { "0" })
            } else leftPart.compareTo(rightPart)
            if (order != 0) return order
        }
        return left.hasNext().compareTo(right.hasNext()).takeIf { it != 0 } ?: first.compareTo(second)
    }
}
