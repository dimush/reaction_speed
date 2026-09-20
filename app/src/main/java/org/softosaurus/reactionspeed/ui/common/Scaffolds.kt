package org.softosaurus.reactionspeed.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.softosaurus.reactionspeed.ads.AdBanner

/**
 * Page frame for every screen except the game: the content on top, the anchored ad banner at the
 * very bottom.
 *
 * The banner sits inside a box that always carries the navigation-bar padding, so the content is
 * kept clear of the system bar whether or not an ad is actually being shown (the banner collapses
 * to nothing before consent is resolved). Horizontal cutout/bar insets are applied once here.
 */
@Composable
fun BannerScaffold(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.weight(1f)) { content() }
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            AdBanner(Modifier.fillMaxWidth())
        }
    }
}
