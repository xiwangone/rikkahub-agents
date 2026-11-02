package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Notification02
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

private val ReminderIntervals = listOf(1, 3, 7, 14, 30)

@Composable
fun ReminderTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.backupReminderConfig

    fun updateConfig(update: BackupReminderConfig) {
        vm.updateSettings { current -> current.copy(backupReminderConfig = update) }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup(
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(
                leadingContent = { CardGroupIcon(HugeIcons.Sparkles) },
                headlineContent = { Text(stringResource(R.string.backup_page_assistant_hint)) },
            )
        }
        CardGroup(
            modifier = Modifier.fillMaxWidth(),
        ) {
            switchItem(
                checked = config.enabled,
                onCheckedChange = { updateConfig(config.copy(enabled = it)) },
                leadingContent = { CardGroupIcon(HugeIcons.Notification02) },
                headlineContent = { Text(stringResource(R.string.backup_page_reminder_enable)) },
            )

            if (config.enabled) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_reminder_interval)) },
                    trailingContent = {
                        CardGroupValue(
                            stringResource(R.string.backup_page_reminder_interval_days, config.intervalDays),
                        )
                    },
                ) {
                    ConnectedChoiceButtons(
                        options = ReminderIntervals,
                        selected = config.intervalDays,
                        onSelect = { updateConfig(config.copy(intervalDays = it)) },
                    ) { days, selected ->
                        ConnectedChoiceLabel(text = days.toString(), selected = selected)
                    }
                }

                item(
                    leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                    headlineContent = {
                        Text(
                            if (config.lastBackupTime == 0L) {
                                stringResource(R.string.backup_page_reminder_no_record)
                            } else {
                                stringResource(
                                    R.string.backup_page_reminder_last_time,
                                    Instant.ofEpochMilli(config.lastBackupTime).toLocalDateTime(),
                                )
                            },
                        )
                    },
                )
            }
        }
    }
}
