package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model

/** Avoid running both provider-native search and separately injected search tools. */
internal fun shouldUseExternalWebSearch(assistantEnabled: Boolean, model: Model): Boolean =
    assistantEnabled && BuiltInTools.Search !in model.tools
