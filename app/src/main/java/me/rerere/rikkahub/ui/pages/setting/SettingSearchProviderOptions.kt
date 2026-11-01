package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import me.rerere.highlight.LocalCodeHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.search.SearchServiceOptions


@Composable
internal fun PerplexityOptions(
    options: SearchServiceOptions.PerplexityOptions,
    onUpdateOptions: (SearchServiceOptions.PerplexityOptions) -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        },
    ) {
        SecretRefField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions(options.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${options.typeKey.uppercase()}_APIKEY",
            singleLine = true,
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens))
        },
    ) {
        OutlinedTextField(
            value = options.maxTokens?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions(options.copy(maxTokens = value.toIntOrNull()))
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens_per_page))
        },
    ) {
        OutlinedTextField(
            value = options.maxTokensPerPage?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions(options.copy(maxTokensPerPage = value.toIntOrNull()))
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
internal fun FirecrawlOptions(
    options: SearchServiceOptions.FirecrawlOptions,
    onUpdateOptions: (SearchServiceOptions.FirecrawlOptions) -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        },
    ) {
        SecretRefField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions(options.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${options.typeKey.uppercase()}_APIKEY",
            singleLine = true,
        )
    }
}

@Composable
internal fun JinaOptions(
    options: SearchServiceOptions.JinaOptions,
    onUpdateOptions: (SearchServiceOptions.JinaOptions) -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        },
    ) {
        SecretRefField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions(options.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${options.typeKey.uppercase()}_APIKEY",
            singleLine = true,
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_url))
        },
    ) {
        OutlinedTextField(
            value = options.searchUrl,
            onValueChange = {
                onUpdateOptions(options.copy(searchUrl = it.trim()))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://s.jina.ai/")
            },
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_url))
        },
    ) {
        OutlinedTextField(
            value = options.scrapeUrl,
            onValueChange = {
                onUpdateOptions(options.copy(scrapeUrl = it.trim()))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://r.jina.ai/")
            },
        )
    }
}

@Composable
internal fun BochaOptions(
    options: SearchServiceOptions.BochaOptions,
    onUpdateOptions: (SearchServiceOptions.BochaOptions) -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        },
    ) {
        SecretRefField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions(options.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${options.typeKey.uppercase()}_APIKEY",
            singleLine = true,
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_summary))
        },
        description = {
            Text(stringResource(R.string.search_detail_summary_desc))
        },
        tail = {
            Switch(
                checked = options.summary,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(summary = checked))
                },
            )
        },
    )
}

@Composable
internal fun RikkaHubOptions(
    options: SearchServiceOptions.RikkaHubOptions,
    onUpdateOptions: (SearchServiceOptions.RikkaHubOptions) -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        },
    ) {
        SecretRefField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions(options.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${options.typeKey.uppercase()}_APIKEY",
            singleLine = true,
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        },
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(options.copy(depth = depth))
                    },
                    selected = options.depth == depth,
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun TinyfishOptions(
    options: SearchServiceOptions.TinyfishOptions,
    onUpdateOptions: (SearchServiceOptions.TinyfishOptions) -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        },
    ) {
        SecretRefField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions(options.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${options.typeKey.uppercase()}_APIKEY",
            singleLine = true,
        )
    }
}

@Composable
internal fun GrokOptions(
    options: SearchServiceOptions.GrokOptions,
    onUpdateOptions: (SearchServiceOptions.GrokOptions) -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        },
    ) {
        SecretRefField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions(options.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${options.typeKey.uppercase()}_APIKEY",
            singleLine = true,
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_model))
        },
    ) {
        OutlinedTextField(
            value = options.model,
            onValueChange = {
                onUpdateOptions(options.copy(model = it))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_custom_url))
        },
    ) {
        OutlinedTextField(
            value = options.customUrl,
            onValueChange = {
                onUpdateOptions(options.copy(customUrl = it))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_system_prompt))
        },
    ) {
        OutlinedTextField(
            value = options.systemPrompt,
            onValueChange = {
                onUpdateOptions(options.copy(systemPrompt = it))
            },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun DeepSeekOptions(
    options: SearchServiceOptions.DeepSeekOptions,
    onUpdateOptions: (SearchServiceOptions.DeepSeekOptions) -> Unit,
) {
    FormItem(label = { Text(stringResource(R.string.search_detail_api_key)) }) {
        SecretRefField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions(options.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${options.typeKey.uppercase()}_APIKEY",
            singleLine = true,
        )
    }
}

@Composable
internal fun CustomJsOptions(
    options: SearchServiceOptions.CustomJsOptions,
    onUpdateOptions: (SearchServiceOptions.CustomJsOptions) -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_name))
        },
    ) {
        OutlinedTextField(
            value = options.name,
            onValueChange = {
                onUpdateOptions(options.copy(name = it))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_detail_custom_search_placeholder)) },
        )
    }

    val highlighter = LocalCodeHighlighter.current
    val darkMode = LocalDarkMode.current

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_script))
        },
    ) {
        OutlinedTextField(
            value = options.searchScript,
            onValueChange = {
                onUpdateOptions(options.copy(searchScript = it))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 20,
            visualTransformation =
                HighlightCodeVisualTransformation(
                    language = "javascript",
                    highlighter = highlighter,
                    darkMode = darkMode,
                ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_script))
        },
        description = {
            Text(stringResource(R.string.search_detail_scrape_script_desc))
        },
    ) {
        OutlinedTextField(
            value = options.scrapeScript,
            onValueChange = {
                onUpdateOptions(options.copy(scrapeScript = it))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 20,
            placeholder = {
                Text(
                    text = SearchServiceOptions.CustomJsOptions.DEFAULT_SCRAPE_SCRIPT.trimIndent(),
                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            visualTransformation =
                HighlightCodeVisualTransformation(
                    language = "javascript",
                    highlighter = highlighter,
                    darkMode = darkMode,
                ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }
}
