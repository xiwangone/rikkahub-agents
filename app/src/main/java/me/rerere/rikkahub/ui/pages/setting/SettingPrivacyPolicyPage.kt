package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

@Composable
fun SettingPrivacyPolicyPage() {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.privacy_policy_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.privacy_policy_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            item {
                PrivacyPolicySection(
                    titleRes = R.string.privacy_policy_data_title,
                    bodyRes = R.string.privacy_policy_data_body,
                )
            }
            item {
                PrivacyPolicySection(
                    titleRes = R.string.privacy_policy_network_title,
                    bodyRes = R.string.privacy_policy_network_body,
                )
            }
            item {
                PrivacyPolicySection(
                    titleRes = R.string.privacy_policy_storage_title,
                    bodyRes = R.string.privacy_policy_storage_body,
                )
            }
            item {
                PrivacyPolicySection(
                    titleRes = R.string.privacy_policy_permissions_title,
                    bodyRes = R.string.privacy_policy_permissions_body,
                )
            }
            item {
                PrivacyPolicySection(
                    titleRes = R.string.privacy_policy_source_title,
                    bodyRes = R.string.privacy_policy_source_body,
                )
            }
        }
    }
}

@Composable
private fun PrivacyPolicySection(
    titleRes: Int,
    bodyRes: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
