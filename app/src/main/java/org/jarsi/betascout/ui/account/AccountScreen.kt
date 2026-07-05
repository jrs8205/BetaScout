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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.betascout.R

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
            Text(
                text = stringResource(R.string.account_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.busy) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            if (state.signedIn) {
                Text(stringResource(R.string.account_signed_in, state.email))
                Button(
                    onClick = viewModel::resync,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_sync))
                }
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

            state.syncedCount?.let {
                Text(
                    text = stringResource(R.string.account_synced, it),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.error?.let {
                Text(
                    text = stringResource(R.string.account_error, it),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Loads Google's embedded sign-in and captures the account email and short-lived
 * oauth_token once the user completes login. The token is exchanged in-app.
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
                        if (handled) return
                        val cookieString = CookieManager.getInstance()
                            .getCookie("https://accounts.google.com") ?: return
                        val oauthToken = cookieString.split(";")
                            .map { it.trim() }
                            .firstOrNull { it.startsWith("oauth_token=") }
                            ?.removePrefix("oauth_token=")
                        if (oauthToken != null && oauthToken.startsWith("oauth2_4/")) {
                            handled = true
                            view.evaluateJavascript(
                                "(function(){var m=document.documentElement.innerHTML" +
                                    ".match(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}/);" +
                                    "return m?m[0]:'';})()",
                            ) { raw ->
                                onCaptured(raw.trim('"'), oauthToken)
                            }
                        }
                    }
                }
                loadUrl("https://accounts.google.com/EmbeddedSetup")
            }
        },
    )
}
