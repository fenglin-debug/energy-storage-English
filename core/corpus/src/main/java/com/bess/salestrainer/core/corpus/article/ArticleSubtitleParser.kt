package com.bess.salestrainer.core.corpus.article

import com.bess.salestrainer.core.model.ArticleParagraph
import com.bess.salestrainer.core.model.LocalArticleImportError
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Strict, bounded parser for user-selected SRT and LRC article subtitles. */
object ArticleSubtitleParser {
    const val MAX_SUBTITLE_BYTES = 2L * 1024L * 1024L
    const val MAX_CUES = 10_000

    class SubtitleException(val error: LocalArticleImportError) : IllegalArgumentException(error.name)

    fun parse(bytes: ByteArray, displayName: String, audioDurationMs: Long): List<ArticleParagraph> {
        if (bytes.size > MAX_SUBTITLE_BYTES) fail(LocalArticleImportError.SUBTITLE_TOO_LARGE)
        val text = decode(bytes)
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val cues = when (extension) {
            "srt" -> parseSrt(text)
            "lrc" -> parseLrc(text, audioDurationMs)
            else -> fail(LocalArticleImportError.UNSUPPORTED_SUBTITLE_FORMAT)
        }
        if (cues.isEmpty()) fail(LocalArticleImportError.EMPTY_SUBTITLE)
        if (cues.size > MAX_CUES) fail(LocalArticleImportError.TOO_MANY_SUBTITLE_CUES)
        validateTimeline(cues, audioDurationMs)
        return cues
    }

    private fun parseSrt(text: String): List<ArticleParagraph> =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\\n\\s*\\n"))
            .filter { it.isNotBlank() }
            .map { block ->
                val lines = block.lines().map(String::trim).filter(String::isNotBlank)
                val timingIndex = lines.indexOfFirst { "-->" in it }
                if (timingIndex < 0) fail(LocalArticleImportError.INVALID_SUBTITLE_TIMELINE)
                val timing = lines[timingIndex].split("-->")
                if (timing.size != 2) fail(LocalArticleImportError.INVALID_SUBTITLE_TIMELINE)
                val start = parseSrtTimestamp(timing[0].trim())
                val end = parseSrtTimestamp(timing[1].trim().substringBefore(' '))
                val (english, chinese) = splitBilingual(lines.drop(timingIndex + 1))
                if (english.isBlank()) fail(LocalArticleImportError.INVALID_SUBTITLE_TIMELINE)
                ArticleParagraph(english, chinese, start, end)
            }

    private fun parseLrc(text: String, audioDurationMs: Long): List<ArticleParagraph> {
        val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val pending = mutableListOf<Pair<Long, Pair<String, String>>>()
        text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { rawLine ->
            val match = timestamp.find(rawLine) ?: return@forEach
            val body = rawLine.substring(match.range.last + 1).trim()
            if (body.isBlank()) return@forEach
            val start = match.groupValues[1].toLong() * 60_000L +
                match.groupValues[2].toLong() * 1_000L +
                fractionToMs(match.groupValues[3])
            val parts = body.split(Regex("\\s*[｜|]\\s*"), limit = 2)
            val english = cleanText(parts.firstOrNull().orEmpty())
            val chinese = cleanText(parts.getOrNull(1).orEmpty())
            if (english.isBlank()) fail(LocalArticleImportError.INVALID_SUBTITLE_TIMELINE)
            pending += start to (english to chinese)
        }
        return pending.mapIndexed { index, (start, textPair) ->
            ArticleParagraph(
                textEn = textPair.first,
                textZh = textPair.second,
                startMs = start,
                endMs = pending.getOrNull(index + 1)?.first ?: audioDurationMs,
            )
        }
    }

    private fun splitBilingual(lines: List<String>): Pair<String, String> {
        val cleaned = lines.map(::cleanText).filter(String::isNotBlank)
        val english = cleaned.filterNot(::containsChinese).joinToString(" ")
        val chinese = cleaned.filter(::containsChinese).joinToString(" ")
        return english to chinese
    }

    private fun validateTimeline(cues: List<ArticleParagraph>, audioDurationMs: Long) {
        if (audioDurationMs <= 0L) fail(LocalArticleImportError.INVALID_AUDIO)
        var previousEnd = -1L
        cues.forEach { cue ->
            val start = cue.startMs ?: fail(LocalArticleImportError.INVALID_SUBTITLE_TIMELINE)
            val end = cue.endMs ?: fail(LocalArticleImportError.INVALID_SUBTITLE_TIMELINE)
            if (start < 0L || end <= start || start < previousEnd || end > audioDurationMs) {
                fail(LocalArticleImportError.INVALID_SUBTITLE_TIMELINE)
            }
            previousEnd = end
        }
    }

    private fun parseSrtTimestamp(value: String): Long {
        val match = Regex("(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})").matchEntire(value)
            ?: fail(LocalArticleImportError.INVALID_SUBTITLE_TIMELINE)
        val hours = match.groupValues[1].ifBlank { "0" }.toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        if (minutes > 59L || seconds > 59L) fail(LocalArticleImportError.INVALID_SUBTITLE_TIMELINE)
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L +
            fractionToMs(match.groupValues[4])
    }

    private fun fractionToMs(value: String): Long = when (value.length) {
        0 -> 0L
        1 -> value.toLong() * 100L
        2 -> value.toLong() * 10L
        else -> value.take(3).toLong()
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) {
            return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        }
        if (bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        }
        if (bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        }
        decodeStrict(bytes, Charsets.UTF_8)?.let { return it }
        decodeStrict(bytes, Charset.forName("GB18030"))?.let { return it }
        fail(LocalArticleImportError.INVALID_SUBTITLE_ENCODING)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun cleanText(value: String): String =
        value.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()

    private fun containsChinese(value: String): Boolean = value.any { it.code in 0x3400..0x9FFF }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun fail(error: LocalArticleImportError): Nothing = throw SubtitleException(error)
}
