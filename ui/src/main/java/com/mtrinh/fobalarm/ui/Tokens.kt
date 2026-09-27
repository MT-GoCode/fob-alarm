package com.mtrinh.fobalarm.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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

    // Type. Six roles, no more.
    val Huge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Light)      // ring clock
    val Title = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)      // screen title
    val Headline = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold) // key figure
    val Body = TextStyle(fontSize = 15.sp)                                     // default
    val Label = TextStyle(fontSize = 13.sp)                                    // secondary
    val Caption = TextStyle(fontSize = 12.sp)                                  // explanations
    val Mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)  // identity
    val Button = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val RingButton = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
}

/** Spacing. Four steps. */
object S {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 28.dp
    /** Page gutter. */
    val page = 18.dp
    /** Minimum comfortable tap target. */
    val tap = 48.dp
}

/** Feeds MaterialTheme.typography so stock components inherit the same scale. */
val FobTypography = Typography(
    headlineLarge = T.Title,
    headlineMedium = T.Headline,
    titleMedium = T.Body,
    bodyLarge = T.Body,
    bodyMedium = T.Label,
    bodySmall = T.Caption,
    labelLarge = T.Button,
    labelMedium = T.Label,
    labelSmall = T.Caption,
)
