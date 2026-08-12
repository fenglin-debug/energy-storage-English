package com.bess.salestrainer

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavigationContainsOnlyVocabularyScenarioAndArticle() {
        composeRule.onNode(hasContentDescription("词汇")).assertExists()
        composeRule.onNode(hasContentDescription("情景")).assertExists()
        composeRule.onNode(hasContentDescription("文章")).assertExists()
        composeRule.onNode(hasContentDescription("例句")).assertDoesNotExist()
    }
}
