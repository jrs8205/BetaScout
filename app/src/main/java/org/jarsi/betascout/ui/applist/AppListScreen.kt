package org.jarsi.betascout.ui.applist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.betascout.R
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.ui.components.AppIcon
import org.jarsi.betascout.ui.scan.ScanStatusCard
import org.jarsi.betascout.ui.scan.ScanStatusViewModel
import org.jarsi.betascout.ui.scan.ScanUiState

@Composable
fun AppListScreen(
    onAppClick: (String) -> Unit,
    onAccountClick: () -> Unit,
    viewModel: AppListViewModel = hiltViewModel(),
    scanViewModel: ScanStatusViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scanState by scanViewModel.state.collectAsStateWithLifecycle()
    // The installed-app mirror only updates when this screen refreshes it. Coming
    // back from the launcher after installing an app must pick the new app up
    // without a manual refresh tap.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    AppListContent(
        uiState = uiState,
        scanState = scanState,
        onAppClick = onAppClick,
        onFiltersChange = viewModel::updateFilters,
        onSelectTab = viewModel::selectTab,
        onRefresh = viewModel::refresh,
        onAccountClick = onAccountClick,
        onScanNow = scanViewModel::scanNow,
        onCancelScan = scanViewModel::cancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppListContent(
    uiState: AppListUiState,
    scanState: ScanUiState,
    onAppClick: (String) -> Unit,
    onFiltersChange: (AppFilters) -> Unit,
    onSelectTab: (BetaMembership) -> Unit,
    onRefresh: () -> Unit,
    onAccountClick: () -> Unit,
    onScanNow: () -> Unit,
    onCancelScan: () -> Unit,
) {
    Scaffold(
        topBar = {
            // Account lives in the bottom bar; the top bar is just the wordmark.
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScanStatusCard(
                state = scanState,
                onScanNow = onScanNow,
                onCancel = onCancelScan,
                onSignIn = onAccountClick,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (uiState.openBetas.isNotEmpty() && uiState.filters.query.isBlank()) {
                OpenBetasRail(apps = uiState.openBetas, onAppClick = onAppClick)
            }

            BetaTabs(
                selected = uiState.selectedTab,
                counts = uiState.counts,
                onSelectTab = onSelectTab,
            )
            SearchAndFilters(uiState.filters, onFiltersChange)

            if (uiState.hasError) {
                ErrorBanner(onRetry = onRefresh)
            }

            when {
                uiState.isLoading || uiState.isRefreshing && uiState.apps.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.apps.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val emptyRes = when (uiState.selectedTab) {
                            BetaMembership.JOINED -> R.string.empty_joined
                            BetaMembership.NONE -> R.string.empty_no_beta
                            else -> R.string.empty_not_joined
                        }
                        Text(stringResource(emptyRes))
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(uiState.apps, key = { it.app.packageName }) { row ->
                            AppRow(row = row, onClick = { onAppClick(row.app.packageName) })
                        }
                    }
                }
            }
        }
    }
}

/** Horizontal rail of betas that can be joined right now — the app's core promise. */
@Composable
private fun OpenBetasRail(apps: List<AppBetaOverview>, onAppClick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.open_betas_title) + " · ${apps.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(apps, key = { it.app.packageName }) { row ->
                Surface(
                    onClick = { onAppClick(row.app.packageName) },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(packageName = row.app.packageName, contentDescription = null)
                        Text(
                            text = row.app.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private val betaTabs = listOf(
    BetaMembership.AVAILABLE to R.string.tab_not_joined,
    BetaMembership.JOINED to R.string.tab_joined,
    // Apps with no (known) beta program get their own tab so every scanned app is
    // visible somewhere — hiding them made users hunt for "lost" apps.
    BetaMembership.NONE to R.string.tab_no_beta,
)

@Composable
private fun BetaTabs(
    selected: BetaMembership,
    counts: Map<BetaMembership, Int>,
    onSelectTab: (BetaMembership) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        betaTabs.forEach { (membership, labelRes) ->
            FilterChip(
                selected = membership == selected,
                onClick = { onSelectTab(membership) },
                shape = MaterialTheme.shapes.extraLarge,
                border = if (membership == selected) {
                    null
                } else {
                    FilterChipDefaults.filterChipBorder(enabled = true, selected = false)
                },
                label = {
                    Text(
                        stringResource(
                            R.string.tab_with_count,
                            stringResource(labelRes),
                            counts[membership] ?: 0,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun SearchAndFilters(
    filters: AppFilters,
    onFiltersChange: (AppFilters) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextField(
            value = filters.query,
            onValueChange = { onFiltersChange(filters.copy(query = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filters.onlySystem,
                onClick = { onFiltersChange(filters.copy(onlySystem = !filters.onlySystem)) },
                shape = MaterialTheme.shapes.extraLarge,
                label = { Text(stringResource(R.string.filter_only_system)) },
            )
        }
    }
}

@Composable
private fun ErrorBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.error_scan),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun AppRow(row: AppBetaOverview, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(packageName = row.app.packageName, contentDescription = null)

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.userStatus?.watching == true) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = stringResource(R.string.filter_only_watched),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(row.statusLineRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            row.statusBadge()?.let { badge -> StatusBadgeChip(badge) }
        }
    }
}

@Composable
private fun StatusBadgeChip(badge: StatusBadge) {
    val (container, content) = when (badge) {
        StatusBadge.OPEN ->
            MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        StatusBadge.JOINED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(
            text = stringResource(badge.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
