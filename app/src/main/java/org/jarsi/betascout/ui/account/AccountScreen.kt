package org.jarsi.betascout.ui.account

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.jarsi.betascout.work.BetaScanWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.showLogin) {
        GoogleLoginWebView(onCaptured = viewModel::onLoginCaptured)
        return
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The sign-in pitch is only for signed-out users; once signed in the
            // screen leads with the account and its latest scan results instead.
            if (!state.signedIn) {
                Text(
                    text = stringResource(R.string.account_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.cancelling) {
                // The cancelled run is still unwinding its in-flight page fetch;
                // controls stay disabled until the scan lock is actually free.
                Text(
                    text = stringResource(R.string.account_scan_cancelling),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.busy) {
                val progress = state.progress
                if (progress != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { progress.index / progress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(
                                R.string.account_scan_progress,
                                progress.index,
                                progress.total,
                                progress.currentLabel,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                OutlinedButton(
                    onClick = viewModel::cancelScan,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_scan_cancel))
                }
            }

            if (state.signedIn) {
                Text(stringResource(R.string.account_signed_in, state.email))
                LastScanSummary(state.lastScan)
                Button(
                    onClick = viewModel::resync,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_sync))
                }
                TextButton(
                    onClick = viewModel::fullResync,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_full_scan))
                }
                Text(
                    text = stringResource(R.string.account_full_scan_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_signout))
                }
            } else {
                Button(
                    onClick = viewModel::startLogin,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_signin))
                }
            }
            if (state.needsReLogin) {
                Text(
                    text = stringResource(R.string.account_relogin),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.error?.let { error ->
                Text(
                    text = when (error) {
                        BetaScanWorker.ERROR_SCAN_IN_PROGRESS ->
                            stringResource(R.string.account_scan_in_progress)
                        else -> stringResource(R.string.account_error, error)
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The persisted result of the latest scan: when it ran and what it found. */
@Composable
private fun LastScanSummary(lastScan: LastScanInfo?) {
    if (lastScan == null) {
        Text(
            text = stringResource(R.string.account_no_scan_yet),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            style = MaterialTheme.typography.bodyMedium,
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
