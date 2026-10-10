package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import me.rerere.rikkahub.R

object ExtendedFontVariation {
    /**
     * Typographic feature axis for (ROND) variations
     *
     * [OpenType Variable Axes Definition](https://fonts.google.com/variablefonts#axis-definitions)
     *
     * @param value Round axis, in 0..100
     **/
    fun round(value: Int): FontVariation.Setting {
        val featureTagType = "ROND"

        require(value in 0..100) { "Google Sans Flex 'Round' axis must be in 0..100" }
        return FontVariation.Setting(featureTagType, value.toFloat())
    }
}

// Thanks https://gitlab.com/nongthaihoang/google-sans-prime/-/commit/0f7b9d29f6ffe5005d22d81af264a86106f2450d
object GoogleSansFlex {
    object Display {
        object Normal {
            /**
             * Google Sans Flex Normal (400), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displayLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displayLarge.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Normal (400), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displayMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displayMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Normal (400), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displaySmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displaySmall.fontSize),
                            ),
                    ),
                )
        }

        object Emphasized {
            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displayLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displayLarge.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displayMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displayMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displaySmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.displaySmall.fontSize),
                            ),
                    ),
                )
        }
    }

    object Headline {
        object Normal {
            /**
             * Google Sans Flex Normal (400), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineLarge.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Normal (400), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Normal (400), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineSmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineSmall.fontSize),
                            ),
                    ),
                )
        }

        object Emphasized {
            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineLarge.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineSmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.headlineSmall.fontSize),
                            ),
                    ),
                )
        }
    }

    object Title {
        object Normal {
            /**
             * Google Sans Flex Medium (500), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleLarge.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleSmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleSmall.fontSize),
                            ),
                    ),
                )
        }

        object Emphasized {
            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleLarge.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleSmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.titleSmall.fontSize),
                            ),
                    ),
                )
        }
    }

    object Body {
        object Normal {
            /**
             * Google Sans Flex Normal (400), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodyLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodyLarge.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Normal (400), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodyMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodyMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Normal (400), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodySmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Normal.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodySmall.fontSize),
                            ),
                    ),
                )
        }

        object Emphasized {
            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodyLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodyLarge.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodyMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodyMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodySmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.bodySmall.fontSize),
                            ),
                    ),
                )
        }
    }

    object Label {
        object Normal {
            /**
             * Google Sans Flex Medium (500), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelSmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelSmall.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Medium (500), Normal
             *
             * Fallback to Google Sans for unsupported glyphs
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.Medium.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelLarge.fontSize),
                            ),
                    ),
                )
        }

        object Emphasized {
            /**
             * Google Sans Flex Semibold (600), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Small =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.SemiBold.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelSmall.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.SemiBold.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelSmall.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Semibold (600), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Medium =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.SemiBold.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelMedium.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.SemiBold.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelMedium.fontSize),
                            ),
                    ),
                )

            /**
             * Google Sans Flex Semibold (600), Emphasized
             *
             * Fallback to Google Sans for unsupported glyphs (no round variation settings)
             **/
            @OptIn(ExperimentalTextApi::class)
            val Large =
                FontFamily(
                    Font(
                        R.font.google_sans_flex,
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.SemiBold.weight),
                                ExtendedFontVariation.round(100),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelLarge.fontSize),
                            ),
                    ),
                    Font(
                        R.font.google_sans_flex, // Google Sans
                        variationSettings =
                            FontVariation.Settings(
                                FontVariation.weight(FontWeight.SemiBold.weight),
                                FontVariation.width(100f),
                                FontVariation.grade(0),
                                FontVariation.opticalSizing(Typography.labelLarge.fontSize),
                            ),
                    ),
                )
        }
    }
}
