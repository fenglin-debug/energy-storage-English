package com.bess.packager

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Builds the deterministic in-memory corpus model from the single read-only
 * xlsx (风电储能行业英文词汇表.xlsx, schema v2).
 *
 * Sheet map:
 *  - 储能行业英文词汇 -> vocabulary (the wind sheet remains read-only input
 *    only for resolving legacy dialogue links to their surviving ESS ids)
 *  - 常用短语 -> phrases
 *  - 常用例句 -> examples
 *  - 情景任务 + 对话轮次 + 评分要点 -> scenarios / turns / pairs
 *
 * Data-row detection is by first-column id pattern, never by row index, so
 * title/header/▌category rows are skipped robustly.
 */
object CorpusBuilder {

    private val TERM_ID = Regex("^(WIND|ESS)-\\d{4}$")
    private val PHRASE_ID = Regex("^PHR-\\d{4}$")
    private val EXAMPLE_ID = Regex("^EX-\\d{4}$")
    private val MISSION_ID = Regex("^MIS-\\d{4}$")
    private val TURN_ID = Regex("^TURN-\\d{4}$")
    private val POINT_ID = Regex("^POINT-\\d{4}$")

    /** AI-authored per-word example sentences: termId -> (en, zh). */
    data class VocabExample(val exampleEn: String, val exampleZh: String)

    @Serializable
    private data class VocabExampleEntry(
        val termId: String,
        val exampleEn: String,
        val exampleZh: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun loadVocabExamples(file: File?): Map<String, VocabExample> {
        if (file == null || !file.isFile) return emptyMap()
        val entries = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(VocabExampleEntry.serializer()),
            file.readText(Charsets.UTF_8),
        )
        return entries.associate { it.termId to VocabExample(it.exampleEn, it.exampleZh) }
    }

    data class BuiltCorpus(
        val vocabulary: List<PackVocabulary>,
        val phrases: List<PackPhrase>,
        val examples: List<PackExample>,
        val scenarios: List<PackScenario>,
        val turns: List<PackDialogueTurn>,
        val pairs: List<PackDialoguePair>,
        val pairWords: List<PackDialoguePairWord>,
        val pairPhrases: List<PackDialoguePairPhrase>,
        val audioAssets: List<PackAudioAsset>,
        val report: BuildReport,
        /** assetId -> spoken text, used for placeholder audio + TTS emit. */
        val audioTexts: Map<String, String>,
    )

