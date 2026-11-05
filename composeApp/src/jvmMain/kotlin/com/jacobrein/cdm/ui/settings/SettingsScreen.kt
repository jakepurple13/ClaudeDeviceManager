package com.jacobrein.cdm.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobrein.cdm.install.InstallStatus
import com.jacobrein.cdm.ui.components.AnimatedLine
import com.jacobrein.cdm.ui.components.AnimatedValue
import com.jacobrein.cdm.ui.components.CardBox
import com.jacobrein.cdm.ui.components.DotStatus
import com.jacobrein.cdm.ui.components.Muted
import com.jacobrein.cdm.ui.components.SectionLabel
import com.jacobrein.cdm.ui.components.SmallButton
import com.jacobrein.cdm.ui.components.StatusDot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import org.koin.compose.viewmodel.koinViewModel

private val ErrorBg = Color(0xFF4A2A2A)
private val ErrorFg = Color(0xFFFFB4B4)
private val OkFg = Color(0xFF5FCF8A)

@Composable
fun SettingsScreen(vm: SettingsViewModel = koinViewModel()) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnimatedValue(s.serverError) { err ->
            Row(
                Modifier.fillMaxWidth().background(ErrorBg, RoundedCornerShape(6.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("⚠ $err", color = ErrorFg, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                SmallButton("Retry", onClick = vm::onRetryServer, enabled = !s.saving)
            }
        }

        SectionLabel("Server and timers")
        CardBox {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Port", s.port, s.portError, vm::onPortChanged)
                NumberField("Idle release (min)", s.idle, s.idleError, vm::onIdleChanged)
                NumberField("Editing release (min)", s.editing, s.editingError, vm::onEditingChanged)
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Crossfade(s.saving, animationSpec = tween(200), label = "saving") { saving ->
                    SmallButton(if (saving) "Saving…" else "Save", onClick = vm::onSave, enabled = s.canSave)
                }
                Crossfade(s.listening, animationSpec = tween(200), label = "listening") { up ->
                    Muted(
                        if (up) "Listening on 127.0.0.1:${s.saved.port}. Changing the port restarts the server; then repair the hooks."
                        else "Not listening. Save retries on port ${s.saved.port}, or pick another port.",
                    )
                }
            }
            ResultLine(s.saveResult)
        }

        SectionLabel("Claude Code hooks")
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedContent(
                    s.hookStatus,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "hookStatus",
                ) { st ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(st.dot())
                        Text(st.text(s.saved.port), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Crossfade(s.hookStatus.buttonLabel(), animationSpec = tween(200), label = "hookButton") { label ->
                    if (label != null) SmallButton(label, onClick = vm::onInstallHooks)
                }
            }
            Muted("Merges into ~/.claude/settings.json after backing it up; other hooks are left alone.", Modifier.padding(top = 4.dp))
            ResultLine(s.hookResult)
        }

        SectionLabel("cdm command")
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Install cdm", onClick = vm::onInstallCdm)
                Muted("Copies the cdm script to ~/.local/bin/cdm.")
            }
            ResultLine(s.cdmResult)
        }

        SectionLabel("CLAUDE.md snippet")
        CardBox {
            SelectionContainer {
                Text(
                    s.snippet,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(4.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                        .padding(8.dp),
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Crossfade(s.copied, animationSpec = tween(200), label = "copied") { copied ->
                    SmallButton(if (copied) "Copied" else "Copy", onClick = {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(s.snippet), null)
                        vm.onCopied()
                    })
                }
                SmallButton("Append to ~/.claude/CLAUDE.md", onClick = vm::onAppendSnippet, enabled = !s.confirmAppend)
            }
            AnimatedLine(s.confirmAppend) {
                Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Add this snippet to ~/.claude/CLAUDE.md? An earlier copy between the cdm markers is replaced.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    SmallButton("Append", onClick = vm::onConfirmAppend)
                    SmallButton("Cancel", onClick = vm::onCancelAppend)
                }
            }
            ResultLine(s.appendResult)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, error: String?, onChange: (String) -> Unit) {
    Column(Modifier.width(170.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { t -> onChange(t.filter(Char::isDigit).take(5)) },
            label = { Text(label, fontSize = 11.sp) },
            isError = error != null,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        AnimatedValue(error) { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** An installer action's outcome, animated in under its button. */
@Composable
private fun ResultLine(result: ActionResult?) {
    AnimatedValue(result) { r ->
        val color by animateColorAsState(if (r.ok) OkFg else ErrorFg, tween(200))
        SelectionContainer {
            Text(r.text, color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun InstallStatus?.dot(): DotStatus = when (this) {
    InstallStatus.Installed -> DotStatus.Free
    is InstallStatus.Outdated -> DotStatus.InUse
    InstallStatus.SettingsUnreadable -> DotStatus.Offline
    InstallStatus.NotInstalled, null -> DotStatus.DontUse
}

private fun InstallStatus?.text(port: Int): String = when (this) {
    null -> "Checking…"
    InstallStatus.Installed -> "Installed (port $port)"
    is InstallStatus.Outdated -> "Outdated" + (installedPort?.takeIf { it != port }?.let { " (they point at port $it, server is on $port)" } ?: "")
    InstallStatus.NotInstalled -> "Not installed"
    InstallStatus.SettingsUnreadable -> "~/.claude/settings.json can't be read; fix it by hand"
}

private fun InstallStatus?.buttonLabel(): String? = when (this) {
    InstallStatus.NotInstalled -> "Install hooks"
    is InstallStatus.Outdated -> "Repair hooks"
    else -> null
}
