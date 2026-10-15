package me.rerere.rikkahub.ui.pages.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.PagerTab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Reminder
import me.rerere.hugeicons.stroke.Upload02
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.backup.components.BackupDialog
import me.rerere.rikkahub.ui.pages.backup.components.BackupEncryptionSection
import me.rerere.rikkahub.ui.pages.backup.tabs.ImportExportTab
import me.rerere.rikkahub.ui.pages.backup.tabs.ReminderTab
import me.rerere.rikkahub.ui.pages.backup.tabs.S3Tab
import me.rerere.rikkahub.ui.pages.backup.tabs.WebDavTab
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val pagerState = rememberPagerState { 4 }
    var showRestartDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.backup_page_title))
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
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
        ) {
            val backupTabs = listOf(
                object : PagerTab {
                    override val icon: ImageVector = HugeIcons.FileImport
                    override val label: String
                        @Composable get() = stringResource(R.string.backup_page_import_export)
                },
                object : PagerTab {
                    override val icon: ImageVector = HugeIcons.Upload02
                    override val label: String
                        @Composable get() = stringResource(R.string.backup_page_webdav_backup)
                },
                object : PagerTab {
                    override val icon: ImageVector = HugeIcons.Folder01
                    override val label: String
                        @Composable get() = stringResource(R.string.backup_page_s3_backup)
                },
                object : PagerTab {
                    override val icon: ImageVector = HugeIcons.Reminder
                    override val label: String
                        @Composable get() = stringResource(R.string.backup_page_reminder)
                },
            )
            PagerTabs(
                pagerState = pagerState,
                tabs = backupTabs,
                modifier = Modifier.fillMaxWidth(),
            )

            BackupEncryptionSection(
                vm = vm,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) { page ->
                when (page) {
                    0 -> {
                        ImportExportTab(
                            vm = vm,
                            onShowRestartDialog = { showRestartDialog = true },
                        )
                    }

                    1 -> {
                        WebDavTab(
                            vm = vm,
                            onShowRestartDialog = { showRestartDialog = true },
                        )
                    }

                    2 -> {
                        S3Tab(
                            vm = vm,
                            onShowRestartDialog = { showRestartDialog = true },
                        )
                    }

                    3 -> {
                        ReminderTab(vm = vm)
                    }
                }
            }
        }
    }

    if (showRestartDialog) {
        BackupDialog(onDismiss = { showRestartDialog = false })
    }
}