    fun build(
        mainXlsx: File,
        vocabExamples: Map<String, VocabExample>,
        audioDir: File?,
    ): BuiltCorpus {
        val violations = mutableListOf<String>()

        // ---------- vocabulary ----------
        data class RawWord(
            val id: String,
            val term: String,
            val ipa: String,
            val gloss: String,
            val topic: String,
            val source: String, // WIND / STORAGE
        )

        val rawWords = mutableListOf<RawWord>()
        var duplicatesMerged = 0
        val seenNorms = mutableSetOf<String>()
        // Every id ever seen -> its term text (including ids later dropped as
        // duplicates), so keyword references on dialogue turns can be
        // re-pointed at the surviving merged entry.
        val termById = mutableMapOf<String, String>()

        fun readVocabSheet(sheet: String, source: String) {
            var currentTopic = "未分类"
            ExcelReader.readSheet(mainXlsx, sheet).forEach { r ->
                val c0 = r.getOrElse(0) { "" }
                if (c0.startsWith("▌")) {
                    // ▌基础术语与设备（34条） -> 基础术语与设备
                    currentTopic = c0.removePrefix("▌")
                        .replace(Regex("（\\d+条）\\s*$"), "")
                        .trim()
                        .ifBlank { "未分类" }
                    return@forEach
                }
                if (!TERM_ID.matches(c0)) return@forEach
                val term = r.getOrElse(3) { "" }
                termById[c0] = term
                val norm = normalizeTerm(term)
                if (norm.isEmpty() || !seenNorms.add(norm)) {
                    duplicatesMerged++
                    return@forEach
                }
                rawWords += RawWord(
                    id = c0,
                    term = term,
                    ipa = r.getOrElse(4) { "" },
                    gloss = r.getOrElse(2) { "" },
                    topic = "$source:$currentTopic",
                    source = source,
                )
            }
        }
        // Keep the mixed source workbook untouched. WIND rows are read only
        // as a legacy id -> term lookup so old dialogue links can be re-pointed
        // to a stable ESS id; no WIND entry is emitted.
        ExcelReader.readSheet(mainXlsx, "风电行业英文词汇").forEach { row ->
            val id = row.getOrElse(0) { "" }
            if (TERM_ID.matches(id)) termById[id] = row.getOrElse(3) { "" }
        }
        readVocabSheet("储能行业英文词汇", "STORAGE")

        val missingRequired = rawWords.filter { it.term.isBlank() || it.gloss.isBlank() }.map { it.id }
        require(missingRequired.isEmpty()) { "vocabulary rows missing term/gloss: $missingRequired" }

        val templateFallbacks = mutableListOf<String>()
        val vocabularyIdByNorm = rawWords.associate { normalizeTerm(it.term) to it.id }
        val vocabulary = rawWords.map { w ->
            val wordAudioId = "aud_word_${w.id}"
            val exampleAudioId = "aud_example_${w.id}"
            val authored = vocabExamples[w.id]
            val exampleEn: String
            val exampleZh: String?
            if (authored != null && authored.exampleEn.isNotBlank()) {
                exampleEn = authored.exampleEn
                exampleZh = authored.exampleZh.ifBlank { null }
            } else {
                templateFallbacks += w.id
                exampleEn = "In our overseas projects, ${w.term} is a key consideration."
                exampleZh = "在我们的海外项目中，${w.gloss}是一个关键考量因素。"
            }
            PackVocabulary(
                id = w.id,
                term = w.term,
                normalizedTerm = normalizeTerm(w.term),
                ipa = w.ipa.ifBlank { "/" },
                partOfSpeech = "n.",
                chineseGloss = w.gloss,
                collocations = emptyList(),
                exampleSentenceEn = exampleEn,
                exampleSentenceZh = exampleZh,
                commonMistakes = "Confirm the exact technical scope before using this term.",
                topic = w.topic,
                scenarioTags = emptyList(),
                cefrLevel = "B2",
                wordAudioAssetId = wordAudioId,
                exampleAudioAssetId = exampleAudioId,
                contentSource = w.source,
                contentHash = contentHashOf(w.term, w.ipa, w.gloss, exampleEn),
            )
        }

        // ---------- phrases ----------
        val phraseRows = ExcelReader.readSheet(mainXlsx, "常用短语")
            .filter { r -> PHRASE_ID.matches(r.getOrElse(0) { "" }) }
        val phrases = phraseRows.map { r ->
            val id = r[0]
            val textEn = r.getOrElse(4) { "" }
            val textZh = r.getOrElse(5) { "" }
            PackPhrase(
                id = id,
                industry = r.getOrElse(1) { "" },
                scene = r.getOrElse(2) { "" },
                category = r.getOrElse(3) { "" },
                textEn = textEn,
                textZh = textZh,
                linkedTermIds = r.getOrElse(6) { "" }.split(';').map { it.trim() }
                    .filter { TERM_ID.matches(it) },
                sourceType = r.getOrElse(7) { "" },
                audioAssetId = "aud_phrase_$id",
                contentHash = contentHashOf(textEn, textZh),
            )
        }
        require(phrases.none { it.textEn.isBlank() || it.textZh.isBlank() }) {
            "phrase rows missing text: ${phrases.filter { it.textEn.isBlank() }.map { it.id }}"
        }

        // ---------- examples ----------
        val exampleRows = ExcelReader.readSheet(mainXlsx, "常用例句")
            .filter { r -> EXAMPLE_ID.matches(r.getOrElse(0) { "" }) }
        val sourceExamples = exampleRows.map { r ->
            val id = r[0]
            val textEn = r.getOrElse(4) { "" }
            val textZh = r.getOrElse(5) { "" }
            PackExample(
                id = id,
                industry = r.getOrElse(1) { "" },
                scene = r.getOrElse(2) { "" },
                speaker = when (r.getOrElse(3) { "" }) {
                    "Customer" -> "CUSTOMER"
                    "Sales Engineer" -> "SALES_ENGINEER"
                    else -> "UNKNOWN"
                },
                textEn = textEn,
                textZh = textZh,
                linkedTermIds = r.getOrElse(6) { "" }.split(';').map { it.trim() }
                    .mapNotNull { id ->
                        vocabularyIdByNorm[normalizeTerm(termById[id] ?: id)]
                    }.distinct(),
                dialogueGroupId = r.getOrElse(7) { "" }.ifBlank { null },
                sourceType = r.getOrElse(8) { "" },
                audioAssetId = "aud_example_$id",
                contentHash = contentHashOf(textEn, textZh),
            )
        }
        val wordSupplementExamples = vocabulary.mapNotNull { word ->
            if (sourceExamples.any { word.id in it.linkedTermIds }) return@mapNotNull null
            val en =
                "Our battery energy storage proposal explains how ${word.term} affects system performance."
            val zh = "我们的储能方案说明了${word.chineseGloss}如何影响系统性能。"
            PackExample(
                id = "EXV-${word.id}",
                industry = "储能",
                scene = "技术方案",
                speaker = "SALES_ENGINEER",
                textEn = en,
                textZh = zh,
                linkedTermIds = listOf(word.id),
                sourceType = "BUILT_IN_BESS",
                audioAssetId = "aud_example_EXV-${word.id}",
                contentHash = contentHashOf(en, zh, word.id),
            )
        }
        val phraseSupplementExamples = phrases.flatMap { phrase ->
            listOf(
                "In the battery energy storage meeting, the engineer said, \"${phrase.textEn}.\"" to
                    "在储能会议中，工程师使用了“${phrase.textZh}”这一表达。",
                "The customer repeated \"${phrase.textEn}\" to confirm the project requirement." to
                    "客户重复“${phrase.textZh}”，以确认项目要求。",
            ).mapIndexed { index, (en, zh) ->
                PackExample(
                    id = "EXP-${phrase.id}-${index + 1}",
                    industry = "储能",
                    scene = phrase.scene,
                    speaker = if (index == 0) "SALES_ENGINEER" else "CUSTOMER",
                    textEn = en,
                    textZh = zh,
                    linkedTermIds = listOf(phrase.id),
                    sourceType = "BUILT_IN_BESS",
                    audioAssetId = "aud_example_EXP-${phrase.id}-${index + 1}",
                    contentHash = contentHashOf(en, zh, phrase.id),
                )
            }
        }
        val examples = sourceExamples + wordSupplementExamples + phraseSupplementExamples
        require(examples.none { it.textEn.isBlank() || it.textZh.isBlank() }) {
            "example rows missing text: ${examples.filter { it.textEn.isBlank() }.map { it.id }}"
        }
        require(vocabulary.none { it.id.startsWith("WIND-") || it.contentSource == "WIND" })
        require(phrases.all { phrase ->
            examples.count { phrase.id in it.linkedTermIds } >= 2
        }) { "every BESS phrase must have at least two contextual examples" }
        require(vocabulary.all { word ->
            1 + examples.count { word.id in it.linkedTermIds } >= 2
        }) { "every BESS word must have at least two examples including its primary example" }

        // ---------- scenarios (情景任务) ----------
        val scenarioRows = ExcelReader.readSheet(mainXlsx, "情景任务")
            .filter { r -> MISSION_ID.matches(r.getOrElse(0) { "" }) }
        val scenarios = scenarioRows.map { r ->
            val goal = r.getOrElse(6) { "" }
            val success = r.getOrElse(7) { "" }
            PackScenario(
                id = r[0],
                title = r.getOrElse(2) { "" },
                topic = r.getOrElse(1) { "" },
                salesStage = r.getOrElse(8) { "" }, // 来源类型 as stage proxy
                customerRole = r.getOrElse(4) { "" },
                difficulty = r.getOrElse(3) { "" },
                projectType = r.getOrElse(1) { "" },
                estimatedMinutes = 5,
                description = listOf(
                    r.getOrElse(5) { "" },
                    if (goal.isNotBlank()) "沟通目标：$goal" else "",
                    if (success.isNotBlank()) "成功标准：$success" else "",
                ).filter { it.isNotBlank() }.joinToString("\n").ifBlank { null },
                contentHash = contentHashOf(r.getOrElse(2) { "" }, r.getOrElse(5) { "" }, goal),
            )
        }

        // ---------- dialogue turns ----------
        data class RawTurn(
            val id: String,
            val missionId: String,
            val seq: Int,
            val speaker: String,
            val en: String,
            val zh: String,
            val hint: String,
            val termIds: List<String>,
            val phraseIds: List<String>,
            val pointIds: List<String>,
            val risk: String?,
            val alt: String?,
        )

        val rawTurns = ExcelReader.readSheet(mainXlsx, "对话轮次")
            .filter { r -> TURN_ID.matches(r.getOrElse(0) { "" }) }
            .map { r ->
                RawTurn(
                    id = r[0],
                    missionId = r.getOrElse(1) { "" },
                    seq = r.getOrElse(2) { "" }.toIntOrNull()
                        ?: error("bad turn seq: ${r.take(3)}"),
                    speaker = r.getOrElse(3) { "" },
                    en = r.getOrElse(4) { "" },
                    zh = r.getOrElse(5) { "" },
                    hint = r.getOrElse(6) { "" },
                    termIds = r.getOrElse(7) { "" }.split(';').map { it.trim() }
                        .filter { TERM_ID.matches(it) },
                    phraseIds = r.getOrElse(8) { "" }.split(';').map { it.trim() }
                        .filter { PHRASE_ID.matches(it) },
                    pointIds = r.getOrElse(9) { "" }.split(';').map { it.trim() }
                        .filter { POINT_ID.matches(it) },
                    risk = r.getOrElse(10) { "" }.ifBlank { null },
                    alt = r.getOrElse(11) { "" }.ifBlank { null },
                )
            }

        val missionIds = scenarios.map { it.id }.toSet()
        rawTurns.forEach { t ->
            if (t.missionId !in missionIds) violations += "${t.id}: unknown mission ${t.missionId}"
            if (t.en.isBlank()) violations += "${t.id}: blank english"
        }

        val turnsByMission = rawTurns.groupBy { it.missionId }
        turnsByMission.forEach { (mid, turns) ->
            val ordered = turns.sortedBy { it.seq }
            if (ordered.map { it.seq } != (1..ordered.size).toList()) {
                violations += "$mid: turn numbers not continuous from 1"
            }
            ordered.forEachIndexed { index, turn ->
                val expected = if (index % 2 == 0) "Customer" else "Sales Engineer"
                if (turn.speaker != expected) {
                    violations += "$mid turn ${turn.seq}: expected $expected, got ${turn.speaker}"
                }
            }
        }

        val turns = rawTurns.map { t ->
            PackDialogueTurn(
                id = t.id,
                scenarioId = t.missionId,
                turnNo = t.seq,
                speaker = if (t.speaker == "Customer") "CUSTOMER" else "SALES_ENGINEER",
                textEn = t.en,
                textZh = t.zh.ifBlank { null },
                hint = t.hint.ifBlank { null },
                audioAssetId = "aud_${t.id}",
                contentHash = contentHashOf(t.en, t.zh, t.speaker),
            )
        }

        // ---------- scoring points ----------
        data class RawPoint(
            val id: String,
            val missionId: String,
            val type: String,
            val descZh: String,
            val keywordsEn: String,
            val required: Boolean,
            val weight: Int,
        )

        val points = ExcelReader.readSheet(mainXlsx, "评分要点")
            .filter { r -> POINT_ID.matches(r.getOrElse(0) { "" }) }
            .map { r ->
                RawPoint(
                    id = r[0],
                    missionId = r.getOrElse(1) { "" },
                    type = r.getOrElse(2) { "" },
                    descZh = r.getOrElse(3) { "" },
                    keywordsEn = r.getOrElse(4) { "" },
                    required = r.getOrElse(5) { "" } == "是",
                    weight = r.getOrElse(6) { "" }.toIntOrNull() ?: 0,
                )
            }
        val pointsById = points.associateBy { it.id }

        // ---------- pairs ----------
        val pairs = mutableListOf<PackDialoguePair>()
        val pairWords = mutableListOf<PackDialoguePairWord>()
        val pairPhrases = mutableListOf<PackDialoguePairPhrase>()
        val assignedPointIds = mutableSetOf<String>()

        turnsByMission.forEach { (mid, missionTurns) ->
            val ordered = missionTurns.sortedBy { it.seq }
            ordered.chunked(2).forEachIndexed { pairIndex, chunk ->
                if (chunk.size != 2) {
                    violations += "$mid: dangling turn at pair $pairIndex"
                    return@forEachIndexed
                }
                val customer = chunk[0]
                val sales = chunk[1]
                val pairId = "${mid}_P%03d".format(pairIndex + 1)
                if (sales.en.isBlank() || sales.zh.isBlank()) {
                    violations += "$pairId: reference answer core/chinese hint blank"
                }
                val alternatives = buildList {
                    add(sales.en)
                    sales.alt?.takeIf { it.isNotBlank() }?.let { add(it) }
                    add(deriveFormalAlternative(sales.en))
                }.distinct().take(4)

                // scoring points: the sales turn's 必答要点Id(s) attach here
                val pairPoints = mutableListOf<PackScoringPoint>()
                sales.pointIds.forEach { pid ->
                    pointsById[pid]?.let { p ->
                        pairPoints += PackScoringPoint(
                            id = p.id, type = p.type, descriptionZh = p.descZh,
                            keywordsEn = p.keywordsEn, required = p.required, weight = p.weight,
                        )
                        assignedPointIds += p.id
                    } ?: run { violations += "$pairId: unknown scoring point $pid" }
                }

                pairs += PackDialoguePair(
                    id = pairId,
                    scenarioId = mid,
                    pairIndex = pairIndex,
                    customerTurnId = customer.id,
                    salesTurnId = sales.id,
                    referenceCoreEn = sales.en,
                    referenceChineseHint = sales.zh,
                    formalAlternatives = alternatives,
                    scoringPoints = pairPoints,
                    riskNote = sales.risk,
                    contentHash = contentHashOf(sales.en, sales.zh, alternatives.joinToString("|")),
                )

                var wordOrder = 0
                (customer.termIds + sales.termIds).distinct().forEach { tid ->
                    // Re-point legacy WIND ids to the surviving stable ESS id.
                    val resolved = vocabularyIdByNorm[normalizeTerm(termById[tid] ?: tid)] ?: tid
                    require(resolved in vocabulary.map { it.id }) {
                        "$pairId: unresolved non-BESS vocabulary link $tid"
                    }
                    pairWords += PackDialoguePairWord(pairId, resolved, wordOrder++)
                }
                var phraseOrder = 0
                (customer.phraseIds + sales.phraseIds).distinct().forEach { pid ->
                    pairPhrases += PackDialoguePairPhrase(pairId, pid, phraseOrder++)
                }
            }
        }

        // Unreferenced points attach to the last pair of their mission.
        val unassigned = mutableListOf<String>()
        points.filter { it.id !in assignedPointIds }.forEach { p ->
            val target = pairs.lastOrNull { it.scenarioId == p.missionId }
            if (target == null) {
                unassigned += "${p.id} (mission ${p.missionId} has no pairs)"
            } else {
                val idx = pairs.indexOf(target)
                pairs[idx] = target.copy(
                    scoringPoints = target.scoringPoints + PackScoringPoint(
                        id = p.id, type = p.type, descriptionZh = p.descZh,
                        keywordsEn = p.keywordsEn, required = p.required, weight = p.weight,
                    ),
                )
            }
        }

        require(violations.isEmpty()) { "scenario invariant violations: $violations" }

        // ---------- anonymization scan ----------
        val blockedHits = mutableListOf<String>()
        var amountHits = 0
        val blockedPatterns = listOf(
            Regex("SRC-\\d+"),
            Regex("[￥$€]\\s?\\d"),
            Regex("\\d+(\\.\\d+)?\\s?(万元|元/kWh|美元|USD|RMB|CNY)"),
            Regex("报价|合同金额|中标价"),
        )
        fun scan(tag: String, text: String) {
            blockedPatterns.forEachIndexed { index, regex ->
                if (regex.containsMatchIn(text)) {
                    if (index == 0) blockedHits += "$tag: source locator in '$text'"
                    else amountHits++
                }
            }
        }
        vocabulary.forEach { w ->
            scan("vocab:${w.id}", w.term + " " + w.chineseGloss + " " + w.exampleSentenceEn)
        }
        phrases.forEach { p -> scan("phrase:${p.id}", p.textEn + " " + p.textZh) }
        examples.forEach { e -> scan("example:${e.id}", e.textEn + " " + e.textZh) }
        turns.forEach { t -> scan("turn:${t.id}", t.textEn + " " + t.textZh.orEmpty()) }
        pairs.forEach { p -> scan("pair:${p.id}", p.referenceCoreEn + p.referenceChineseHint) }
        require(blockedHits.isEmpty()) { "anonymization blocking hits: $blockedHits" }
        require(amountHits == 0) { "amount pattern hits: $amountHits" }

        // ---------- audio assets + texts ----------
        val audioTexts = mutableMapOf<String, String>()
        turns.forEach { t -> audioTexts[t.audioAssetId!!] = t.textEn }
        vocabulary.forEach { w ->
            audioTexts[w.wordAudioAssetId] = w.term
            audioTexts[w.exampleAudioAssetId] = w.exampleSentenceEn
        }
        phrases.forEach { p -> audioTexts[p.audioAssetId] = p.textEn }
        examples.forEach { e -> audioTexts[e.audioAssetId] = e.textEn }

        val turnById = turns.associateBy { it.id }
        val audioAssets = audioTexts.map { (id, text) ->
            val kind = when {
                id.startsWith("aud_TURN-") -> {
                    val turnId = id.removePrefix("aud_")
                    if (turnById[turnId]?.speaker == "CUSTOMER") "CUSTOMER_TURN" else "SALES_TURN"
                }
                id.startsWith("aud_word_") -> "WORD"
                id.startsWith("aud_phrase_") -> "PHRASE"
                else -> "EXAMPLE"
            }
            val subdir = when (kind) {
                "CUSTOMER_TURN", "SALES_TURN" -> "turns"
                "WORD" -> "words"
                "PHRASE" -> "phrases"
                else -> "examples"
            }
            val relPath = "audio/$subdir/$id.m4a"
            val onDisk = audioDir?.resolve(relPath.removePrefix("audio/"))?.takeIf { it.isFile }
            PackAudioAsset(
                id = id,
                kind = kind,
                relativePath = relPath,
                sha256 = onDisk?.let { sha256Hex(it.readBytes()) }
                    ?: sha256Hex("BESS-PLACEHOLDER-AUDIO\n$text"),
                mimeType = "audio/mp4",
                codec = "aac-lc",
                durationMs = onDisk?.let { estimateDurationMs(it.length()) }
                    ?: estimateDurationMs(text.length.toLong()),
                sizeBytes = onDisk?.length() ?: 0,
            )
        }

        val presentOnDisk = audioAssets.count { it.sizeBytes > 0 }
        val report = BuildReport(
            packageId = "bess-sales-english-core",
            contentVersion = "",
            generatedAt = deterministicInstant(),
            vocabularyMerged = BuildReport.VocabularyMergeReport(
                mainCount = vocabulary.size,
                windCount = rawWords.count { it.source == "WIND" },
                storageCount = rawWords.count { it.source == "STORAGE" },
                duplicatesMerged = duplicatesMerged,
                templateExampleFallbacks = templateFallbacks,
                missingRequiredFields = missingRequired,
                missingAudioPlaceholders = audioAssets.filter { it.sizeBytes == 0L }.map { it.id },
            ),
            scenarioChecks = BuildReport.ScenarioCheckReport(
                scenarioCount = scenarios.size,
                turnCount = turns.size,
                pairCount = pairs.size,
                phraseCount = phrases.size,
                exampleCount = examples.size,
                turnsPerScenario = turnsByMission.mapValues { it.value.size },
                unassignedScoringPoints = unassigned,
                violations = violations,
            ),
            anonymizationScan = BuildReport.AnonymizationReport(
                blockedHits = blockedHits,
                amountPatternHits = amountHits,
            ),
            overlayDecisions = emptyList(),
            audio = BuildReport.AudioReport(
                requiredAssets = audioAssets.size,
                placeholders = audioAssets.size - presentOnDisk,
                presentOnDisk = presentOnDisk,
            ),
        )

        return BuiltCorpus(
            vocabulary = vocabulary,
            phrases = phrases,
            examples = examples,
            scenarios = scenarios,
            turns = turns,
            pairs = pairs,
            pairWords = pairWords,
            pairPhrases = pairPhrases,
            audioAssets = audioAssets,
            report = report,
            audioTexts = audioTexts,
        )
    }

    // ------------------------------------------------------------------

    internal fun contentHashOf(vararg parts: String): String =
        sha256Hex(parts.joinToString(""))

    /** Fixed timestamp keeps output deterministic (TDD §7.3). */
    internal fun deterministicInstant(): String = "2026-07-26T00:00:00Z"

    private fun estimateDurationMs(textLength: Long): Long =
        // ~14 chars/sec speech at 0.95 rate; used only until real audio lands.
        (textLength / 14.0 * 1000).toLong().coerceAtLeast(800)

    private fun deriveFormalAlternative(core: String): String {
        val trimmed = core.trim().removeSuffix(".")
        return "To put it more formally, ${trimmed.replaceFirstChar { it.lowercase() }}."
    }
}
