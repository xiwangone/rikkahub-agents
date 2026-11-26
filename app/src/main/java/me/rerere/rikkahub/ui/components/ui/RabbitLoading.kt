package me.rerere.rikkahub.ui.components.ui

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    val displaySetting = LocalSettings.current.displaySetting
    val useAppIconStyleLoadingIndicator = displaySetting.useAppIconStyleLoadingIndicator
    val loadingRes = displaySetting.appIconPreset.artwork.loadingAnimRes
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()

    if (useAppIconStyleLoadingIndicator) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                ImageView(context).apply {
                    tag = loadingRes
                    val drawable = AppCompatResources.getDrawable(context, loadingRes) as? AnimatedVectorDrawable
                    setImageDrawable(drawable)
                    drawable?.setTint(primaryColor)
                    drawable?.start()
                }
            },
            update = { imageView ->
                if (imageView.tag != loadingRes) {
                    imageView.tag = loadingRes
                    val drawable = AppCompatResources.getDrawable(imageView.context, loadingRes) as? AnimatedVectorDrawable
                    imageView.setImageDrawable(drawable)
                }
                (imageView.drawable as? AnimatedVectorDrawable)?.apply {
                    setTint(primaryColor)
                    start()
                }
            },
        )
    } else {
        ContainedLoadingIndicator(
            modifier = modifier,
        )
    }
}
