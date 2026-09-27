package com.mtrinh.fobalarm.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The only sizes and spacings in the app.
 *
 * Before this there were ~15 distinct hardcoded font sizes and ~15 distinct spacer
 * heights, and no use of the theme's typography at all, which is why nothing lined up
 * with anything. Nothing outside this file may declare an `sp` or a layout `dp`.
 */
object T {
    // Raw sizes, for call sites that also set weight or colour. These are the ONLY
    // font sizes in the app; 16 distinct values collapsed to 7.
    val caption = 12.sp    // explanations, identity lines
    val label = 13.sp      // secondary values
    val body = 15.sp       // default
    val button = 16.sp
    val headline = 20.sp   // a key figure
    val title = 24.sp      // screen title
    val hero = 44.sp       // the ring clock, and nothing else

}

/** Spacing. Four steps. */
object S {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 28.dp
    /** Page gutter. */
    val page = 18.dp
}

/** Feeds MaterialTheme.typography so stock components inherit the same scale. */
val FobTypography = Typography(
    headlineLarge = TextStyle(fontSize = T.title, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = T.headline, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = T.body),
    bodyLarge = TextStyle(fontSize = T.body),
    bodyMedium = TextStyle(fontSize = T.label),
    bodySmall = TextStyle(fontSize = T.caption),
    labelLarge = TextStyle(fontSize = T.button, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = T.label),
    labelSmall = TextStyle(fontSize = T.caption),
)
