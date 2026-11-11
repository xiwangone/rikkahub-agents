package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.data.vault.withResolvedVaultCredentials
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.search.DoubaoSearchMode
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingSearchDetailPage(
    serviceId: Uuid,
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val nav = LocalNavController.current

    val service = settings.searchServices.find { it.id == serviceId } ?: return
    val serviceIndex = settings.searchServices.indexOf(service)
    var options by remember(service) { mutableStateOf(service) }

    fun save(updated: SearchServiceOptions) {
        options = updated
        vm.updateSettings { current ->
            current.copy(searchServices = current.searchServices.map { service ->
                if (service.id == serviceId) updated else service
            })
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(options.displayName)
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (settings.searchServices.size > 1) {
                        IconButton(
                            onClick = {
                                vm.updateSettings { current ->
                                    if (current.searchServices.size > 1) {
                                        current.copy(searchServices = current.searchServices.filterNot { it.id == serviceId })
                                    } else current
                                }
                                nav.popBackStack()
                            },
                        ) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("config") {
                CardGroup(title = { Text(stringResource(R.string.setting_page_search_config)) }) {
                    formItem {
                        SearchServiceOptionsEditor(
                            options = options,
                            onUpdateOptions = { save(it) },
                        )

                        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                            SearchService.getService(options).Description()
                        }
                    }
                }
            }

            item("test") {
                SearchTestSection(
                    options = options,
                    commonOptions = settings.searchCommonOptions,
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun SearchServiceOptionsEditor(
    options: SearchServiceOptions,
    onUpdateOptions: (SearchServiceOptions) -> Unit,
) {
    when (options) {
        is SearchServiceOptions.TavilyOptions -> {
            TavilyOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.ExaOptions -> {
            ExaOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.ZhipuOptions -> {
            ZhipuOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.DoubaoOptions -> {
            DoubaoOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.SearXNGOptions -> {
            SearXNGOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.LinkUpOptions -> {
            SearchLinkUpOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.BraveOptions -> {
            BraveOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.MetasoOptions -> {
            MetasoOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.OllamaOptions -> {
            OllamaOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.PerplexityOptions -> {
            PerplexityOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.BingLocalOptions -> {}
        is SearchServiceOptions.DuckDuckGoOptions -> {}
        is SearchServiceOptions.FirecrawlOptions -> {
            FirecrawlOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.JinaOptions -> {
            JinaOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.BochaOptions -> {
            BochaOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.RikkaHubOptions -> {
            RikkaHubOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.GrokOptions -> {
            GrokOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.TinyfishOptions -> {
            TinyfishOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.SerperOptions -> {
            SerperOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.DeepSeekOptions -> {
            DeepSeekOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.CustomJsOptions -> {
            CustomJsOptions(options) { onUpdateOptions(it) }
        }
    }
}

@Composable
private fun SearchTestSection(
    options: SearchServiceOptions,
    commonOptions: SearchCommonOptions,
) {
    var query by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<SearchResult>?>(null) }
    val scope = rememberCoroutineScope()

    CardGroup(
        title = { Text(stringResource(R.string.setting_page_search_test)) },
    ) {
        formItem {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.setting_page_search_test_query_hint)) },
                    singleLine = true,
                )

                IconButton(
                    onClick = {
                        if (query.isNotBlank() && !testing) {
                            testing = true
                            result = null
                            scope.launch {
                                val resolvedOptions = options.withResolvedVaultCredentials()
                                val service = SearchService.getService(resolvedOptions)
                                val params =
                                    JsonObject(
                                        mapOf("query" to JsonPrimitive(query)),
                                    )
                                result = service.search(params, commonOptions, resolvedOptions)
                                testing = false
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !testing,
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = HugeIcons.Play,
                            contentDescription = stringResource(R.string.setting_page_search_test_run),
                        )
                    }
                }
            }
        }

        result?.let { res ->
            res.onSuccess { searchResult ->
                searchResult.answer?.let { answer ->
                    item(
                        headlineContent = {
                            Text(
                                text = answer,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
                searchResult.items.forEachIndexed { index, item ->
                    item(
                        headlineContent = {
                            Text(
                                text = "${index + 1}. ${item.title}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = item.text.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }
            res.onFailure { error ->
                item(
                    headlineContent = {
                        Text(
                            text = error.message ?: stringResource(R.string.search_detail_unknown_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun TavilyOptions(
    options: SearchServiceOptions.TavilyOptions,
    onUpdateOptions: (SearchServiceOptions.TavilyOptions) -> Unit,
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
        val depthOptions = listOf("basic", "advanced")
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
    FormItem(
        label = { Text(stringResource(R.string.search_detail_tavily_include_answer)) },
        description = { Text(stringResource(R.string.search_detail_tavily_include_answer_desc)) },
        tail = {
            Switch(
                checked = options.includeAnswer,
                onCheckedChange = { checked -> onUpdateOptions(options.copy(includeAnswer = checked)) },
            )
        },
    )
}

@Composable
internal fun ExaOptions(
    options: SearchServiceOptions.ExaOptions,
    onUpdateOptions: (SearchServiceOptions.ExaOptions) -> Unit,
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
internal fun ZhipuOptions(
    options: SearchServiceOptions.ZhipuOptions,
    onUpdateOptions: (SearchServiceOptions.ZhipuOptions) -> Unit,
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
internal fun DoubaoOptions(
    options: SearchServiceOptions.DoubaoOptions,
    onUpdateOptions: (SearchServiceOptions.DoubaoOptions) -> Unit,
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

    FormItem(label = { Text(stringResource(R.string.search_detail_mode)) }) {
        val modes = DoubaoSearchMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    onClick = { onUpdateOptions(options.copy(mode = mode)) },
                    selected = options.mode == mode,
                ) {
                    Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                }
            }
        }
    }
}

@Composable
internal fun SearXNGOptions(
    options: SearchServiceOptions.SearXNGOptions,
    onUpdateOptions: (SearchServiceOptions.SearXNGOptions) -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_url))
        },
    ) {
        OutlinedTextField(
            value = options.url,
            onValueChange = {
                onUpdateOptions(options.copy(url = it))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_engines))
        },
    ) {
        OutlinedTextField(
            value = options.engines,
            onValueChange = {
                onUpdateOptions(options.copy(engines = it))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_language))
        },
    ) {
        OutlinedTextField(
            value = options.language,
            onValueChange = {
                onUpdateOptions(options.copy(language = it))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_username))
        },
    ) {
        OutlinedTextField(
            value = options.username,
            onValueChange = {
                onUpdateOptions(options.copy(username = it))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_password))
        },
    ) {
        SecretRefField(
            value = options.password,
            onValueChange = { onUpdateOptions(options.copy(password = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${options.typeKey.uppercase()}_PASSWORD",
            singleLine = true,
        )
    }
}

@Composable
internal fun SearchLinkUpOptions(
    options: SearchServiceOptions.LinkUpOptions,
    onUpdateOptions: (SearchServiceOptions.LinkUpOptions) -> Unit,
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
internal fun BraveOptions(
    options: SearchServiceOptions.BraveOptions,
    onUpdateOptions: (SearchServiceOptions.BraveOptions) -> Unit,
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
internal fun SerperOptions(
    options: SearchServiceOptions.SerperOptions,
    onUpdateOptions: (SearchServiceOptions.SerperOptions) -> Unit,
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
internal fun MetasoOptions(
    options: SearchServiceOptions.MetasoOptions,
    onUpdateOptions: (SearchServiceOptions.MetasoOptions) -> Unit,
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
internal fun OllamaOptions(
    options: SearchServiceOptions.OllamaOptions,
    onUpdateOptions: (SearchServiceOptions.OllamaOptions) -> Unit,
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

