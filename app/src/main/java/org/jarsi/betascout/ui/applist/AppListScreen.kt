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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import org.jarsi.betascout.ui.scan.ScanProgressStrip
import org.jarsi.betascout.ui.scan.ScanStatusViewModel
import org.jarsi.betascout.ui.scan.ScanUiState
import org.jarsi.betascout.ui.scan.mainScreenNotice
import org.jarsi.betascout.ui.scan.scanNoticeText

@Composable
fun AppListScreen(
    onAppClick: (String) -> Unit,
    onAccountClick: () -> Unit,
    onSignInClick: () -> Unit,
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
        // The rate-limit cooldown may have run out while the phone slept, where
        // no timer of ours was counting.
        scanViewModel.recheckCooldown()
        onPauseOrDispose { }
    }
    AppListContent(
        uiState = uiState,
        scanState = scanState,
        onAppClick = onAppClick,
        onFiltersChange = viewModel::updateFilters,
        onCloseSearch = viewModel::closeSearch,
        onSelectTab = viewModel::selectTab,
        onSignInClick = onSignInClick,
        onRetry = { viewModel.refreshByUser() },
        onPullRefresh = {
            // A pull means "bring this list up to date": re-read the installed
            // apps, then re-check the beta statuses that have gone stale. In
            // that order, so the scan can rely on the read just made instead of
            // reading every package a second time. Whether this pull may ask
            // for a scan at all is settled now, while the gesture fires — not
            // seconds later, when a scan the user cancelled in between would
            // otherwise look free to start again.
            val startScan = scanViewModel.beginPullScan()
            viewModel.refreshByUser(startScan)
        },
        onSettingsClick = onAccountClick,
        onCancelScan = scanViewModel::cancel,
    )
}

