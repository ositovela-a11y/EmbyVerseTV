package com.embyverse.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.R
import com.embyverse.app.data.repo.AppUpdater
import com.embyverse.app.ui.theme.LocalEmby
import kotlinx.coroutines.launch

/** «Hay una versión nueva»: descarga e instala desde la app (sin Downloader). */
@Composable
fun UpdateDialog(updater: AppUpdater) {
    val t = LocalEmby.current
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var hidden by remember { mutableStateOf(updater.snoozed) }
    var needsPermission by remember { mutableStateOf(false) }
    val s = state
    if (hidden) return
    when (s) {
        is AppUpdater.State.Available -> AlertDialog(
            onDismissRequest = { updater.later(); hidden = true },
            title = { Text(stringResource(R.string.update_title, s.versionName)) },
            text = { Text(stringResource(if (needsPermission) R.string.update_permission else R.string.update_message)) },
            confirmButton = {
                TextButton(onClick = {
                    if (needsPermission) updater.openInstallPermission()
                    scope.launch { needsPermission = !updater.downloadAndInstall() }
                }, modifier = Modifier.testTag("update_now")) { Text(stringResource(if (needsPermission) R.string.update_allow else R.string.update_now)) }
            },
            dismissButton = { TextButton(onClick = { updater.later(); hidden = true }) { Text(stringResource(R.string.update_later)) } },
        )
        is AppUpdater.State.Ready -> AlertDialog(
            onDismissRequest = { updater.later(); hidden = true },
            title = { Text(stringResource(R.string.update_ready_title, s.versionName)) },
            text = { Text(stringResource(R.string.update_ready_message)) },
            confirmButton = { TextButton(onClick = { scope.launch { updater.downloadAndInstall() } }, modifier = Modifier.testTag("update_install")) { Text(stringResource(R.string.update_install)) } },
            dismissButton = { TextButton(onClick = { updater.later(); hidden = true }) { Text(stringResource(R.string.update_later)) } },
        )
        is AppUpdater.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading, s.versionName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = t.primary)
                    Text("${(s.progress * 100).toInt()} %")
                }
            },
            confirmButton = {},
        )
        is AppUpdater.State.Failed -> AlertDialog(
            onDismissRequest = { hidden = true },
            title = { Text(stringResource(R.string.update_failed)) },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { hidden = true; scope.launch { updater.check() } }) { Text(stringResource(R.string.action_ok)) } },
        )
        AppUpdater.State.Idle -> Unit
    }
}
