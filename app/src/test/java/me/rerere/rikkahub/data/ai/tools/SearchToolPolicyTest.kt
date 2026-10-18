package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchToolPolicyTest {
    @Test fun externalSearchOnlyWhenEnabledWithoutNativeSearch() {
        assertTrue(shouldUseExternalWebSearch(true, Model()))
        assertFalse(shouldUseExternalWebSearch(false, Model()))
        assertFalse(shouldUseExternalWebSearch(true, Model(tools = setOf(BuiltInTools.Search))))
    }
}
