package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Test

class AskPromptCardTest {
    @Test fun customTextOverridesSelectedOptionWhenAllowed() {
        assertEquals("custom", resolveAskSingleAnswer(true, "option", "custom"))
    }
    @Test fun disallowedCustomTextDoesNotReplaceSelectedOption() {
        assertEquals("option", resolveAskSingleAnswer(false, "option", "stale"))
    }
    @Test fun customTextCanAnswerWithoutSelectingAnOption() {
        assertEquals("free response", resolveAskSingleAnswer(true, null, "free response"))
    }
}
