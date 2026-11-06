package com.programmersbox.cdm.ui.settings

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmersbox.cdm.data.AdbMode
import com.programmersbox.cdm.data.PinnedPermission
import com.programmersbox.cdm.devices.AdbLocation
import com.programmersbox.cdm.devices.AdbSource
import com.programmersbox.cdm.install.PartState
import com.programmersbox.cdm.ui.components.AnimatedLine
import com.programmersbox.cdm.ui.components.AnimatedValue
import com.programmersbox.cdm.ui.components.CardBox
import com.programmersbox.cdm.ui.components.DotStatus
import com.programmersbox.cdm.ui.components.Muted
import com.programmersbox.cdm.ui.components.SectionLabel
import com.programmersbox.cdm.ui.components.SmallButton
import com.programmersbox.cdm.ui.components.StatusDot
import com.programmersbox.cdm.ui.update.UpdatesSection
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import org.koin.compose.viewmodel.koinViewModel

private val ErrorBg = Color(0xFF4A2A2A)
private val ErrorFg = Color(0xFFFFB4B4)
private val OkFg = Color(0xFF5FCF8A)
private val WarnFg = Color(0xFFE0B84A)

/** The Pinned commands options, in display order: title and one-line explanation. */
private val PinnedOptions = listOf(
    Triple(PinnedPermission.MIRROR, "Follow my Claude permission rules (recommended)", "Prompts only where your rules would."),
    Triple(PinnedPermission.ALWAYS_ALLOW, "Always allow", "Pinned commands run without a prompt."),
    Triple(PinnedPermission.ALWAYS_ASK, "Always ask", "Every pinned command needs your approval."),
)

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
                        if (up) "Listening on 127.0.0.1:${s.saved.port}. Changing the port restarts the server and re-installs the hooks."
                        else "Not listening. Save retries on port ${s.saved.port}, or pick another port.",
                    )
                }
            }
            ResultLine(s.saveResult)
        }

        SectionLabel("Pinned commands")
        CardBox {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Muted("Commands rewritten to target a held device. Changes apply at once.", Modifier.padding(bottom = 4.dp))
                PinnedOptions.forEach { (mode, title, detail) ->
                    PinnedOption(title, detail, selected = s.pinnedPermission == mode) { vm.onPinnedPermissionSelected(mode) }
                }
            }
            AnimatedLine(s.pinnedPermission == PinnedPermission.ALWAYS_ALLOW) {
                Text(
                    "Commands from a session holding a device skip your allow/ask rules and auto mode's check. Your deny rules still apply.",
                    color = WarnFg,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            ResultLine(s.pinnedResult)
        }

        SectionLabel("adb")
        CardBox {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Muted("The adb the app uses to see devices. Changes apply at once.", Modifier.padding(bottom = 4.dp))
                PinnedOption(
                    "Automatic (recommended)",
                    "Your own adb (Android SDK, PATH, Homebrew) when one is found, else the bundled copy.",
                    selected = s.adbMode == AdbMode.AUTO,
                ) { vm.onAdbModeSelected(AdbMode.AUTO) }
                PinnedOption(
                    "Bundled",
                    if (s.bundledAdb != null) "The adb shipped with the app." else "Not included in this build.",
                    selected = s.adbMode == AdbMode.BUNDLED,
                ) { vm.onAdbModeSelected(AdbMode.BUNDLED) }
                PinnedOption("Custom", "An adb file you choose.", selected = s.adbMode == AdbMode.CUSTOM) {
                    vm.onAdbModeSelected(AdbMode.CUSTOM)
                }
            }
            AnimatedLine(s.adbMode == AdbMode.CUSTOM) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s.adbPath,
                        onValueChange = vm::onAdbPathChanged,
                        label = { Text("adb path", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    SmallButton("Browse…", onClick = { chooseAdbFile()?.let(vm::onAdbPathPicked) })
                    SmallButton("Use", onClick = vm::onAdbPathSubmit, enabled = s.adbPathDirty && s.adbPath.isNotBlank())
                }
            }
            AnimatedValue(s.adbInUse) { loc ->
                val missing = loc.source == AdbSource.NotFound
                val color by animateColorAsState(if (missing) ErrorFg else MaterialTheme.colorScheme.onSurfaceVariant, tween(200))
                SelectionContainer {
                    Text(adbStatusText(loc, s.adbMode), color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Muted(
                "Every adb on this computer shares one adb server. Pick the same version Claude's sessions run, " +
                    "or adb restarts the server and connected devices drop briefly.",
                Modifier.padding(top = 4.dp),
            )
            ResultLine(s.adbResult)
        }

        SectionLabel("Claude Code integration")
        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusRow("Hooks in ~/.claude/settings.json", s.integration.hooks, s.integration.port?.let { "port $it" })
                StatusRow("cdm in ~/.local/bin", s.integration.cdm, null)
                StatusRow("Snippet in ~/.claude/CLAUDE.md", s.integration.snippet, null)
            }
            AnimatedLine(s.integration.cdm == PartState.Installed && !s.integration.cdmOnPath) {
                Muted("~/.local/bin isn't on your PATH: add `export PATH=\"\$HOME/.local/bin:\$PATH\"` to your shell profile.", Modifier.padding(top = 6.dp))
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Crossfade(s.repairing, animationSpec = tween(200), label = "repairing") { repairing ->
                    SmallButton(if (repairing) "Repairing…" else "Repair", onClick = vm::onRepair, enabled = s.canRepair)
                }
                Muted("Installed when the server starts and removed when the app quits; other hooks and text are left alone.")
            }
            ResultLine(s.repairResult)
        }

        UpdatesSection()

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
                Muted("Added to ~/.claude/CLAUDE.md while the app runs; copy it to use it elsewhere.")
            }
        }
    }
}

