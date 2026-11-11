package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.asr.ASRProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingSpeechPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingTTSProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }
    var editingASRProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    var selectedPage by remember { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.speech_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (selectedPage == 0) {
                        AddTTSProviderButton {
                            vm.updateSettings { current ->
            current.copy(
                                    ttsProviders = listOf(it) + current.ttsProviders,
                                )
        }
                        }
                    } else {
                        AddASRProviderButton {
                            vm.updateSettings { current ->
            current.copy(
                                    asrProviders = listOf(it) + current.asrProviders,
                                    selectedASRProviderId = current.selectedASRProviderId ?: it.id,
                                )
        }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor,
            ) {
                NavigationBarItem(
                    selected = selectedPage == 0,
                    onClick = { selectedPage = 0 },
                    icon = { Icon(HugeIcons.VolumeHigh, contentDescription = null) },
                    label = { Text(stringResource(R.string.speech_tab_tts)) },
                )
                NavigationBarItem(
                    selected = selectedPage == 1,
                    onClick = { selectedPage = 1 },
                    icon = { Icon(HugeIcons.Mic01, contentDescription = null) },
                    label = { Text(stringResource(R.string.speech_tab_asr)) },
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when (selectedPage) {
            0 -> {
                Column(modifier = Modifier.padding(innerPadding)) {
                    TTSPlaybackSpeedSetting(
                        speed = settings.defaultTTSPlaybackSpeed,
                        onSpeedChange = {
                            vm.updateSettings { current -> current.copy(defaultTTSPlaybackSpeed = it) }
                        },
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                    TTSProviderList(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        onEdit = { editingTTSProvider = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            1 -> {
                ASRProviderList(
                    settings = settings,
                    onUpdateSettings = vm::updateSettings,
                    onEdit = { editingASRProvider = it },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }

    // Edit TTS Provider Bottom Sheet
    editingTTSProvider?.let { provider ->
        TTSProviderEditSheet(
            provider = provider,
            onDismiss = { editingTTSProvider = null },
            onSave = { currentProvider ->
                vm.updateSettings { current ->
                    current.copy(ttsProviders = current.ttsProviders.map {
                        if (it.id == provider.id) currentProvider else it
                    })
                }
                editingTTSProvider = null
            },
        )
    }

    editingASRProvider?.let { provider ->
        ASRProviderEditSheet(
            provider = provider,
            onDismiss = { editingASRProvider = null },
            onSave = { currentProvider ->
                vm.updateSettings { current ->
                    current.copy(asrProviders = current.asrProviders.map {
                        if (it.id == provider.id) currentProvider else it
                    })
                }
                editingASRProvider = null
            },
        )
    }
}
