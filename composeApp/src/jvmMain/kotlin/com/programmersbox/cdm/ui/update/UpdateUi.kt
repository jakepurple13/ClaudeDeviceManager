package com.programmersbox.cdm.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmersbox.cdm.ui.components.CardBox
import com.programmersbox.cdm.ui.components.Muted
import com.programmersbox.cdm.ui.components.SectionLabel
import com.programmersbox.cdm.ui.components.SmallButton
import com.programmersbox.cdm.update.GITHUB_OWNER
import com.programmersbox.cdm.update.GITHUB_REPO
import com.programmersbox.cdm.update.UpdateState
import androidx.compose.ui.platform.LocalUriHandler
import org.koin.compose.viewmodel.koinViewModel

private const val ANIM_MS = 220
private const val RELEASES_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

/**
 * The strip above every screen: an update is available, downloading or ready, or this launch was just updated.
 * Slides down in and back up out.
 */
@Composable
fun UpdateBanner(vm: UpdateViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val visible by vm.bannerVisible.collectAsStateWithLifecycle()
    val justUpdated by vm.justUpdatedTo.collectAsStateWithLifecycle()
    Column {
        AnimatedVisibility(
            visible = justUpdated != null,
            enter = expandVertically(tween(ANIM_MS)) + fadeIn(tween(ANIM_MS)),
            exit = shrinkVertically(tween(ANIM_MS)) + fadeOut(tween(ANIM_MS)),
        ) {
            BannerRow {
                Text("Updated to ${justUpdated.orEmpty()}.", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                SmallButton("OK", onClick = vm::onDismissJustUpdated)
            }
        }
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically(tween(ANIM_MS)) + fadeIn(tween(ANIM_MS)),
            exit = shrinkVertically(tween(ANIM_MS)) + fadeOut(tween(ANIM_MS)),
        ) {
            BannerRow {
                UpdateActions(state, vm, Modifier.weight(1f), showLater = true)
            }
        }
    }
}

@Composable
private fun BannerRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** The Settings card: current version, last check outcome, and the same actions as the banner. */
@Composable
fun UpdatesSection(vm: UpdateViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    SectionLabel("Updates")
    CardBox {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Version ${vm.currentVersion}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // Manual fallback (and the only route from a Gradle run): the latest release's installers in the browser.
                SmallButton("Download from GitHub", onClick = { runCatching { uriHandler.openUri(RELEASES_URL) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UpdateActions(state, vm, Modifier.weight(1f), showLater = false)
            }
            Muted("Checks GitHub releases at launch and every 6 hours. Restarting to update releases every held device.")
        }
    }
}

/** Status text plus the one button that fits the state; animated as the state changes. */
@Composable
private fun UpdateActions(state: UpdateState, vm: UpdateViewModel, modifier: Modifier, showLater: Boolean) {
    AnimatedContent(
        state,
        modifier = modifier,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(ANIM_MS)) togetherWith fadeOut(tween(ANIM_MS)) },
        label = "update",
    ) { s ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (s) {
                UpdateState.Unsupported -> Muted("Updates work in installed builds only.", Modifier.weight(1f))
                UpdateState.Idle, UpdateState.UpToDate -> {
                    Muted(if (s == UpdateState.UpToDate) "Up to date." else "Not checked yet.", Modifier.weight(1f))
                    SmallButton("Check now", onClick = vm::onCheck)
                }
                UpdateState.Checking -> {
                    Muted("Checking…", Modifier.weight(1f))
                    SmallButton("Check now", onClick = {}, enabled = false)
                }
                is UpdateState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    SmallButton("Retry", onClick = vm::onCheck)
                }
                is UpdateState.Available -> {
                    Column(Modifier.weight(1f)) {
                        Text("Version ${s.version} is available.", fontWeight = FontWeight.Medium)
                        s.error?.let { Text("Download failed: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 2) }
                    }
                    if (showLater) SmallButton("Later", onClick = vm::onDismissBanner)
                    SmallButton("Download", onClick = vm::onDownload)
                }
                is UpdateState.Downloading -> {
                    val progress by animateFloatAsState(s.fraction, tween(ANIM_MS), label = "progress")
                    Text("Downloading ${s.version}…", modifier = Modifier.weight(1f))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(160.dp))
                }
                is UpdateState.Ready -> {
                    Text("Version ${s.version} is ready.", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (showLater) SmallButton("Later", onClick = vm::onDismissBanner)
                    SmallButton("Restart to update", onClick = vm::onInstall)
                }
            }
        }
    }
}
