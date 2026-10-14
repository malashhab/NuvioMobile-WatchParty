package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.watchparty.WatchPartyRoomController
import com.nuvio.app.features.watchparty.WatchPartyServer
import com.nuvio.app.features.watchparty.WatchPartyServerCheck
import com.nuvio.app.features.watchparty.WatchPartyServerProblem
import com.nuvio.app.features.watchparty.WatchPartyServerSettings
import com.nuvio.app.features.watchparty.WatchPartyServerValidator
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_remove
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.watch_party_server_invalid_url
import nuvio.composeapp.generated.resources.watch_party_server_missing_key
import nuvio.composeapp.generated.resources.watch_party_server_missing_url
import nuvio.composeapp.generated.resources.watch_party_server_not_https
import nuvio.composeapp.generated.resources.watch_party_server_secret_key
import nuvio.composeapp.generated.resources.watch_party_settings_howto
import nuvio.composeapp.generated.resources.watch_party_settings_howto_title
import nuvio.composeapp.generated.resources.watch_party_settings_intro
import nuvio.composeapp.generated.resources.watch_party_settings_key_label
import nuvio.composeapp.generated.resources.watch_party_settings_removed
import nuvio.composeapp.generated.resources.watch_party_settings_saved
import nuvio.composeapp.generated.resources.watch_party_settings_section
import nuvio.composeapp.generated.resources.watch_party_settings_test
import nuvio.composeapp.generated.resources.watch_party_settings_test_failed
import nuvio.composeapp.generated.resources.watch_party_settings_test_ok
import nuvio.composeapp.generated.resources.watch_party_settings_testing
import nuvio.composeapp.generated.resources.watch_party_settings_url_label
import nuvio.composeapp.generated.resources.watch_party_settings_using_builtin
import nuvio.composeapp.generated.resources.watch_party_settings_using_none
import nuvio.composeapp.generated.resources.watch_party_settings_using_own
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.watchPartySettingsContent(isTablet: Boolean) {
    item {
        SettingsSection(
            title = stringResource(Res.string.watch_party_settings_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                WatchPartyServerForm(isTablet = isTablet)
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.watch_party_settings_howto_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                Text(
                    text = stringResource(Res.string.watch_party_settings_howto),
                    modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class FormMessage(val text: StringResource, val isError: Boolean)

@Composable
private fun WatchPartyServerForm(isTablet: Boolean) {
    LaunchedEffect(Unit) { WatchPartyServerSettings.ensureLoaded() }
    val custom by WatchPartyServerSettings.custom.collectAsStateWithLifecycle()
    val builtIn = WatchPartyServerSettings.builtIn

    var url by rememberSaveable(custom) { mutableStateOf(custom?.url.orEmpty()) }
    var key by rememberSaveable(custom) { mutableStateOf(custom?.key.orEmpty()) }
    var message by remember { mutableStateOf<FormMessage?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Checks the fields and explains what's wrong; null when they can't be used.
    fun validServer(): WatchPartyServer? =
        when (val check = WatchPartyServerValidator.check(url, key)) {
            is WatchPartyServerCheck.Valid -> check.server
            is WatchPartyServerCheck.Invalid -> {
                message = FormMessage(check.problem.message(), isError = true)
                null
            }
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = when (val saved = custom) {
                null -> builtIn?.let { stringResource(Res.string.watch_party_settings_using_builtin, it.displayHost) }
                    ?: stringResource(Res.string.watch_party_settings_using_none)
                else -> stringResource(Res.string.watch_party_settings_using_own, saved.displayHost)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(Res.string.watch_party_settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it.trim()
                message = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.watch_party_settings_url_label)) },
            placeholder = { Text("https://xxxx.supabase.co") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        SettingsSecretTextField(
            value = key,
            onValueChange = {
                key = it.trim()
                message = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.watch_party_settings_key_label),
            isError = message?.isError == true,
        )
        message?.let {
            Text(
                text = stringResource(it.text),
                style = MaterialTheme.typography.bodySmall,
                color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    validServer()?.let { server ->
                        WatchPartyServerSettings.save(server)
                        message = FormMessage(Res.string.watch_party_settings_saved, isError = false)
                    }
                },
                enabled = !testing,
            ) {
                Text(stringResource(Res.string.action_save))
            }
            OutlinedButton(
                onClick = {
                    val server = validServer() ?: return@OutlinedButton
                    testing = true
                    scope.launch {
                        val works = WatchPartyRoomController.testServer(server)
                        testing = false
                        message = if (works) {
                            FormMessage(Res.string.watch_party_settings_test_ok, isError = false)
                        } else {
                            FormMessage(Res.string.watch_party_settings_test_failed, isError = true)
                        }
                    }
                },
                enabled = !testing,
            ) {
                Text(
                    stringResource(
                        if (testing) Res.string.watch_party_settings_testing else Res.string.watch_party_settings_test,
                    ),
                )
            }
            if (custom != null) {
                TextButton(
                    onClick = {
                        WatchPartyServerSettings.clear()
                        message = FormMessage(Res.string.watch_party_settings_removed, isError = false)
                    },
                    enabled = !testing,
                ) {
                    Text(stringResource(Res.string.action_remove))
                }
            }
        }
    }
}

private fun WatchPartyServerProblem.message(): StringResource =
    when (this) {
        WatchPartyServerProblem.MissingUrl -> Res.string.watch_party_server_missing_url
        WatchPartyServerProblem.InvalidUrl -> Res.string.watch_party_server_invalid_url
        WatchPartyServerProblem.NotHttps -> Res.string.watch_party_server_not_https
        WatchPartyServerProblem.MissingKey -> Res.string.watch_party_server_missing_key
        WatchPartyServerProblem.SecretKey -> Res.string.watch_party_server_secret_key
    }