/** One radio option: the whole row is clickable; the title brightens when selected. */
@Composable
private fun PinnedOption(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            val titleColor by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(200),
            )
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyMedium)
            Muted(detail)
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

/** One integration part: dot, name, and its state (animated as it changes). */
@Composable
private fun StatusRow(name: String, part: PartState, detail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(part.dot())
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(230.dp))
        AnimatedContent(
            part,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "part",
        ) { p ->
            val color by animateColorAsState(if (p is PartState.Failed) ErrorFg else MaterialTheme.colorScheme.onSurfaceVariant, tween(200))
            SelectionContainer {
                Text(p.text(detail), color = color, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun adbStatusText(loc: AdbLocation, mode: AdbMode): String = when (loc.source) {
    AdbSource.NotFound -> when (mode) {
        AdbMode.AUTO -> "No adb found. Install Android platform-tools, or choose Custom."
        AdbMode.BUNDLED -> "This build has no bundled adb. Choose Automatic or Custom."
        AdbMode.CUSTOM -> if (loc.path.isBlank()) "Choose an adb file." else "Not an executable file: ${loc.path}"
    }
    AdbSource.AndroidSdk -> "In use: ${loc.path} (Android SDK)"
    AdbSource.Path -> "In use: ${loc.path} (PATH)"
    AdbSource.KnownLocation -> "In use: ${loc.path}"
    AdbSource.Bundled -> "In use: bundled adb (${loc.path})"
    AdbSource.Custom -> "In use: ${loc.path}"
}

/** The OS file picker for an adb binary; null when cancelled. */
private fun chooseAdbFile(): String? {
    val dialog = FileDialog(null as Frame?, "Choose adb", FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).path
}

private fun PartState.dot(): DotStatus = when (this) {
    PartState.Installed -> DotStatus.Free
    PartState.NotInstalled -> DotStatus.DontUse
    is PartState.Failed -> DotStatus.Offline
}

private fun PartState.text(detail: String?): String = when (this) {
    PartState.Installed -> "Installed while the app runs" + (detail?.let { " ($it)" } ?: "")
    PartState.NotInstalled -> "Not installed"
    is PartState.Failed -> message
}
