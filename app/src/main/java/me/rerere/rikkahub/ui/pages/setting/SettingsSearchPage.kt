package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsSearchPage(vm: SettingVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val focusRequester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val entries = remember(settings.developerMode) { settingsSearchIndex(settings.developerMode) }
    val filtered = remember(entries, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                buildList {
                    add(context.getString(entry.titleRes))
                    entry.descriptionRes?.let { add(context.getString(it)) }
                    add(context.getString(entry.groupRes))
                    entry.searchTermsRes.forEach { add(context.getString(it)) }
                }.any { it.contains(q, ignoreCase = true) }
            }
        }
    }
    val filteredGroups = remember(filtered) { filtered.groupBy { it.groupRes } }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.settings_search_page_title)) },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.accessibility_clear_text),
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(50),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            )

            Box(modifier = Modifier.weight(1f)) {
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        filteredGroups.forEach { (groupRes, groupEntries) ->
                            item(key = groupRes) {
                                CardGroup(
                                    title = { Text(stringResource(groupRes)) },
                                ) {
                                    groupEntries.forEach { entry ->
                                        item(
                                            onClick = { navController.navigate(entry.route) },
                                            headlineContent = { Text(stringResource(entry.titleRes)) },
                                            supportingContent = entry.descriptionRes?.let { desc ->
                                                { Text(stringResource(desc)) }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
