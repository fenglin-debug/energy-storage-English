package com.bess.packager

import java.io.File

/**
 * Corpus packager entry point (v2 corpus schema).
 *
 * Usage:
 *   build  --main <vocab.xlsx> --out <pack.besspack>
 *          [--audio-dir <dir>] [--emit-audio-texts <dir>]
 *          [--vocab-examples <json>] [--content-version <v>]
 *   validate <pack.besspack>
 *   --dump-headers <xlsx>
 *
 * Inputs are read-only. Audio is optional: when --audio-dir is absent or a
 * file is missing, a deterministic placeholder is embedded and reported, so
 * the offline TTS pipeline can regenerate the pack later with real audio.
 */
fun main(args: Array<String>) {
    val opts = args.toList()
    when {
        opts.isEmpty() -> printUsage()
        opts[0] == "build" -> runBuild(opts.drop(1))
        opts[0] == "validate" -> runValidate(opts.drop(1))
        opts[0] == "--dump-headers" -> {
            val path = opts.getOrNull(1) ?: error("--dump-headers requires an xlsx path")
            val file = File(path)
            listOf(
                "风电行业英文词汇", "储能行业英文词汇", "词汇总览", "常用短语",
                "常用例句", "情景任务", "对话轮次", "评分要点",
            ).forEach { sheet ->
                runCatching {
                    val rows = ExcelReader.readSheet(file, sheet).take(4)
                    println("== $sheet ==")
                    rows.forEachIndexed { index, row -> println("  row${index + 1}: $row") }
                }
            }
        }
        else -> printUsage()
    }
}

private fun printUsage() {
    println(
        """
        corpus-packager (v2)
          build --main <xlsx> --out <pack.besspack>
                [--audio-dir <dir>] [--emit-audio-texts <dir>]
                [--vocab-examples <json>] [--content-version <v>]
          validate <pack.besspack>
          --dump-headers <xlsx>
        """.trimIndent(),
    )
}

private fun optionValues(opts: List<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < opts.size) {
        val key = opts[i]
        if (key.startsWith("--") && i + 1 < opts.size) {
            map[key] = opts[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return map
}

private fun runBuild(opts: List<String>) {
    val values = optionValues(opts)
    val main = File(values["--main"] ?: error("--main required"))
    val out = File(values["--out"] ?: error("--out required"))
    val audioDir = values["--audio-dir"]?.let(::File)
    val emitTexts = values["--emit-audio-texts"]?.let(::File)
    val vocabExamples = CorpusBuilder.loadVocabExamples(values["--vocab-examples"]?.let(::File))
    val contentVersion = values["--content-version"] ?: "2026.07.27.v1"

    val corpus = CorpusBuilder.build(main, vocabExamples, audioDir)
    PackWriter.write(corpus, contentVersion, out, audioDir, emitTexts)

    val r = corpus.report
    println(
        """
        [build] OK -> ${out.absolutePath}
          vocabulary: ${r.vocabularyMerged.mainCount} (wind ${r.vocabularyMerged.windCount} + storage ${r.vocabularyMerged.storageCount}, dupes merged ${r.vocabularyMerged.duplicatesMerged}, template examples ${r.vocabularyMerged.templateExampleFallbacks.size})
          phrases/examples: ${r.scenarioChecks.phraseCount}/${r.scenarioChecks.exampleCount}
          scenarios/turns/pairs: ${r.scenarioChecks.scenarioCount}/${r.scenarioChecks.turnCount}/${r.scenarioChecks.pairCount}
          audio: ${r.audio.presentOnDisk} real, ${r.audio.placeholders} placeholders
        """.trimIndent(),
    )
}

private fun runValidate(opts: List<String>) {
    val path = opts.firstOrNull() ?: error("validate requires a .besspack path")
    val result = PackValidator.validate(File(path))
    if (result.isEmpty()) {
        println("[validate] OK: $path")
    } else {
        println("[validate] FAILED:")
        result.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
