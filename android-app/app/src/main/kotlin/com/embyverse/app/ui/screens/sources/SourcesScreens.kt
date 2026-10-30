package com.embyverse.app.ui.screens.sources

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.embyverse.app.R
import com.embyverse.app.data.db.SourceEntity
import com.embyverse.app.data.repo.SourceDraft
import com.embyverse.app.data.repo.SyncState
import com.embyverse.app.di.AppContainer
import com.embyverse.app.ui.appViewModel
import com.embyverse.app.ui.components.BrandLogo
import com.embyverse.app.ui.components.CategoryChip
import com.embyverse.app.ui.components.EmbyButton
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.components.MessageState
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.navigation.Routes
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.app.work.SyncWorker
import com.embyverse.core.config.LogoVariant
import com.embyverse.core.model.SourceType
import com.embyverse.core.net.Outcome
import com.embyverse.core.net.UrlSafety
import com.embyverse.core.source.ConnectionReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ LISTA DE FUENTES
class SourcesViewModel(private val c: AppContainer) : ViewModel() {
    val sources = c.sources.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sync = c.sources.syncState
    fun syncNow(id: String) = viewModelScope.launch { c.sources.sync(id); c.epg.refresh(id) }
    fun delete(id: String) = viewModelScope.launch { c.sources.delete(id) }
}

@Composable
fun SubScreenTop(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val t = LocalEmby.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = t.onBackground) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = t.onBackground, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun SourcesScreen(nav: Navigator) {
    val vm = appViewModel { SourcesViewModel(it) }
    val list by vm.sources.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    var confirmDelete by remember { mutableStateOf<SourceEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        SubScreenTop(stringResource(R.string.nav_sources), nav::back) {
            IconButton(onClick = { nav.go(Routes.sourceEdit()) }) { Icon(Icons.Rounded.Add, stringResource(R.string.action_add_source), tint = t.primary) }
        }
        val l = list
        when {
            l == null -> Unit
            l.isEmpty() -> MessageState(Icons.Rounded.SatelliteAlt, stringResource(R.string.home_no_sources_title), stringResource(R.string.home_no_sources_message),
                stringResource(R.string.action_add_source), { nav.go(Routes.sourceEdit()) })
            else -> LazyColumn(contentPadding = PaddingValues(t.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(l, key = { it.id }) { s -> SourceCard(s, sync[s.id], { vm.syncNow(s.id) }, { nav.go(Routes.sourceEdit(s.id)) }, { confirmDelete = s }) }
            }
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.source_delete_title)) },
            text = { Text(stringResource(R.string.source_delete_message, s.name)) },
            confirmButton = { TextButton(onClick = { vm.delete(s.id); confirmDelete = null }) { Text(stringResource(R.string.action_delete), color = t.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SourceCard(s: SourceEntity, sync: SyncState?, onSync: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val t = LocalEmby.current
    val type = SourceType.valueOf(s.type)
    Column(Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(t.cardRadius)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(when (type) { SourceType.XTREAM -> Icons.Rounded.Dns; SourceType.M3U_FILE -> Icons.Rounded.FolderOpen; else -> Icons.Rounded.Link }, null, tint = t.accent)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, color = t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(typeLabel(type)), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
            if (sync is SyncState.Running) CircularProgressIndicator(Modifier.size(22.dp), color = t.primary, strokeWidth = 2.dp)
            else IconButton(onClick = onSync) { Icon(Icons.Rounded.Sync, stringResource(R.string.action_sync), tint = t.onBackground) }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, stringResource(R.string.action_edit), tint = t.onBackground) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete), tint = t.error) }
        }
        Text(stringResource(R.string.source_counts, s.liveCount, s.movieCount, s.seriesCount), style = MaterialTheme.typography.bodyMedium, color = t.onSurface)
        val last = s.lastSyncAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
        Text(if (last != null) stringResource(R.string.source_last_sync, last) else stringResource(R.string.source_never_synced), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
        if (!UrlSafety.isHttps(s.location) && type != SourceType.M3U_FILE) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = t.textSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.source_insecure_http), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
        }
        s.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.error) }
        if (sync is SyncState.Done && sync.warnings.isNotEmpty()) sync.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
    }
}

private fun typeLabel(t: SourceType) = when (t) {
    SourceType.M3U_URL -> R.string.source_type_m3u_url
    SourceType.M3U_FILE -> R.string.source_type_m3u_file
    SourceType.XTREAM -> R.string.source_type_xtream
}

// ------------------------------------------------------------------ AÑADIR / EDITAR FUENTE
data class SourceForm(
    val type: SourceType = SourceType.M3U_URL,
    val name: String = "",
    val url: String = "",
    val fileUri: String = "",
    val fileName: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val userAgent: String = "",
    val epgUrl: String = "",
    val hasStoredPassword: Boolean = false,
)

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val report: ConnectionReport) : TestState
    data class Error(val message: String) : TestState
}

