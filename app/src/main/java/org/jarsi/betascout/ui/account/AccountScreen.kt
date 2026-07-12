package org.jarsi.betascout.ui.account

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import org.jarsi.betascout.R
import org.jarsi.betascout.data.settings.LastScanInfo
import org.jarsi.betascout.data.settings.ScanType
import org.jarsi.betascout.ui.scan.ScanStatusCard
import org.jarsi.betascout.ui.scan.ScanStatusViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: (() -> Unit)?,
    viewModel: AccountViewModel = hiltViewModel(),
    scanViewModel: ScanStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanState by scanViewModel.state.collectAsStateWithLifecycle()
    val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()

    if (state.showLogin) {
        GoogleLoginWebView(onCaptured = viewModel::onLoginCaptured)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountCard(
                state = state,
                onSignIn = viewModel::startLogin,
                onSignOut = viewModel::signOut,
            )

            ScanStatusCard(
                state = scanState,
                onScanNow = scanViewModel::scanNow,
                onCancel = scanViewModel::cancel,
                onSignIn = viewModel::startLogin,
            )

            if (state.signedIn) {
                scanState.lastScan?.let { LastScanCard(it) }
                FullScanCard(
                    enabled = !scanState.busy && !scanState.cancelling,
                    onFullScan = scanViewModel::fullScan,
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AppearanceCard(
                    useDynamicColor = useDynamicColor,
                    onToggle = viewModel::setUseDynamicColor,
                )
            }

            state.error?.let { error ->
                Text(
                    text = stringResource(R.string.account_error, error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = stringResource(R.string.account_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun AccountCard(
    state: AccountUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    SettingsCard {
        if (state.signedIn) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = state.email.firstOrNull()?.uppercase() ?: "•",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.email, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.account_connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSignOut) {
                    Text(
                        stringResource(R.string.account_signout),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.account_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.busy) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_signin))
                }
            }
        }
    }
}

/** The persisted result of the latest scan plus the background-scan explainer —
 *  the background worker is invisible everywhere else, so it is introduced here. */
@Composable
private fun LastScanCard(lastScan: LastScanInfo) {
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    SettingsCard {
        Text(
            text = stringResource(R.string.account_last_scan_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(
                R.string.account_last_scan,
                dateFormat.format(Date(lastScan.at)),
                stringResource(
                    when (lastScan.scanType) {
                        ScanType.MANUAL -> R.string.account_scan_type_manual
                        ScanType.BACKGROUND -> R.string.account_scan_type_background
                    },
                ),
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.account_scan_totals,
                lastScan.joined,
                lastScan.notJoined,
                lastScan.noProgram,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.account_scan_checked, lastScan.checked),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (lastScan.failed > 0) {
            val reason = lastScan.failureReason
            Text(
                text = if (reason != null) {
                    stringResource(R.string.account_last_scan_failed_reason, lastScan.failed, reason)
                } else {
                    stringResource(R.string.account_last_scan_failed, lastScan.failed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(
            text = stringResource(R.string.account_background_scan_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.account_background_scan_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FullScanCard(enabled: Boolean, onFullScan: () -> Unit) {
    SettingsCard {
        Text(
            text = stringResource(R.string.account_full_scan),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.account_full_scan_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onFullScan,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.account_full_scan_button))
        }
    }
}

@Composable
private fun AppearanceCard(useDynamicColor: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.account_dynamic_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.account_dynamic_color_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = useDynamicColor, onCheckedChange = onToggle)
        }
    }
}

/**
 * Signs the user into their Google web session and, once play.google.com carries the
 * session cookies, captures them (plus the account email) so BetaScout can fetch the
 * user's own testing pages. No token exchange and no Play API — just the web session,
 * the same mechanism the reference app uses.
 */
@Composable
private fun GoogleLoginWebView(onCaptured: (String, String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7a) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    private var handled = false

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (handled || url == null || !url.contains("play.google.com")) return
                        val cookies = CookieManager.getInstance()
                            .getCookie("https://play.google.com") ?: return
                        // A signed-in Google web session carries these auth cookies.
                        val signedIn = cookies.contains("SAPISID=") || cookies.contains("SID=")
                        if (!signedIn) return
                        handled = true
                        view.evaluateJavascript(
                            "(function(){var m=document.documentElement.innerHTML" +
                                ".match(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}/);" +
                                "return m?m[0]:'';})()",
                        ) { raw ->
                            onCaptured(raw.trim('"'), cookies)
                        }
                    }
                }
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https://play.google.com/")
            }
        },
    )
}
