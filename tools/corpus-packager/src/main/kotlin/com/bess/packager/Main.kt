package com.bess.packager

import java.io.File

/**
 * Corpus packager entry point. Implemented fully in TASK-A-03.
 *
 * Modes:
 *   --dump-headers  Print sheet names + header rows of input xlsx for column calibration.
 *   (default)       Build a deterministic .besspack from the two xlsx corpora.
 */
fun main(args: Array<String>) {
    val opts = args.toList()
    if (opts.contains("--dump-headers")) {
        val path = opts.getOrNull(opts.indexOf("--dump-headers") + 1)
            ?: error("--dump-headers requires an xlsx path")
        println("[corpus-packager] dump-headers for: $path")
        println("TODO(TASK-A-03): read sheet names + header rows via Apache POI")
        return
    }
    println("[corpus-packager] TASK-A-03 implementation pending. args=${opts.joinToString(" ")}")
    println("Input must come from read-only xlsx under outputs/bess_sales_english_corpus/")
}
