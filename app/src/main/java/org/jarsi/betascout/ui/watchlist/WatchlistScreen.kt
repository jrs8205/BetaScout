package org.jarsi.betascout.ui.watchlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import org.jarsi.betascout.R
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.BetaLinkBuilder
import org.jarsi.betascout.ui.components.AppIcon
import org.jarsi.betascout.ui.components.openUrl

@Composable
fun WatchlistScreen(
    onAppClick: (String) -> Unit,
    viewModel: WatchlistViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WatchlistContent(
        uiState = uiState,
        onAppClick = onAppClick,
        onCheckNow = viewModel::markChecked,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchlistContent(
    uiState: WatchlistUiState,
    onAppClick: (String) -> Unit,
    onCheckNow: (String) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.watchlist_title)) }) },
    ) { padding ->
        when {
            uiState.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.apps.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.watchlist_empty)) }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(uiState.apps, key = { it.app.packageName }) { row ->
                    WatchedAppRow(
                        row = row,
                        onClick = { onAppClick(row.app.packageName) },
                        onCheckNow = { onCheckNow(row.app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchedAppRow(
    row: AppBetaOverview,
    onClick: () -> Unit,
    onCheckNow: () -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val status = row.userStatus

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(packageName = row.app.packageName, contentDescription = null)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.interval_line, status?.reminderIntervalDays ?: 7),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = status?.lastCheckedByUser
                    ?.let { stringResource(R.string.last_checked, dateFormat.format(Date(it))) }
                    ?: stringResource(R.string.never_checked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(
            onClick = {
                // Checking = opening the beta page yourself; stamp it as checked.
                openUrl(
                    context,
                    row.betaProgram?.testingUrl ?: BetaLinkBuilder.testingUrl(row.app.packageName),
                )
                onCheckNow()
            },
        ) { Text(stringResource(R.string.check_now)) }
    }
    }
}
