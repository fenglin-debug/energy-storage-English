package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.ContentSource
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.RecordWordReview
import com.bess.salestrainer.core.model.ReviewResult
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.VocabularyFilter
import com.bess.salestrainer.core.model.VocabularyQueue
import com.bess.salestrainer.core.model.WordMemoryState
import com.bess.salestrainer.core.model.contract.VocabularyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * In-memory fake driving the vocabulary flows until real Room impl replaces it.
 * Maintains a small queue state machine so UI can exercise INTRODUCE..TRANSFER + rating.
 */
class FakeVocabularyRepository : VocabularyRepository {

    private val now: Instant = Instant.now()

    private val words = MutableStateFlow(sampleWords())

    private val queue = MutableStateFlow(
        VocabularyQueue(
            newWords = words.value.take(2),
            dueReviews = words.value.drop(2),
            overflowDeferredCount = 0,
        )
    )

    override fun observeWord(wordId: String): Flow<Vocabulary> =
        words.map { list -> list.first { it.id == wordId } }

    override fun observeTodayQueue(): Flow<VocabularyQueue> = queue

    override fun observeVocabulary(filter: VocabularyFilter): Flow<List<Vocabulary>> =
        words.map { list ->
            list.filter { w ->
                (filter.topic == null || w.topic == filter.topic) &&
                    (filter.cefrLevel == null || w.cefrLevel == filter.cefrLevel) &&
                    (!filter.favoritesOnly || w.memoryState?.isFavorite == true) &&
                    (filter.query.isNullOrBlank() || w.term.contains(filter.query, ignoreCase = true))
            }
        }

    override suspend fun recordReview(command: RecordWordReview): ReviewResult {
        // Fake scheduling: bump due date based on rating, mark state REVIEW.
        val days = when (command.rating) {
            com.bess.salestrainer.core.model.Rating.AGAIN -> 0L
            com.bess.salestrainer.core.model.Rating.HARD -> 1L
            com.bess.salestrainer.core.model.Rating.GOOD -> 3L
            com.bess.salestrainer.core.model.Rating.EASY -> 7L
        }
        val nextDue = command.reviewedAt.plus(days, ChronoUnit.DAYS)
        var result: ReviewResult? = null
        words.value = words.value.map { w ->
            if (w.id == command.wordId) {
                val prev = w.memoryState
                val newState = WordMemoryState(
                    wordId = w.id,
                    fsrsState = FsrsState.REVIEW,
                    difficulty = 5.0,
                    stability = days.coerceAtLeast(1).toDouble(),
                    dueAt = nextDue,
                    reps = (prev?.reps ?: 0) + 1,
                    lapses = prev?.lapses ?: 0,
                    masteredUi = false,
                    lastQuestionMode = command.questionMode,
                    isFavorite = prev?.isFavorite ?: false,
                )
                result = ReviewResult(w.id, FsrsState.REVIEW, nextDue, newState.reps, newState.lapses)
                w.copy(memoryState = newState)
            } else w
        }
        // Remove the reviewed word from today's queue.
        queue.value = queue.value.copy(
            newWords = queue.value.newWords.filterNot { it.id == command.wordId },
            dueReviews = queue.value.dueReviews.filterNot { it.id == command.wordId },
        )
        return result ?: error("word not found: ${command.wordId}")
    }

    private fun sampleWords(): List<Vocabulary> {
        fun mem(state: FsrsState, dueDaysAgo: Long) = WordMemoryState(
            wordId = "", fsrsState = state, difficulty = 5.0, stability = 2.0,
            dueAt = now.minus(dueDaysAgo, ChronoUnit.DAYS),
            reps = 1, lapses = 0, masteredUi = false, lastQuestionMode = QuestionMode.INTRODUCE,
        )
        return listOf(
            Vocabulary(
                id = "V_001", term = "round-trip efficiency", normalizedTerm = "round-trip efficiency",
                ipa = "/ˌraʊnd ˈtrɪp ɪˈfɪʃənsi/", partOfSpeech = "n.",
                chineseGloss = "往返效率", englishDefinition = "The ratio of energy discharged to energy charged.",
                collocations = listOf("improve round-trip efficiency"),
                exampleSentenceEn = "Our LFP system achieves 95% round-trip efficiency.",
                exampleSentenceZh = "我们的磷酸铁锂系统实现95%往返效率。",
                commonMistakes = "Do not say 'back and forth efficiency'.",
                topic = "系统性能", scenarioTags = listOf("技术交流"), cefrLevel = "B2",
                contentSource = ContentSource.CORE,
                memoryState = mem(FsrsState.NEW, 0).copy(wordId = "V_001"),
            ),
            Vocabulary(
                id = "V_002", term = "depth of discharge", normalizedTerm = "depth of discharge",
                ipa = "/depθ əv dɪsˈtʃɑːrdʒ/", partOfSpeech = "n.",
                chineseGloss = "放电深度", englishDefinition = "The percentage of battery capacity that has been discharged.",
                collocations = listOf("90% DoD"),
                exampleSentenceEn = "We recommend 90% depth of discharge for daily cycling.",
                exampleSentenceZh = "我们建议日常循环使用90%放电深度。",
                commonMistakes = null,
                topic = "系统性能", scenarioTags = listOf("技术交流"), cefrLevel = "B1",
                contentSource = ContentSource.CORE,
                memoryState = mem(FsrsState.NEW, 0).copy(wordId = "V_002"),
            ),
            Vocabulary(
                id = "V_003", term = "auxiliary consumption", normalizedTerm = "auxiliary consumption",
                ipa = "/ɔːɡˈzɪliəri kənˈsʌmpʃən/", partOfSpeech = "n.",
                chineseGloss = "自耗电", englishDefinition = "Power consumed by auxiliary systems (HVAC, BMS, PCS idle).",
                collocations = listOf("reduce auxiliary consumption"),
                exampleSentenceEn = "Auxiliary consumption is below 2% of rated power.",
                exampleSentenceZh = "自耗电低于额定功率的2%。",
                commonMistakes = null,
                topic = "系统性能", scenarioTags = listOf("技术交流"), cefrLevel = "B2",
                contentSource = ContentSource.CORE,
                memoryState = mem(FsrsState.REVIEW, 1).copy(wordId = "V_003"),
            ),
            Vocabulary(
                id = "V_004", term = "grid-forming", normalizedTerm = "grid-forming",
                ipa = "/ɡrɪd ˈfɔːrmɪŋ/", partOfSpeech = "adj.",
                chineseGloss = "构网型", englishDefinition = "Inverter control mode that can form grid voltage and frequency.",
                collocations = listOf("grid-forming inverter"),
                exampleSentenceEn = "Grid-forming control improves weak-grid stability.",
                exampleSentenceZh = "构网型控制可提升弱电网稳定性。",
                commonMistakes = "Confuse with 'grid-following'.",
                topic = "并网与控制", scenarioTags = listOf("方案介绍"), cefrLevel = "C1",
                contentSource = ContentSource.CORE,
                memoryState = mem(FsrsState.REVIEW, 2).copy(wordId = "V_004"),
            ),
        )
    }
}
