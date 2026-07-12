package org.jarsi.betascout.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.jarsi.betascout.R
import org.jarsi.betascout.work.BetaScanWorker

/** Thin progress row for the main screen: visible only while a scan runs, so an
 *  idle front page spends no space on scanning — the controls live in settings. */
@Composable
fun ScanProgressStrip(
    state: ScanUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.cancelling) {
                Text(
                    text = stringResource(R.string.account_scan_cancelling),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val progress = state.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.index / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (progress != null) {
                            stringResource(
                                R.string.account_scan_progress,
                                progress.index,
                                progress.total,
                                progress.currentLabel,
                            )
                        } else {
                            stringResource(R.string.scan_card_starting)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.account_scan_cancel))
                    }
                }
            }
        }
    }
}

/**
 * The scanning system's face in the UI: what happened last, what is happening
 * now, and the one action that makes sense in that state. Shared by the main
 * screen and the account screen so scanning never feels hidden.
 */
@Composable
fun ScanStatusCard(
    state: ScanUiState,
    onScanNow: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.cancelling -> {
                    Text(
                        text = stringResource(R.string.account_scan_cancelling),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.busy -> {
                    val progress = state.progress
                    if (progress != null) {
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
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.scan_card_starting),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.account_scan_cancel))
                    }
                }

                !state.signedIn -> {
                    Text(
                        text = stringResource(
                            if (state.needsLogin) R.string.account_relogin else R.string.scan_card_signed_out,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.needsLogin) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.account_signin))
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val lastScan = state.lastScan
                            if (lastScan != null) {
                                val dateFormat = remember {
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                }
                                Text(
                                    text = stringResource(
                                        R.string.scan_card_title_scanned,
                                        dateFormat.format(Date(lastScan.at)),
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.scan_card_subtitle, lastScan.joined),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.account_no_scan_yet),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(onClick = onScanNow) {
                            Text(stringResource(R.string.scan_card_button))
                        }
                    }
                }
            }
            state.error?.let { error ->
                Text(
                    text = when (error) {
                        BetaScanWorker.ERROR_SCAN_IN_PROGRESS ->
                            stringResource(R.string.account_scan_in_progress)
                        else -> stringResource(R.string.account_error, error)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
