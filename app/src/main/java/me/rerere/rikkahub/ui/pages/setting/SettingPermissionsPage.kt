package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.permissions.PermissionInventory
import me.rerere.rikkahub.data.permissions.PermissionInventory.GrantAction
import me.rerere.rikkahub.data.permissions.PermissionInventory.Group
import me.rerere.rikkahub.data.permissions.PermissionInventory.Status
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun SettingPermissionsPage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var rows by remember { mutableStateOf(PermissionInventory.build(context)) }

    val refreshNow = {
        rows = PermissionInventory.build(context)
    }

    val runtimeRequester =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            refreshNow()
        }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshNow()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val grouped = rows.groupBy { it.group }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_permissions)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Group.values().forEach { group ->
                val rowsForGroup = grouped[group] ?: return@forEach
                if (rowsForGroup.isEmpty()) return@forEach
                CardGroup(
                    title = { Text(sectionTitle(group)) },
                ) {
                    rowsForGroup.forEach { row ->
                        val grantAction = row.grant
                        val click: (() -> Unit)? =
                            when {
                                grantAction is GrantAction.Runtime &&
                                    row.status != Status.GRANTED -> {
                                    { runtimeRequester.launch(grantAction.permission) }
                                }

                                grantAction is GrantAction.SystemSettings -> {
                                    { context.startActivity(grantAction.intent) }
                                }

                                else -> {
                                    null
                                }
                            }
                        item(
                            onClick = click,
                            leadingContent = {
                                val (icon, bg, fg) =
                                    when (row.status) {
                                        Status.GRANTED ->
                                            Triple(
                                                HugeIcons.CheckmarkCircle02,
                                                MaterialTheme.colorScheme.primaryContainer,
                                                MaterialTheme.colorScheme.onPrimaryContainer,
                                            )

                                        Status.AUTO_GRANTED ->
                                            Triple(
                                                HugeIcons.Shield01,
                                                MaterialTheme.colorScheme.secondaryContainer,
                                                MaterialTheme.colorScheme.onSecondaryContainer,
                                            )

                                        Status.DENIED ->
                                            Triple(
                                                HugeIcons.Alert01,
                                                MaterialTheme.colorScheme.errorContainer,
                                                MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                    }
                                CardGroupIcon(
                                    icon = icon,
                                    containerColor = bg,
                                    contentColor = fg,
                                )
                            },
                            headlineContent = { Text(row.label) },
                            supportingContent = { Text(row.description) },
                            trailingContent = {
                                val statusText =
                                    when (row.status) {
                                        Status.GRANTED -> {
                                            stringResource(R.string.setting_page_permissions_status_granted)
                                        }

                                        Status.AUTO_GRANTED -> {
                                            stringResource(
                                                R.string.setting_page_permissions_status_auto_granted,
                                            )
                                        }

                                        Status.DENIED -> {
                                            stringResource(R.string.setting_page_permissions_status_denied)
                                        }
                                    }
                                if (row.status == Status.GRANTED) {
                                    CardGroupValue(statusText)
                                } else {
                                    val tint =
                                        when (row.status) {
                                            Status.GRANTED -> MaterialTheme.colorScheme.primary
                                            Status.AUTO_GRANTED -> MaterialTheme.colorScheme.outline
                                            Status.DENIED -> MaterialTheme.colorScheme.error
                                        }
                                    Text(
                                        text = statusText,
                                        color = tint,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sectionTitle(group: Group): String =
    when (group) {
        Group.ServicesAndIntegrations -> {
            stringResource(R.string.setting_page_permissions_group_services)
        }

        Group.SpecialAccess -> {
            stringResource(R.string.setting_page_permissions_group_special)
        }

        Group.Runtime -> {
            stringResource(R.string.setting_page_permissions_group_runtime)
        }

        Group.AutoGranted -> {
            stringResource(R.string.setting_page_permissions_group_auto)
        }
    }
