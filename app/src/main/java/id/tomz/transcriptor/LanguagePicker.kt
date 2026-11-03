package id.tomz.transcriptor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.tomz.transcriptor.domain.LanguageOption
import id.tomz.transcriptor.domain.languageOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguagePicker(
    title: String,
    selected: String,
    options: List<LanguageOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowAuto: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    OutlinedButton(onClick = { search = ""; open = true }, modifier = modifier.fillMaxWidth(), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), shape = MaterialTheme.shapes.small) {
        AppIcon(R.drawable.ic_languages)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (selected.isBlank()) stringResource(R.string.language_choose) else languageLabel(selected),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        AppIcon(R.drawable.ic_chevron_down)
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(search, onValueChange = { search = it }, singleLine = true,
                    label = { Text(stringResource(R.string.language_search)) },
                    leadingIcon = { AppIcon(R.drawable.ic_search) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                val available = (if (allowAuto) listOf(languageOption("auto")) else emptyList()) + options
                val labels = available.associate { it.code to languageLabel(it.code) }
                val filtered = available.distinctBy { it.code }.filter { it.matches(search) || labels.getValue(it.code).contains(search.trim(), ignoreCase = true) }.sortedBy { labels.getValue(it.code) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (filtered.isEmpty()) item { Text(stringResource(R.string.language_no_results), Modifier.padding(vertical = 20.dp)) }
                    items(filtered, key = { it.code }) { option ->
                        ListItem(
                            headlineContent = { Text(labels.getValue(option.code)) },
                            supportingContent = { if (option.nativeName.isNotBlank()) Text(option.nativeName) },
                            trailingContent = {
                                if (selected == option.code) AppIcon(R.drawable.ic_check)
                                else Text(option.code.uppercase(), style = MaterialTheme.typography.labelSmall)
                            },
                            modifier = Modifier.fillMaxWidth().selectable(selected = selected == option.code,
                                role = Role.RadioButton, onClick = { onSelect(option.code); open = false }),
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun languageLabel(code: String): String {
    if (code == "auto") return stringResource(R.string.language_auto)
    val displayLocale = LocalConfiguration.current.locales[0]
    val locale = Locale.forLanguageTag(if (code == "jw") "jv" else code)
    return locale.getDisplayLanguage(displayLocale).replaceFirstChar { it.titlecase(displayLocale) }
}
