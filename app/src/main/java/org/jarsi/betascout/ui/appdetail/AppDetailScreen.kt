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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import org.jarsi.betascout.ui.applist.BetaMembership
import org.jarsi.betascout.ui.applist.betaMembership
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
                title = {},
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
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header(overview)
                HeroCard(overview, onSetWatching)
                WatchCard(overview, onSetWatching)
                InfoCard(overview)
                MarkingCard(overview, onSetState, onMarkChecked, onSaveNote)
            }
        }
    }
}

@Composable
private fun Header(overview: AppBetaOverview) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AppIcon(packageName = overview.app.packageName, contentDescription = null, size = 56.dp)
        Text(
            overview.app.label,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = buildString {
                append(overview.app.packageName)
                append(" · ")
                append(overview.app.versionName ?: "?")
                if (overview.app.isSystem) {
                    append(" · ")
                    append(stringResource(R.string.system_app))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One state-colored answer to "what can I do here right now". */
@Composable
private fun HeroCard(overview: AppBetaOverview, onSetWatching: (Boolean, Int?) -> Unit) {
    val context = LocalContext.current
    val observation = overview.observation
    val membership = overview.betaMembership()
    val live = observation?.liveStatus
    val watching = overview.userStatus?.watching == true
    // The testing page opens in a Custom Tab so the user's browser Google session
    // can complete the join (the opt-in is a plain web form on that page).
    val testingUrl = overview.betaProgram?.testingUrl
        ?: BetaLinkBuilder.testingUrl(overview.app.packageName)
    val openPage: () -> Unit = { openInCustomTab(context, testingUrl) }

    data class Hero(
        val titleRes: Int,
        val subtitleRes: Int,
        val container: Color,
        val onContainer: Color,
        val buttonRes: Int,
        val onButton: () -> Unit,
        val filledButton: Boolean,
    )

    val hero = when {
        membership == BetaMembership.JOINED -> Hero(
            R.string.hero_joined, R.string.hero_joined_sub,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            R.string.open_beta_page, openPage, filledButton = false,
        )

        membership == BetaMembership.AVAILABLE && live == LiveBetaStatus.OPEN -> Hero(
            R.string.hero_open, R.string.hero_open_sub,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            R.string.join_beta, openPage, filledButton = true,
        )

        membership == BetaMembership.AVAILABLE && live == LiveBetaStatus.FULL -> Hero(
            R.string.hero_full, R.string.hero_full_sub,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
            if (watching) R.string.open_beta_page else R.string.watch_app,
            if (watching) openPage else fun() { onSetWatching(true, null) },
            filledButton = !watching,
        )

        membership == BetaMembership.AVAILABLE && live == LiveBetaStatus.CLOSED -> Hero(
            R.string.hero_closed, R.string.hero_full_sub,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
            if (watching) R.string.open_beta_page else R.string.watch_app,
            if (watching) openPage else fun() { onSetWatching(true, null) },
            filledButton = !watching,
        )

        membership == BetaMembership.AVAILABLE -> Hero(
            R.string.status_line_has_beta, R.string.hero_unknown_sub,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
            R.string.open_beta_page, openPage, filledButton = false,
        )

        else -> Hero(
            R.string.hero_no_beta, R.string.hero_no_beta_sub,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            R.string.open_beta_page, openPage, filledButton = false,
        )
    }

    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = hero.container),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(hero.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                color = hero.onContainer,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(hero.subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = hero.onContainer,
                textAlign = TextAlign.Center,
            )
            observation?.let {
                Text(
                    text = stringResource(
                        R.string.hero_checked_at,
                        dateFormat.format(Date(it.checkedAt)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = hero.onContainer.copy(alpha = 0.7f),
                )
            }
            observation?.lastError?.let {
                Text(
                    text = stringResource(R.string.observed_last_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (hero.filledButton) {
                Button(
                    onClick = hero.onButton,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(hero.buttonRes)) }
            } else {
                Button(
                    onClick = hero.onButton,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) { Text(stringResource(hero.buttonRes)) }
            }
        }
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun WatchCard(overview: AppBetaOverview, onSetWatching: (Boolean, Int?) -> Unit) {
    val watching = overview.userStatus?.watching == true
    val interval = overview.userStatus?.reminderIntervalDays ?: 7
    DetailCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.watch_app), style = MaterialTheme.typography.titleSmall)
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
                        shape = MaterialTheme.shapes.extraLarge,
                        label = { Text(stringResource(R.string.days_count, days)) },
                    )
                }
            }
        }
    }
}

/** The facts: what the last authenticated scan saw, what the catalog knows, links. */
@Composable
private fun InfoCard(overview: AppBetaOverview) {
    val context = LocalContext.current
    val observation = overview.observation
    val program = overview.betaProgram
    DetailCard {
        Text(
            text = stringResource(R.string.observed_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        InfoRow(
            label = stringResource(R.string.info_membership),
            value = stringResource(
                (observation?.observedMembership ?: ObservedMembership.UNKNOWN).labelRes(),
            ),
        )
        InfoRow(
            label = stringResource(R.string.info_live_status),
            value = stringResource((observation?.liveStatus ?: LiveBetaStatus.UNKNOWN).labelRes()),
        )
        program?.let {
            InfoRow(
                label = stringResource(R.string.known_beta_title),
                value = stringResource(it.knownStatus.labelRes()),
            )
            it.notes?.let { notes ->
                Text(
                    notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        TextButton(onClick = { openPlayPage(context, overview.app.packageName) }) {
            Text(stringResource(R.string.open_play_page))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The user's own optional override plus their bookkeeping (checked, note). */
@Composable
private fun MarkingCard(
    overview: AppBetaOverview,
    onSetState: (UserBetaState) -> Unit,
    onMarkChecked: () -> Unit,
    onSaveNote: (String) -> Unit,
) {
    val selected = overview.userStatus?.state ?: UserBetaState.UNKNOWN
    DetailCard {
        Text(stringResource(R.string.my_status), style = MaterialTheme.typography.titleSmall)
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
        HorizontalDivider()
        CheckedRow(overview, onMarkChecked)
        NoteSection(overview, onSaveNote)
    }
}

@Composable
private fun CheckedRow(overview: AppBetaOverview, onMarkChecked: () -> Unit) {
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
            shape = MaterialTheme.shapes.medium,
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
