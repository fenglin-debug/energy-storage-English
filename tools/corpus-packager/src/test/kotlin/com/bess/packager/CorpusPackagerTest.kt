package com.bess.packager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Packager tests for the v2 single-xlsx corpus (风电储能行业英文词汇表.xlsx).
 */
class CorpusPackagerTest {

    private val workspaceRoot: File = File(System.getProperty("user.dir")).let {
        // tools/corpus-packager -> tools -> android -> workspace root
        it.parentFile.parentFile.parentFile
    }

    private val mainXlsx = workspaceRoot.resolve(
        "outputs/bess_sales_english_corpus/风电储能行业英文词汇表.xlsx",
    )

    @Test
    fun normalizationFollowsFrozenAlgorithm() {
        assertEquals("round-trip efficiency", normalizeTerm("  Round-Trip  Efficiency "))
        assertEquals("soc", normalizeTerm("SoC"))
        assertEquals("don't", normalizeTerm("Don’t"))
        assertEquals("ac-coupled", normalizeTerm("AC–Coupled"))
    }

    @Test
    fun contentHashChangesWithContent() {
        val h1 = CorpusBuilder.contentHashOf("battery", "/ˈbætəri/", "电池")
        val h2 = CorpusBuilder.contentHashOf("battery", "/ˈbætəri/", "电池")
        val h3 = CorpusBuilder.contentHashOf("battery", "/ˈbætəri/", "蓄电池")
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
    }

    @Test
    fun buildRealCorpusPassesAllGates() {
        assertTrue("main xlsx missing: $mainXlsx", mainXlsx.isFile)

        val corpus = CorpusBuilder.build(mainXlsx, emptyMap(), null)

        // content counts from the frozen v2 sheet
        assertEquals(202, corpus.vocabulary.size)
        assertEquals(72, corpus.phrases.size)
        assertTrue(corpus.examples.size >= 144)
        assertEquals(15, corpus.scenarios.size)
        assertEquals(90, corpus.turns.size)
        assertEquals(45, corpus.pairs.size)

        // strict alternation, customer first, 6 turns per mission
        corpus.turns.groupBy { it.scenarioId }.forEach { (sid, group) ->
            val ordered = group.sortedBy { it.turnNo }
            assertEquals("$sid turn count", 6, ordered.size)
            ordered.forEachIndexed { index, turn ->
                assertEquals(
                    "${turn.id} speaker",
                    if (index % 2 == 0) "CUSTOMER" else "SALES_ENGINEER",
                    turn.speaker,
                )
            }
        }

        // every pair: adjacent customer->sales, hint, 2-4 alternatives, both audio refs
        corpus.pairs.forEach { pair ->
            val customer = corpus.turns.first { it.id == pair.customerTurnId }
            val sales = corpus.turns.first { it.id == pair.salesTurnId }
            assertEquals(pair.id, customer.turnNo + 1, sales.turnNo)
            assertTrue(pair.id, pair.referenceChineseHint.isNotBlank())
            assertTrue(pair.id, pair.formalAlternatives.size in 2..4)
            assertTrue(pair.id, !customer.audioAssetId.isNullOrBlank())
            assertTrue(pair.id, !sales.audioAssetId.isNullOrBlank())
        }

        // every word has required fields and both audio refs; ipa present
        corpus.vocabulary.forEach { w ->
            assertTrue(w.id, w.term.isNotBlank())
            assertTrue(w.id, w.chineseGloss.isNotBlank())
            assertTrue(w.id, w.exampleSentenceEn.isNotBlank())
            assertTrue(w.id, w.ipa.isNotBlank())
            assertTrue(w.id, w.topic.contains(':'))
            assertTrue(w.id, w.wordAudioAssetId.isNotBlank())
            assertTrue(w.id, w.exampleAudioAssetId.isNotBlank())
        }

        // Only stable ESS ids survive; legacy WIND links resolve by term.
        val inverter = corpus.vocabulary.first { it.term == "Inverter" }
        assertTrue(inverter.id.startsWith("ESS-"))
        assertTrue(corpus.vocabulary.none { it.id.startsWith("WIND-") })
        assertTrue(corpus.pairWords.none { it.wordId.startsWith("WIND-") })

        // anonymization scan produced no blocking hits
        assertTrue(corpus.report.anonymizationScan.blockedHits.isEmpty())
        assertEquals(0, corpus.report.anonymizationScan.amountPatternHits)

        // audio coverage: turns + 2 per word + phrases + examples
        assertEquals(
            90 + corpus.vocabulary.size * 2 + corpus.phrases.size + corpus.examples.size,
            corpus.audioAssets.size,
        )

        // without --vocab-examples every word falls back to template
        assertEquals(202, corpus.report.vocabularyMerged.templateExampleFallbacks.size)
        corpus.vocabulary.forEach { word ->
            assertTrue(
                word.id,
                1 + corpus.examples.count { word.id in it.linkedTermIds } >= 2,
            )
        }
        corpus.phrases.forEach { phrase ->
            assertTrue(
                phrase.id,
                corpus.examples.count { phrase.id in it.linkedTermIds } >= 2,
            )
        }
    }

    @Test
    fun vocabExamplesJsonBackfillsAuthoredExamples() {
        val authored = mapOf(
            "ESS-0001" to CorpusBuilder.VocabExample(
                "The battery energy storage system supports the grid.",
                "电池储能系统为电网提供支撑。",
            ),
        )
        val corpus = CorpusBuilder.build(mainXlsx, authored, null)
        val w = corpus.vocabulary.first { it.id == "ESS-0001" }
        assertEquals("The battery energy storage system supports the grid.", w.exampleSentenceEn)
        assertEquals("电池储能系统为电网提供支撑。", w.exampleSentenceZh)
        assertEquals(201, corpus.report.vocabularyMerged.templateExampleFallbacks.size)
    }

    @Test
    fun packRoundTripIsDeterministicAndValid() {
        val corpus = CorpusBuilder.build(mainXlsx, emptyMap(), null)
        val tmp = java.nio.file.Files.createTempDirectory("besspack-test").toFile()
        val packA = tmp.resolve("a.besspack")
        val packB = tmp.resolve("b.besspack")

        PackWriter.write(corpus, "2026.07.27.test", packA, null, null)
        PackWriter.write(corpus, "2026.07.27.test", packB, null, null)

        // deterministic: identical bytes for identical inputs
        assertEquals(sha256Hex(packA.readBytes()), sha256Hex(packB.readBytes()))

        // Missing build-time TTS is now a hard validation failure.
        val errors = PackValidator.validate(packA)
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.all { "placeholder audio is not decodable" in it })

        tmp.deleteRecursively()
    }
}
