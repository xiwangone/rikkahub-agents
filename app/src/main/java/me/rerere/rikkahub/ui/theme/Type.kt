package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import me.rerere.rikkahub.R

val Typography = MaterialTypography()

@OptIn(ExperimentalTextApi::class)
val JetbrainsMono =
    FontFamily(
        Font(
            resId = R.font.jetbrains_mono,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(FontWeight.Normal.weight),
                ),
        ),
    )
