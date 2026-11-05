package com.jacobrein.cdm.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobrein.cdm.ui.nav.AnimatedCount
import com.jacobrein.cdm.ui.nav.ShellViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Title bar content: the app name and a live "N in use · M waiting". With the system title bar this is a strip
 * above the content, without the name (the OS bar already shows it).
 */
@Composable
fun AppTitleBarScope.TitleBarStatus(title: String, shell: ShellViewModel = koinViewModel()) {
    val counts by shell.counts.collectAsStateWithLifecycle()
    val custom = chrome == WindowChrome.Custom
    val status = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = if (custom) {
            Modifier
        } else {
            Modifier.fillMaxWidth().background(barColors(MaterialTheme.colorScheme).background)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (custom) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
        }
        AnimatedCount(counts.inUse, style = status)
        Text(" in use · ", style = status)
        AnimatedCount(counts.waiting, style = status)
        Text(" waiting", style = status)
    }
}
