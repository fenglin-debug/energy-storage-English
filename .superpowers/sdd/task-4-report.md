# Task 4 Report: 词库搜索 + 词条详情

## Status

**DONE**

## What was implemented

Restored the vocabulary tab as a single browse page (today summary + search + chips + list) and added an independent word-detail route. Practice recall-first / change-before-advance / FSRS timing were not touched.

1. **`VocabularyScreen`**
   - Removed `VocabularyHomeContent(...); return` and the three identical unreachable empty states.
   - Single column, top to bottom:
     1. Today summary: `新内容 X · 到期 Y` + “开始背单词” (enabled from `homeState`, same queue gate as before).
     2. `OutlinedTextField` search (term / gloss / alias; repository already filters).
     3. Chips: 未学 / 学习中 / 已掌握 / 到期 / 收藏 (`MasteryFilter` + `dueOnly` + `favoritesOnly`). Mastery chips are exclusive; tapping the selected chip returns to `ALL`.
     4. `LazyColumn` rows; whole `Card` is `clickable { onOpenWord(word.id) }`; favorite `IconButton` does not open detail.
     5. Filtered empty: “没有符合条件的词” + “清除筛选”. Unfiltered empty: single “正在准备离线学习内容，请稍候”.
   - Signature now includes `onOpenWord: (String) -> Unit`.

2. **`VocabularyDetailScreen` + `VocabularyDetailViewModel`**
   - Independent `@HiltViewModel` + `SavedStateHandle["wordId"]` so browse/play does not share practice session state.
   - `bindWordId` covers the composable argument if the handle is empty.
   - Shows term, IPA, POS, Chinese, collocations, EN/ZH examples (plus extra linked examples), common mistakes, topic, CEFR, favorite, word/example playback. All visible (browser, not a quiz).

3. **Navigation**
   - `Routes.VOCAB_DETAIL = "vocabulary/detail/{wordId}"`
   - `Routes.vocabDetail(wordId)`
   - Tab wires `onOpenWord`; detail composable pops back.

4. **`VocabularyViewModel`**
   - Added `clearFilter()` only. No practice / FSRS / reveal changes.

## What was tested

| Suite | Result |
|---|---|
| `:core:data:testDebugUnitTest --tests …VocabularyRepositoryImplTest.vocabularyFilterByMasteryAndQuery` | PASS (1 test) |
| `:feature:vocabulary:testDebugUnitTest --tests …VocabularyFilterChipLogicTest` | PASS (3 tests) |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

Chip-logic cases:

- `tappingSameMasteryClearsFilter` — LEARNING + LEARNING → ALL
- `tappingDifferentMasterySelectsIt` — LEARNING + MASTERED → MASTERED
- `queryAndChipsCountAsActiveConstraints` — empty filter is inactive; query / mastery / due / favorite are active

## TDD Evidence

### GREEN (existing filter contract kept)

Command:

```powershell
$env:JAVA_HOME = 'C:\Users\fengl\jdk-17'
.\gradlew.bat :core:data:testDebugUnitTest --tests com.bess.salestrainer.core.data.repository.VocabularyRepositoryImplTest.vocabularyFilterByMasteryAndQuery
.\gradlew.bat :app:assembleDebug
```

Result:

```
:core:data:testDebugUnitTest --tests …vocabularyFilterByMasteryAndQuery
BUILD SUCCESSFUL

:app:assembleDebug
BUILD SUCCESSFUL in 49s
```

## Files

- `feature/vocabulary/src/main/java/com/bess/salestrainer/feature/vocabulary/VocabularyScreen.kt`
- `feature/vocabulary/src/main/java/com/bess/salestrainer/feature/vocabulary/VocabularyViewModel.kt`
- `feature/vocabulary/src/main/java/com/bess/salestrainer/feature/vocabulary/VocabularyDetailScreen.kt`
- `feature/vocabulary/src/test/java/com/bess/salestrainer/feature/vocabulary/VocabularyFilterChipLogicTest.kt`
- `app/src/main/java/com/bess/salestrainer/navigation/BessNavHost.kt`

## Commit

`9268dfb` — `feat(vocab): restore browser search and word detail`

## Self-review

- Practice `VocabularyPracticeScreen` / reveal / assessment / advance untouched.
- FSRS / `submitAssessment` / `advanceToNext` untouched.
- No Home tab, sentence tab, or Room version change.
- Detail uses its own ViewModel so it cannot overwrite `vocab_session_id`.
- Favorite `IconButton` consumes the click; row `Card` opens detail.
- Empty-state branches are one `when`, not three copies of the preparing message.
- `PUBLIC_INTERFACE_IMPACT`: new route `vocabulary/detail/{wordId}` and `Routes.vocabDetail`; `VocabularyScreen` gained `onOpenWord`.

## Concerns

- No Compose UI test for search, chips, row click vs favorite, or TalkBack on the practice card.
- Word IDs with `/` would break the string route; current corpus ids are `V_*` style.
- Favoriting a never-studied word still creates a NEW memory row (existing repository behavior).
- Pre-existing Compose deprecation warnings: `hiltViewModel` package move, `Icons.Filled.ArrowBack` / `VolumeUp`.

---

## Fix: weighted height for browser list and detail (review finding)

### What changed

In multi-child `Column` layouts, list / empty / loading regions used `Modifier.fillMaxSize()`, which took the full parent height and clipped bottom content under summary / search / chips (or the detail toolbar).

- **`VocabularyScreen.kt`**: loading `Column`, filtered-empty `Column`, unfiltered-empty `Column`, and word `LazyColumn` now use `Modifier.weight(1f).fillMaxWidth()` (same pattern as practice).
- **`VocabularyDetailScreen.kt`**: detail content `LazyColumn` now uses `Modifier.weight(1f).fillMaxWidth()` under the toolbar `Row`.

No navigation, filter, or practice logic changes.

### Tests

```powershell
$env:JAVA_HOME = 'C:\Users\fengl\jdk-17'
.\gradlew.bat :feature:vocabulary:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

| Suite | Result |
|---|---|
| `:feature:vocabulary:testDebugUnitTest` | BUILD SUCCESSFUL in 23s |
| `:app:assembleDebug` | BUILD SUCCESSFUL in 6s |

### Commit

`fix(vocab): give browser list and detail a weighted height`