class SourceEditViewModel(private val c: AppContainer, private val id: String?) : ViewModel() {
    val form = MutableStateFlow(SourceForm())
    val test = MutableStateFlow<TestState>(TestState.Idle)
    val saving = MutableStateFlow(false)
    val existing = c.sources.sources
    /** Servidor ya puesto (APK o panel): al añadir una cuenta solo se piden usuario y contraseña. */
    val lockedHost: String? = if (id == null) com.embyverse.app.data.repo.ProviderHost.of(c.config.config.value) else null

    init {
        lockedHost?.let { h -> form.value = SourceForm(SourceType.XTREAM, server = h) }
        if (id != null) viewModelScope.launch {
            c.database.sources().get(id)?.let { s ->
                val type = SourceType.valueOf(s.type)
                form.value = SourceForm(type, s.name,
                    url = if (type == SourceType.M3U_URL) s.location else "", fileUri = if (type == SourceType.M3U_FILE) s.location else "",
                    fileName = if (type == SourceType.M3U_FILE) Uri.parse(s.location).lastPathSegment.orEmpty() else "",
                    server = if (type == SourceType.XTREAM) s.location else "", username = s.username.orEmpty(),
                    userAgent = s.userAgent.orEmpty(), epgUrl = s.epgUrl.orEmpty(), hasStoredPassword = c.credentials.get(s.id) != null)
            }
        }
    }

    fun update(f: (SourceForm) -> SourceForm) { form.update(f); test.value = TestState.Idle }

    /** Si el usuario pega un enlace "get.php?username=..&password=..", se rellenan los campos Xtream. */
    fun onServerChanged(text: String) {
        val creds = c.sources.parseCredentialUrl(text)
        if (creds != null) update { it.copy(type = SourceType.XTREAM, server = creds.baseUrl, username = creds.username, password = creds.password) }
        else update { it.copy(server = text) }
    }

    fun draft(): SourceDraft? {
        val f = form.value
        val location = when (f.type) { SourceType.M3U_URL -> f.url.trim(); SourceType.M3U_FILE -> f.fileUri; SourceType.XTREAM -> f.server.trim() }
        if (location.isBlank()) return null
        val name = f.name.trim().ifEmpty {
            if (f.type == SourceType.M3U_FILE) f.fileName.ifBlank { "Lista M3U" }
            else runCatching { Uri.parse(if (location.contains("://")) location else "http://$location").host }.getOrNull() ?: "Fuente"
        }
        return SourceDraft(id, name, f.type, location, f.username.trim().takeIf { f.type == SourceType.XTREAM },
            f.password.takeIf { it.isNotEmpty() }, f.userAgent.trim().ifEmpty { null }, f.epgUrl.trim().ifEmpty { null })
    }

    fun runTest(missing: String) {
        val d = draft() ?: run { test.value = TestState.Error(missing); return }
        test.value = TestState.Running
        viewModelScope.launch {
            test.value = when (val r = c.sources.test(d)) {
                is Outcome.Ok -> TestState.Ok(r.value)
                is Outcome.Fail -> TestState.Error(r.error.userMessage)
            }
        }
    }

    fun save(missing: String, done: () -> Unit) {
        val d = draft() ?: run { test.value = TestState.Error(missing); return }
        saving.value = true
        viewModelScope.launch {
            val sid = c.sources.save(d)
            SyncWorker.syncNow(c.app, sid)
            saving.value = false
            done()
        }
    }
}

