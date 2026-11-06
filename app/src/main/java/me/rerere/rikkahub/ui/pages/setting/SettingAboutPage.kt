package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.easteregg.EmojiBurstHost
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.drawAppLogo
import me.rerere.rikkahub.ui.components.ui.logoMorphShape
import me.rerere.rikkahub.ui.components.ui.rememberAppLogoLayers
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus

@Suppress("SpreadOperator")
@Composable
fun SettingAboutPage() {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val logoLayers = rememberAppLogoLayers()
    val logoContentDescription = stringResource(R.string.accessibility_app_logo)
    val emojiOptions =
        remember {
            listOf(
                "🧧", "🏮", "🐉", "🐲", "🎋", "🪷", "🀄", "☯️",
                "🥮", "🥟", "🍜", "🍵", "🍡", "🍊", "🥠", "🎐",
                "🐼", "🎆", "🎇", "🧨", "🎊", "✨", "🌸", "🏵️",
                "🔥", "⭐", "🪭", "🌙",
            )
        }
    var logoCenterPx by remember { mutableStateOf(Offset.Zero) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.about_page_title))
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
        EmojiBurstHost(
            modifier = Modifier.fillMaxSize(),
            emojiOptions = emojiOptions,
            burstCount = 12,
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    val logoScale by animateFloatAsState(
                        targetValue = if (pressed) 0.88f else 1f,
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    )
                    var shapeIndex by remember { mutableIntStateOf(0) }
                    val shapePosition by animateFloatAsState(
                        targetValue = shapeIndex.toFloat(),
                        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
                    )
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(116.dp)
                                    .semantics { contentDescription = logoContentDescription }
                                    .onGloballyPositioned { coordinates ->
                                        val position = coordinates.positionInParent()
                                        val size = coordinates.size
                                        logoCenterPx =
                                            Offset(
                                                position.x + size.width / 2f,
                                                position.y + size.height / 2f,
                                            )
                                    }.graphicsLayer {
                                        scaleX = logoScale
                                        scaleY = logoScale
                                        shape = logoMorphShape(shapePosition)
                                        clip = true
                                    }.clickable(
                                        interactionSource = interactionSource,
                                        indication = LocalIndication.current,
                                    ) {
                                        onBurst(logoCenterPx)
                                        shapeIndex++
                                    }.drawAppLogo(logoLayers),
                        )

                        Text(
                            text = stringResource(R.string.app_name),
                            style =
                                MaterialTheme.typography.headlineLargeEmphasized.copy(
                                    fontWeight = FontWeight.Black,
                                ),
                        )
                    }
                }

                item {
                    val containerColor = MaterialTheme.colorScheme.primaryContainer
                    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    CardGroup {
                        item(
                            modifier =
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { navController.navigate(Screen.Debug) },
                                ),
                            leadingContent = { CardGroupIcon(HugeIcons.Code, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = {
                                Text("${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_version)) },
                        )
                        item(
                            leadingContent = { CardGroupIcon(HugeIcons.SmartPhone01, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = {
                                Text(
                                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}",
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_system)) },
                        )
                    }
                }

                item {
                    val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    CardGroup {
                        item(
                            onClick = { context.openUrl("https://github.com/xiwangone/rikkahub-agents") },
                            leadingContent = { CardGroupIcon(HugeIcons.Github, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = { Text("https://github.com/xiwangone/rikkahub-agents") },
                            headlineContent = { Text(stringResource(R.string.about_ai_maintained)) },
                        )
                        item(
                            onClick = { context.openUrl("https://rikka-ai.com/") },
                            leadingContent = { CardGroupIcon(HugeIcons.Earth, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = { Text("https://rikka-ai.com") },
                            headlineContent = { Text(stringResource(R.string.about_page_website)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/rikkahub/rikkahub") },
                            leadingContent = { CardGroupIcon(HugeIcons.Github, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = { Text("https://github.com/rikkahub/rikkahub") },
                            headlineContent = { Text(stringResource(R.string.about_official)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/ExTV/rikkahub-agent") },
                            leadingContent = { CardGroupIcon(HugeIcons.Github, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = { Text("https://github.com/ExTV/rikkahub-agent") },
                            headlineContent = { Text(stringResource(R.string.about_extv_fork)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/rikkahub/rikkahub/blob/master/LICENSE") },
                            leadingContent = { CardGroupIcon(HugeIcons.File02, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = { Text("https://github.com/rikkahub/rikkahub/blob/master/LICENSE") },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                        )
                    }
                }
            }
        }
    }
}
