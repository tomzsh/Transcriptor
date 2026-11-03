package id.tomz.transcriptor

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import id.tomz.transcriptor.diagnostics.AppDiagnostics
import id.tomz.transcriptor.inference.PerformanceMode
import id.tomz.transcriptor.inference.PerformanceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeChange: (ThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var selectedLanguage by remember { mutableStateOf(AppLanguageSettings.read(context)) }
    var diagnosticsBusy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackFailed by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var languageDialog by rememberSaveable { mutableStateOf(false) }
    var performanceDialog by rememberSaveable { mutableStateOf(false) }
    var performanceMode by remember { mutableStateOf(PerformanceSettings(context.applicationContext).read()) }

    fun openLink(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) {
            feedback = resources.getString(R.string.settings_link_failed)
            feedbackFailed = true
        }
    }

    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsHeading(stringResource(R.string.settings_preferences), stringResource(R.string.settings_appearance_help))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(selected = themeMode == mode, onClick = { onThemeChange(mode) },
                            // Keep full labels readable at compact width with enlarged system text.
                            icon = {},
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)) {
                            Text(stringResource(mode.labelRes), maxLines = 1)
                        }
                    }
                }
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    SettingsDestinationRow(R.drawable.ic_languages, stringResource(R.string.settings_language),
                        stringResource(when (selectedLanguage) {
                            AppLanguage.SYSTEM -> R.string.settings_language_system
                            AppLanguage.INDONESIAN -> R.string.settings_language_indonesian
                            AppLanguage.ENGLISH -> R.string.settings_language_english
                        })) { languageDialog = true }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsDestinationRow(R.drawable.ic_cpu, stringResource(R.string.settings_performance),
                        stringResource(if (performanceMode == PerformanceMode.BALANCED) R.string.performance_balanced else R.string.performance_fast)) {
                        performanceDialog = true
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsHeading(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_help))
            OutlinedButton(
                enabled = !diagnosticsBusy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    diagnosticsBusy = true
                    feedback = null
                    scope.launch {
                        try {
                            val report = AppDiagnostics.export(context.applicationContext)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", report)
                            val share = Intent(Intent.ACTION_SEND).setType("application/zip")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .apply { clipData = ClipData.newUri(context.contentResolver, "Transcriptor diagnostics", uri) }
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(share, resources.getString(R.string.settings_share_title)))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            feedback = resources.getString(R.string.settings_diagnostics_failed)
                            feedbackFailed = true
                        } finally { diagnosticsBusy = false }
                    }
                },
            ) {
                AppIcon(R.drawable.ic_upload)
                Text(stringResource(if (diagnosticsBusy) R.string.settings_diagnostics_busy else R.string.settings_share_diagnostics), Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = { confirmClear = true }, enabled = !diagnosticsBusy) {
                AppIcon(R.drawable.ic_trash_2)
                Text(stringResource(R.string.settings_clear_diagnostics), Modifier.padding(start = 8.dp))
            }
            feedback?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = if (feedbackFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Transcriptor", style = MaterialTheme.typography.headlineSmall)
                Text(versionName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.settings_about_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_creator), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_from), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsDestinationRow(R.drawable.ic_file_text, stringResource(R.string.settings_github), "github.com/tomzsh") { openLink("https://github.com/tomzsh") }
                SettingsDestinationRow(R.drawable.ic_chevron_right, stringResource(R.string.settings_website), "qodequ.com") { openLink("https://qodequ.com") }
            }
        }
    }
    if (languageDialog) AlertDialog(
        onDismissRequest = { languageDialog = false },
        title = { Text(stringResource(R.string.settings_language)) },
        text = { Column {
            Text(stringResource(R.string.settings_language_help), style = MaterialTheme.typography.bodyMedium)
            AppLanguageOptions(selectedLanguage) { language ->
                selectedLanguage = language
                languageDialog = false
                AppLanguageSettings.select(context, language)
            }
        } },
        confirmButton = { TextButton(onClick = { languageDialog = false }) { Text(stringResource(R.string.done)) } },
    )
    if (performanceDialog) AlertDialog(
        onDismissRequest = { performanceDialog = false; performanceMode = PerformanceSettings(context.applicationContext).read() },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { PerformanceOptions() } },
        confirmButton = { TextButton(onClick = {
            performanceDialog = false
            performanceMode = PerformanceSettings(context.applicationContext).read()
        }) { Text(stringResource(R.string.done)) } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.settings_clear_confirm_title)) },
        text = { Text(stringResource(R.string.settings_clear_confirm_message)) },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                diagnosticsBusy = true
                feedback = null
                scope.launch {
                    try {
                        AppDiagnostics.clear(context.applicationContext)
                        feedback = resources.getString(R.string.settings_clear_success)
                        feedbackFailed = false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        feedback = resources.getString(R.string.settings_clear_failed)
                        feedbackFailed = true
                    } finally { diagnosticsBusy = false }
                }
            }) { Text(stringResource(R.string.remove_model)) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.project_delete_cancel)) } },
    )
}

@Composable
internal fun AppLanguageOptions(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        AppLanguage.entries.forEach { language ->
            val title = stringResource(when (language) {
                AppLanguage.SYSTEM -> R.string.settings_language_system
                AppLanguage.INDONESIAN -> R.string.settings_language_indonesian
                AppLanguage.ENGLISH -> R.string.settings_language_english
            })
            SettingsRadioOption(title, selected == language, { onSelect(language) })
        }
    }
}

@Composable
internal fun PerformanceOptions() {
    val context = LocalContext.current
    val settings = remember { PerformanceSettings(context.applicationContext) }
    var mode by remember { mutableStateOf(settings.read()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsHeading(stringResource(R.string.settings_performance), stringResource(R.string.settings_performance_help))
        Column(Modifier.selectableGroup()) {
            PerformanceMode.entries.forEach { item ->
                SettingsRadioOption(
                    title = stringResource(if (item == PerformanceMode.BALANCED) R.string.performance_balanced else R.string.performance_fast),
                    selected = mode == item,
                    onClick = { settings.save(item); mode = item },
                    description = stringResource(if (item == PerformanceMode.BALANCED) R.string.performance_balanced_help else R.string.performance_fast_help),
                )
            }
        }
    }
}

@Composable
private fun SettingsHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsRadioOption(title: String, selected: Boolean, onClick: () -> Unit, description: String? = null) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .minimumInteractiveComponentSize().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SettingsDestinationRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon(R.drawable.ic_chevron_right)
        }
    }
}
