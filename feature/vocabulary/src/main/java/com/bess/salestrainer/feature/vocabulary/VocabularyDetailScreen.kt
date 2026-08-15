package com.bess.salestrainer.feature.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.contract.AudioPlaybackRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import com.bess.salestrainer.core.model.contract.VocabularyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VocabularyDetailUiState(
    val word: Vocabulary? = null,
    val loading: Boolean = true,
    val missing: Boolean = false,
)

/** Independent of practice session state so browse/play does not steal the queue. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VocabularyDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val vocabularyRepository: VocabularyRepository,
    private val audioPlaybackRepository: AudioPlaybackRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<VocabularyDetailUiState> =
        savedStateHandle.getStateFlow("wordId", "")
            .flatMapLatest { id ->
                if (id.isBlank()) {
                    flowOf(VocabularyDetailUiState(loading = false, missing = true))
                } else {
                    vocabularyRepository.observeWord(id)
                        .map { VocabularyDetailUiState(word = it, loading = false) }
                        .catch { emit(VocabularyDetailUiState(loading = false, missing = true)) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VocabularyDetailUiState())

    fun bindWordId(wordId: String) {
        if (wordId.isNotBlank() && savedStateHandle.get<String>("wordId") != wordId) {
            savedStateHandle["wordId"] = wordId
        }
    }

    fun toggleFavorite() {
        val current = uiState.value.word ?: return
        viewModelScope.launch {
            vocabularyRepository.setFavorite(
                current.id,
                !(current.memoryState?.isFavorite ?: false),
            )
        }
    }

    fun playWordAudio() {
        playAsset(uiState.value.word?.wordAudioAssetId)
    }

    fun playExampleAudio(assetId: String) = playAsset(assetId)

    private fun playAsset(assetId: String?) {
        if (assetId.isNullOrBlank()) return
        viewModelScope.launch {
            val settings = settingsRepository.observeSettings().first()
            audioPlaybackRepository.play(assetId, settings.playbackSpeed)
        }
    }
}

@Composable
fun VocabularyDetailScreen(
    wordId: String,
    onBack: () -> Unit,
    viewModel: VocabularyDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(wordId) { viewModel.bindWordId(wordId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.loading -> {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
        }
        state.missing || state.word == null -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("找不到这个词", style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp),
                ) { Text("返回") }
            }
        }
        else -> {
            WordDetailContent(
                word = requireNotNull(state.word),
                onBack = onBack,
                onToggleFavorite = viewModel::toggleFavorite,
                onPlayWord = viewModel::playWordAudio,
                onPlayExample = viewModel::playExampleAudio,
            )
        }
    }
}

@Composable
private fun WordDetailContent(
    word: Vocabulary,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayWord: () -> Unit,
    onPlayExample: (String) -> Unit,
) {
    val favorite = word.memoryState?.isFavorite ?: false
    val extraExamples = word.examples.filter { it.textEn != word.exampleSentenceEn }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                "词条详情",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.semantics {
                    contentDescription = if (favorite) "取消收藏 ${word.term}" else "收藏 ${word.term}"
                },
            ) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(word.term, style = MaterialTheme.typography.headlineMedium)
                if (word.ipa.isNotBlank()) {
                    Text(
                        word.ipa,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    listOf(word.partOfSpeech, word.cefrLevel, word.topic)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                DetailSection("中文", word.chineseGloss)
            }
            if (word.collocations.isNotEmpty()) {
                item {
                    DetailSection("搭配", word.collocations.joinToString(" · "))
                }
            }
            item {
                Text("例句", style = MaterialTheme.typography.titleSmall)
                Text(word.exampleSentenceEn, style = MaterialTheme.typography.bodyLarge)
                word.exampleSentenceZh?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            extraExamples.forEach { example ->
                item {
                    Text(example.textEn, style = MaterialTheme.typography.bodyLarge)
                    if (example.textZh.isNotBlank()) {
                        Text(example.textZh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (example.audioAssetId.isNotBlank()) {
                        PlayAudioButton("朗读例句") { onPlayExample(example.audioAssetId) }
                    }
                }
            }
            if (word.commonMistakes.isNotBlank()) {
                item {
                    DetailSection("易错点", word.commonMistakes)
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayAudioButton("朗读单词", Modifier.weight(1f), onPlayWord)
                    PlayAudioButton("朗读例句", Modifier.weight(1f)) {
                        onPlayExample(word.exampleAudioAssetId)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PlayAudioButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.VolumeUp, contentDescription = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
