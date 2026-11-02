package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingToolbarColors
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class ExtendColors(
    val red1: Color,
    val red2: Color,
    val red3: Color,
    val red4: Color,
    val red5: Color,
    val red6: Color,
    val red7: Color,
    val red8: Color,
    val red9: Color,
    val red10: Color,
    val orange1: Color,
    val orange2: Color,
    val orange3: Color,
    val orange4: Color,
    val orange5: Color,
    val orange6: Color,
    val orange7: Color,
    val orange8: Color,
    val orange9: Color,
    val orange10: Color,
    val green1: Color,
    val green2: Color,
    val green3: Color,
    val green4: Color,
    val green5: Color,
    val green6: Color,
    val green7: Color,
    val green8: Color,
    val green9: Color,
    val green10: Color,
    val blue1: Color,
    val blue2: Color,
    val blue3: Color,
    val blue4: Color,
    val blue5: Color,
    val blue6: Color,
    val blue7: Color,
    val blue8: Color,
    val blue9: Color,
    val blue10: Color,
    val purple1: Color,
    val purple2: Color,
    val purple3: Color,
    val purple4: Color,
    val purple5: Color,
    val purple6: Color,
    val purple7: Color,
    val purple8: Color,
    val purple9: Color,
    val purple10: Color,
    val gray1: Color,
    val gray2: Color,
    val gray3: Color,
    val gray4: Color,
    val gray5: Color,
    val gray6: Color,
    val gray7: Color,
    val gray8: Color,
    val gray9: Color,
    val gray10: Color,
)

fun lightExtendColors() =
    ExtendColors(
        red1 = Color(255, 242, 240),
        red2 = Color(255, 204, 199),
        red3 = Color(255, 163, 158),
        red4 = Color(255, 120, 117),
        red5 = Color(255, 77, 79),
        red6 = Color(245, 34, 45),
        red7 = Color(207, 19, 34),
        red8 = Color(168, 7, 26),
        red9 = Color(130, 0, 20),
        red10 = Color(92, 0, 17),
        orange1 = Color(255, 247, 230),
        orange2 = Color(255, 231, 186),
        orange3 = Color(255, 213, 145),
        orange4 = Color(255, 192, 105),
        orange5 = Color(255, 169, 64),
        orange6 = Color(250, 140, 22),
        orange7 = Color(212, 107, 8),
        orange8 = Color(173, 78, 0),
        orange9 = Color(135, 56, 0),
        orange10 = Color(97, 37, 0),
        green1 = Color(246, 255, 237),
        green2 = Color(217, 247, 190),
        green3 = Color(183, 235, 143),
        green4 = Color(149, 222, 100),
        green5 = Color(115, 209, 61),
        green6 = Color(82, 196, 26),
        green7 = Color(56, 158, 13),
        green8 = Color(35, 120, 4),
        green9 = Color(19, 82, 0),
        green10 = Color(9, 43, 0),
        blue1 = Color(230, 244, 255),
        blue2 = Color(186, 224, 255),
        blue3 = Color(145, 202, 255),
        blue4 = Color(105, 177, 255),
        blue5 = Color(64, 150, 255),
        blue6 = Color(22, 119, 255),
        blue7 = Color(9, 88, 217),
        blue8 = Color(0, 62, 179),
        blue9 = Color(0, 44, 140),
        blue10 = Color(0, 29, 102),
        purple1 = Color(249, 240, 255),
        purple2 = Color(239, 219, 255),
        purple3 = Color(211, 173, 247),
        purple4 = Color(179, 127, 235),
        purple5 = Color(146, 84, 222),
        purple6 = Color(114, 46, 209),
        purple7 = Color(83, 29, 171),
        purple8 = Color(57, 16, 133),
        purple9 = Color(34, 7, 94),
        purple10 = Color(18, 3, 56),
        gray1 = Color(247, 247, 247),
        gray2 = Color(242, 242, 242),
        gray3 = Color(230, 230, 230),
        gray4 = Color(209, 209, 209),
        gray5 = Color(179, 179, 179),
        gray6 = Color(148, 148, 148),
        gray7 = Color(117, 117, 117),
        gray8 = Color(87, 87, 87),
        gray9 = Color(56, 56, 56),
        gray10 = Color(26, 26, 26),
    )

