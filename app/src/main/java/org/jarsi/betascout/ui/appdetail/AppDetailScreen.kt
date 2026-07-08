package org.jarsi.betascout.ui.appdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import org.jarsi.betascout.R
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.BetaLinkBuilder
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership
import org.jarsi.betascout.domain.UserBetaState
import org.jarsi.betascout.ui.applist.canJoinBeta
import org.jarsi.betascout.ui.applist.labelRes
import org.jarsi.betascout.ui.components.AppIcon
import org.jarsi.betascout.ui.components.openInCustomTab
import org.jarsi.betascout.ui.components.openPlayPage

@Composable
fun AppDetailScreen(
    onBack: () -> Unit,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppDetailContent(
        uiState = uiState,
        packageName = viewModel.packageName,
        onBack = onBack,
        onSetState = viewModel::setUserState,
        onSetWatching = viewModel::setWatching,
        onSaveNote = viewModel::saveNote,
        onMarkChecked = viewModel::markCheckedNow,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailContent(
    uiState: AppDetailUiState,
    packageName: String,
    onBack: () -> Unit,
    onSetState: (UserBetaState) -> Unit,
    onSetWatching: (Boolean, Int?) -> Unit,
    onSaveNote: (String) -> Unit,
    onMarkChecked: () -> Unit,
) {
    val overview = uiState.overview
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(overview?.app?.label ?: packageName) },
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
        when {
            uiState.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            overview == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.app_not_found)) }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(overview)
                LinkButtons(overview)
                ObservedStatusCard(overview)
                KnownBetaCard(overview)
                MyStatusSection(overview, onSetState)
                WatchSection(overview, onSetWatching)
                CheckedSection(overview, onMarkChecked)
                NoteSection(overview, onSaveNote)
            }
        }
    }
}

@Composable
private fun Header(overview: AppBetaOverview) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(packageName = overview.app.packageName, contentDescription = null, size = 56.dp)
        Column {
            Text(overview.app.label, style = MaterialTheme.typography.titleLarge)
            Text(
                overview.app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.version_line,
                    overview.app.versionName ?: "?",
                    overview.app.versionCode,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            overview.app.installerPackage?.let {
                Text(stringResource(R.string.installer_line, it), style = MaterialTheme.typography.bodySmall)
            }
            if (overview.app.isSystem) {
                Text(
                    stringResource(R.string.system_app),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LinkButtons(overview: AppBetaOverview) {
    val context = LocalContext.current
    val pkg = overview.app.packageName
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The testing page opens in a Custom Tab so the user's browser Google session
        // can complete the join (the opt-in is a plain web form on that page).
        val testingUrl = overview.betaProgram?.testingUrl ?: BetaLinkBuilder.testingUrl(pkg)
        Button(
            onClick = { openInCustomTab(context, testingUrl) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (overview.canJoinBeta()) R.string.join_beta else R.string.open_beta_page,
                ),
            )
        }

        OutlinedButton(
            onClick = { openPlayPage(context, pkg) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.open_play_page)) }
    }
}

/**
 * What the last authenticated scan saw on this app's testing page: the user's own
 * membership, the program's live status and when it was checked. This is the
 * detected truth — the manual marking below is only an optional override.
 */
@Composable
private fun ObservedStatusCard(overview: AppBetaOverview) {
    val observation = overview.observation ?: return
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.observed_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(observation.observedMembership.labelRes()),
                color = if (observation.observedMembership == ObservedMembership.JOINED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                stringResource(
                    R.string.observed_live_status,
                    stringResource(observation.liveStatus.labelRes()),
                ),
            )
            Text(
                stringResource(
                    R.string.observed_checked_at,
                    dateFormat.format(Date(observation.checkedAt)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            observation.lastError?.let {
                Text(
                    stringResource(R.string.observed_last_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun KnownBetaCard(overview: AppBetaOverview) {
    val program = overview.betaProgram ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.known_beta_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(program.knownStatus.labelRes()))
            program.notes?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MyStatusSection(overview: AppBetaOverview, onSetState: (UserBetaState) -> Unit) {
    val selected = overview.userStatus?.state ?: UserBetaState.UNKNOWN
    Column {
        Text(stringResource(R.string.my_status), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.my_status_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UserBetaState.entries.forEach { state ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == state, onClick = { onSetState(state) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == state, onClick = { onSetState(state) })
                Text(stringResource(state.labelRes()))
            }
        }
    }
}

@Composable
private fun WatchSection(overview: AppBetaOverview, onSetWatching: (Boolean, Int?) -> Unit) {
    val watching = overview.userStatus?.watching == true
    val interval = overview.userStatus?.reminderIntervalDays ?: 7
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.watch_app), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.watch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = watching, onCheckedChange = { onSetWatching(it, null) })
        }
        if (watching) {
            Text(stringResource(R.string.reminder_interval), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 14, 30).forEach { days ->
                    FilterChip(
                        selected = interval == days,
                        onClick = { onSetWatching(true, days) },
                        label = { Text(stringResource(R.string.days_count, days)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckedSection(overview: AppBetaOverview, onMarkChecked: () -> Unit) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val lastChecked = overview.userStatus?.lastCheckedByUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = lastChecked?.let { stringResource(R.string.last_checked, dateFormat.format(Date(it))) }
                ?: stringResource(R.string.never_checked),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onMarkChecked) { Text(stringResource(R.string.mark_checked)) }
    }
}

@Composable
private fun NoteSection(overview: AppBetaOverview, onSaveNote: (String) -> Unit) {
    val savedNote = overview.userStatus?.userNote.orEmpty()
    var note by rememberSaveable(savedNote) { mutableStateOf(savedNote) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.note_label)) },
            minLines = 2,
        )
        TextButton(
            onClick = { onSaveNote(note) },
            enabled = note != savedNote,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.save_note)) }
    }
}

private fun KnownBetaStatus.labelRes(): Int = when (this) {
    KnownBetaStatus.UNKNOWN -> R.string.known_unknown
    KnownBetaStatus.OFTEN_OPEN -> R.string.known_often_open
    KnownBetaStatus.OFTEN_FULL -> R.string.known_often_full
    KnownBetaStatus.NO_PROGRAM -> R.string.known_no_program
}

private fun ObservedMembership.labelRes(): Int = when (this) {
    ObservedMembership.UNKNOWN -> R.string.observed_membership_unknown
    ObservedMembership.JOINED -> R.string.observed_membership_joined
    ObservedMembership.NOT_JOINED -> R.string.observed_membership_not_joined
}

private fun LiveBetaStatus.labelRes(): Int = when (this) {
    LiveBetaStatus.UNKNOWN -> R.string.live_unknown
    LiveBetaStatus.OPEN -> R.string.live_open
    LiveBetaStatus.FULL -> R.string.live_full
    LiveBetaStatus.CLOSED -> R.string.live_closed
    LiveBetaStatus.NO_PROGRAM -> R.string.live_no_program
}
