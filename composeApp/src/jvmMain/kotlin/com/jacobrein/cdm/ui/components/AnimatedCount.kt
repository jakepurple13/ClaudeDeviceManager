package com.jacobrein.cdm.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/** A number that slides vertically and fades when it changes: up when it grows, down when it shrinks. */
@Composable
fun AnimatedCount(value: Int, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(tween(200)) { h -> if (up) h else -h } + fadeIn(tween(200))) togetherWith
                (slideOutVertically(tween(200)) { h -> if (up) -h else h } + fadeOut(tween(200)))
        },
        label = "count",
    ) { Text("$it", style = style) }
}