@Composable
fun SourceEditScreen(nav: Navigator, id: String?, firstRun: Boolean, onDone: () -> Unit) {
    val vm = appViewModel(key = "source-edit-$id") { SourceEditViewModel(it, id) }
    val f by vm.form.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }
    val missing = stringResource(R.string.source_missing_location)
    var pickerError by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            vm.update { it.copy(fileUri = uri.toString(), fileName = name ?: uri.lastPathSegment.orEmpty()) }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (firstRun) Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLogo(LogoVariant.BUNDLED_FULL, null, Modifier.height(if (t.device.isTv) 120.dp else 96.dp))
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall, color = t.onBackground)
            Text(stringResource(R.string.onboarding_subtitle), style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, modifier = Modifier.padding(horizontal = 24.dp))
        } else SubScreenTop(stringResource(if (id == null) R.string.source_add_title else R.string.source_edit_title), nav::back)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(t.screenPadding).widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val locked = vm.lockedHost != null
            if (!locked) Text(stringResource(R.string.source_type), style = MaterialTheme.typography.titleSmall, color = t.textSecondary)
            if (!locked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceType.entries.forEach { type -> CategoryChip(stringResource(typeLabel(type)), f.type == type) { vm.update { it.copy(type = type) } } }
            }
            if (!locked) Field(f.name, { v -> vm.update { it.copy(name = v) } }, stringResource(R.string.source_name), stringResource(R.string.source_name_hint))
            else Text(stringResource(R.string.source_locked_hint), style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
            when (f.type) {
                SourceType.M3U_URL -> Field(f.url, { v -> vm.update { it.copy(url = v) } }, stringResource(R.string.source_url), "https://proveedor.example.com/lista.m3u", KeyboardType.Uri)
                SourceType.M3U_FILE -> {
                    EmbyButton(if (f.fileName.isBlank()) stringResource(R.string.source_pick_file) else f.fileName, {
                        try { picker.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { pickerError = true }
                    }, icon = Icons.Rounded.FolderOpen, secondary = true)
                    if (pickerError) Text(stringResource(R.string.source_picker_unavailable), color = t.error, style = MaterialTheme.typography.bodySmall)
                }
                SourceType.XTREAM -> {
                    if (!locked) {
                        Field(f.server, vm::onServerChanged, stringResource(R.string.source_server), "http://servidor.example.com:8080", KeyboardType.Uri)
                        Text(stringResource(R.string.source_server_hint), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                    }
                    Field(f.username, { v -> vm.update { it.copy(username = v) } }, stringResource(R.string.source_username), null)
                    OutlinedTextField(
                        value = f.password, onValueChange = { v -> vm.update { it.copy(password = v) } }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.source_password)) },
                        placeholder = { if (f.hasStoredPassword) Text(stringResource(R.string.source_password_stored)) },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.action_toggle_password)) } },
                        colors = fieldColors(),
                    )
                }
            }
            if (!locked) {
                Text(stringResource(R.string.source_advanced), style = MaterialTheme.typography.titleSmall, color = t.textSecondary)
                Field(f.epgUrl, { v -> vm.update { it.copy(epgUrl = v) } }, stringResource(R.string.source_epg_url), stringResource(R.string.source_epg_hint), KeyboardType.Uri)
                if (f.type != SourceType.M3U_FILE) Field(f.userAgent, { v -> vm.update { it.copy(userAgent = v) } }, stringResource(R.string.source_user_agent), stringResource(R.string.optional))
            }

            TestResult(test)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EmbyButton(stringResource(R.string.action_test_connection), { vm.runTest(missing) }, icon = Icons.Rounded.NetworkCheck, secondary = true, enabled = test !is TestState.Running)
                EmbyButton(stringResource(R.string.action_save), { vm.save(missing, onDone) }, enabled = !saving)
            }
            Text(stringResource(R.string.source_legal_notice), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            if (firstRun) {
                val existing by vm.existing.collectAsStateWithLifecycle(initialValue = emptyList())
                if (existing.isNotEmpty()) TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_use_existing, existing.size), color = t.accent) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun fieldColors() = LocalEmby.current.let { t ->
    OutlinedTextFieldDefaults.colors(focusedBorderColor = t.focusRing, unfocusedBorderColor = t.surfaceVariant, focusedLabelColor = t.accent,
        focusedContainerColor = t.surface, unfocusedContainerColor = t.surface, cursorColor = t.primary)
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, placeholder: String?, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, placeholder = if (placeholder != null) ({ Text(placeholder) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), colors = fieldColors())
}

@Composable
private fun TestResult(test: TestState) {
    val t = LocalEmby.current
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = t.primary, strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp)); Text(stringResource(R.string.source_testing), color = t.onSurface)
        }
        is TestState.Error -> Row(Modifier.fillMaxWidth().background(t.error.copy(alpha = 0.12f), RoundedCornerShape(t.cardRadius)).padding(12.dp)) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = t.error); Spacer(Modifier.size(10.dp))
            Text(test.message, color = t.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        is TestState.Ok -> Column(Modifier.fillMaxWidth().background(t.primary.copy(alpha = 0.12f), RoundedCornerShape(t.cardRadius)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = t.primary); Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.source_test_ok), color = t.onBackground, style = MaterialTheme.typography.titleSmall)
            }
            val r = test.report
            if (r.account == null) Text(stringResource(R.string.source_counts, r.liveCount, r.movieCount, r.seriesCount), color = t.onSurface, style = MaterialTheme.typography.bodyMedium)
            r.account?.let { a ->
                Text(stringResource(R.string.source_account_status, a.status), color = t.onSurface, style = MaterialTheme.typography.bodyMedium)
                a.expiresAtEpochSeconds?.let { Text(stringResource(R.string.source_account_expires, DateFormat.getDateInstance().format(Date(it * 1000))), color = t.onSurface, style = MaterialTheme.typography.bodySmall) }
                a.maxConnections?.let { Text(stringResource(R.string.source_account_connections, a.activeConnections ?: 0, it), color = t.onSurface, style = MaterialTheme.typography.bodySmall) }
            }
            r.capabilities?.let { caps -> Text(stringResource(R.string.source_capabilities, caps.supportedEndpoints.joinToString(", ")), color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
            r.warnings.forEach { Text("• $it", color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