@Composable
private fun AppListContent(
    uiState: AppListUiState,
    scanState: ScanUiState,
    onAppClick: (String) -> Unit,
    onFiltersChange: (AppFilters) -> Unit,
    onCloseSearch: () -> Unit,
    onSelectTab: (AppListTab) -> Unit,
    onSignInClick: () -> Unit,
    onRetry: () -> Unit,
    onPullRefresh: () -> Unit,
    onSettingsClick: () -> Unit,
    onCancelScan: () -> Unit,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderRow(
            searchOpen = searchOpen,
            onToggleSearch = {
                // Closing the search also clears it — query and filter chips —
                // so the list never stays silently narrowed by a hidden control.
                if (searchOpen) onCloseSearch()
                searchOpen = !searchOpen
            },
            onSettingsClick = onSettingsClick,
        )

        // Scanning surfaces here only while it actually runs; the controls and
        // the idle summary live on the settings screen.
        if (scanState.busy || scanState.cancelling) {
            ScanProgressStrip(
                state = scanState,
                onCancel = onCancelScan,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (searchOpen) {
            SearchAndFilters(uiState.filters, onFiltersChange)
        } else if (uiState.openBetas.isNotEmpty()) {
            OpenBetasRail(apps = uiState.openBetas, onAppClick = onAppClick)
        }

        BetaTabs(
            selected = uiState.selectedTab,
            counts = uiState.counts,
            onSelectTab = onSelectTab,
        )

        // What the user has to be told goes into the list, as its first entry,
        // not above it. Above it the lines would be fixed: in a short window
        // (split screen) or at a large font size a few lines of failure reason,
        // together with the header, the rail and the tabs, can take every pixel
        // that is left — no app row reachable, and no list area to pull on for
        // another try. Inside the list they scroll away like any row.
        //   - the installed apps could not be read;
        //   - a scan that was asked for did not run, or ran without one
        //     successful check (which, and for how long, is mainScreenNotice()'s
        //     business) — otherwise such a pull ends exactly like a clean one;
        //   - the session expired: every membership has vanished from the
        //     lists, and without this line with no explanation at all.
        val scanNotice = scanState.mainScreenNotice()
        val relogin = scanState.needsLogin && !scanState.signedIn
        val hasNotices = uiState.hasError || scanNotice != null || relogin
        // Always the list's first entry, empty when there is nothing to say, so
        // that its place and key never change. (See RevealNotices for what
        // happens when it gets something to say.)
        val notices: LazyListScope.() -> Unit = {
            item(key = NOTICES_KEY, contentType = NOTICES_KEY) {
                Column {
                    if (uiState.hasError) ErrorBanner(onRetry = onRetry)
                    scanNotice?.let { ScanErrorBanner(scanNoticeText(it)) }
                    if (relogin) ReloginBanner(onSignIn = onSignInClick)
                }
            }
        }

        // One indicator for every refresh the user starts by hand — a pull, or
        // Retry on the error line. Its state is the view model's, so it survives
        // the screen being rebuilt. It covers the installed-app refresh only: a
        // scan the pull starts reports through the progress strip, so the
        // spinner never sits there for a ten-minute scan.
        PullToRefresh(
            refreshing = uiState.isUserRefreshing,
            onRefresh = onPullRefresh,
        ) {
            when {
                uiState.showLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.apps.isEmpty() -> {
                    val emptyRes = emptyStateRes(uiState.selectedTab, uiState.filters)
                    // A list even with nothing to list: the pull gesture only
                    // reaches scrollable content, and an empty tab must be
                    // refreshable too.
                    val listState = rememberLazyListState()
                    RevealNotices(listState, hasNotices)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = LIST_PADDING,
                    ) {
                        notices()
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(emptyRes))
                            }
                        }
                    }
                }

                else -> {
                    val listState = rememberLazyListState()
                    RevealNotices(listState, hasNotices)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = LIST_PADDING,
                    ) {
                        notices()
                        items(uiState.apps, key = { it.app.packageName }) { row ->
                            AppRow(
                                row = row,
                                onClick = { onAppClick(row.app.packageName) },
                                // The gap between rows is each row's own, not the
                                // list's: a list-wide spacing would also follow
                                // the notices entry when it is empty.
                                modifier = Modifier.padding(bottom = ROW_GAP),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Brings the notices entry into view when it gets something to say while the
 * list is at its top.
 *
 * A lazy list keeps the row that is at the top where it is. With the notices
 * entry empty, that row is the first app (an entry of no height does not count
 * as the top one once the list has been scrolled), so lines that then appear —
 * a refusal coming back after the scan that hid it, say — grow above the
 * visible area and wait there until the user happens to scroll up. A user who
 * has scrolled further down is left where they are.
 */
@Composable
private fun RevealNotices(listState: LazyListState, hasNotices: Boolean) {
    LaunchedEffect(hasNotices) {
        val atTop = listState.firstVisibleItemIndex <= 1 &&
            listState.firstVisibleItemScrollOffset == 0
        if (hasNotices && atTop) listState.scrollToItem(0)
    }
}

/** Key and content type of the list's first entry. Not a package name: those
 *  cannot contain '#'. */
private const val NOTICES_KEY = "#notices"

private val ROW_GAP = 6.dp

/** Side margins of the list; at the bottom, together with the last row's own
 *  gap, the same 12 dp as before. */
private val LIST_PADDING = PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp)

/** Pull-to-refresh around the list area; the caller owns the indicator state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        content()
    }
}

/** Compact top row: the wordmark plus search and settings — no app bar, so the
 *  content starts right below the status bar. */
@Composable
private fun HeaderRow(
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleSearch) {
            Icon(
                imageVector = if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                contentDescription = stringResource(
                    if (searchOpen) R.string.search_close else R.string.search_open,
                ),
            )
        }
        IconButton(onClick = onSettingsClick) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.settings_open),
            )
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
    // Everything at once: a search should not depend on guessing which tab the
    // app sits on.
    AppListTab.ALL to R.string.tab_all,
    AppListTab.AVAILABLE to R.string.tab_not_joined,
    AppListTab.JOINED to R.string.tab_joined,
    // Apps with no (known) beta program get their own tab so every scanned app is
    // visible somewhere — hiding them made users hunt for "lost" apps.
    AppListTab.NONE to R.string.tab_no_beta,
)

@Composable
private fun BetaTabs(
    selected: AppListTab,
    counts: Map<AppListTab, Int>,
    onSelectTab: (AppListTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        betaTabs.forEach { (tab, labelRes) ->
            FilterChip(
                selected = tab == selected,
                onClick = { onSelectTab(tab) },
                shape = MaterialTheme.shapes.extraLarge,
                border = if (tab == selected) {
                    null
                } else {
                    FilterChipDefaults.filterChipBorder(enabled = true, selected = false)
                },
                label = {
                    Text(
                        stringResource(
                            R.string.tab_with_count,
                            stringResource(labelRes),
                            counts[tab] ?: 0,
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
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextField(
            value = filters.query,
            onValueChange = { onFiltersChange(filters.copy(query = it)) },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            // Empties the query but keeps the search open and focused; the
            // header's close button is the one that leaves the search.
            trailingIcon = if (filters.query.isNotEmpty()) {
                {
                    IconButton(onClick = { onFiltersChange(filters.copy(query = "")) }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.search_clear),
                        )
                    }
                }
            } else {
                null
            },
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
private fun ScanErrorBanner(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun ReloginBanner(onSignIn: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.account_relogin),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSignIn) { Text(stringResource(R.string.account_signin)) }
    }
}

@Composable
private fun AppRow(row: AppBetaOverview, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
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