fun darkExtendColors() =
    ExtendColors(
        red1 = Color(42, 18, 21),
        red2 = Color(67, 20, 24),
        red3 = Color(88, 24, 28),
        red4 = Color(121, 26, 31),
        red5 = Color(166, 29, 36),
        red6 = Color(211, 32, 41),
        red7 = Color(232, 71, 73),
        red8 = Color(243, 113, 112),
        red9 = Color(248, 159, 154),
        red10 = Color(250, 200, 195),
        orange1 = Color(43, 29, 17),
        orange2 = Color(68, 42, 17),
        orange3 = Color(89, 56, 21),
        orange4 = Color(124, 74, 21),
        orange5 = Color(170, 98, 21),
        orange6 = Color(216, 122, 22),
        orange7 = Color(232, 149, 52),
        orange8 = Color(243, 183, 101),
        orange9 = Color(248, 207, 141),
        orange10 = Color(250, 227, 183),
        green1 = Color(22, 35, 18),
        green2 = Color(29, 55, 18),
        green3 = Color(39, 73, 22),
        green4 = Color(48, 99, 23),
        green5 = Color(60, 134, 24),
        green6 = Color(73, 170, 25),
        green7 = Color(106, 190, 57),
        green8 = Color(143, 212, 96),
        green9 = Color(178, 229, 139),
        green10 = Color(213, 242, 187),
        blue1 = Color(17, 26, 44),
        blue2 = Color(17, 37, 69),
        blue3 = Color(21, 50, 91),
        blue4 = Color(21, 65, 126),
        blue5 = Color(21, 84, 173),
        blue6 = Color(22, 104, 220),
        blue7 = Color(60, 137, 232),
        blue8 = Color(101, 169, 243),
        blue9 = Color(141, 197, 248),
        blue10 = Color(183, 220, 250),
        purple1 = Color(26, 19, 37),
        purple2 = Color(36, 22, 58),
        purple3 = Color(48, 28, 77),
        purple4 = Color(62, 32, 105),
        purple5 = Color(81, 37, 143),
        purple6 = Color(100, 42, 181),
        purple7 = Color(133, 78, 202),
        purple8 = Color(171, 122, 224),
        purple9 = Color(205, 168, 240),
        purple10 = Color(235, 215, 250),
        gray1 = Color(31, 31, 31),
        gray2 = Color(38, 38, 38),
        gray3 = Color(51, 51, 51),
        gray4 = Color(74, 74, 74),
        gray5 = Color(106, 106, 106),
        gray6 = Color(139, 139, 139),
        gray7 = Color(171, 171, 171),
        gray8 = Color(204, 204, 204),
        gray9 = Color(223, 223, 223),
        gray10 = Color(246, 246, 246),
    )

object CustomColors {
    var black = false

    val topBarColors: TopAppBarColors
        @Composable get() {
            return if (!LocalDarkMode.current) {
                TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.surfaceContainer,
                    scrolledContainerColor = colorScheme.surfaceContainer,
                )
            } else {
                TopAppBarDefaults.topAppBarColors()
            }
        }

    val cardColors: CardColors
        @Composable get() = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer)

    val cardColorsOnSurfaceContainer: CardColors
        @Composable get() = CardDefaults.cardColors(containerColor = colorScheme.surfaceBright)

    val listItemColors: ListItemColors
        @Composable get() = ListItemDefaults.colors(containerColor = colorScheme.surfaceBright)

    // 浅色模式下页面背景和悬浮工具栏默认的底色相同，用强调色的版本才分得开
    val floatingToolbarColors: FloatingToolbarColors
        @Composable get() = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
}
