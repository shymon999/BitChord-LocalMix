package com.music.bitchord.localmix.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun LocalRadioChoiceHost() {
    val request by LocalRadioSeeds.choice.collectAsStateWithLifecycle()
    request?.let { LocalRadioChoiceDialog(it) }
}

@Composable
private fun LocalRadioChoiceDialog(request: LocalRadioChoiceRequest) {
    val scope = rememberCoroutineScope()
    var query by remember(request) { mutableStateOf(request.initialQuery) }
    var shown by remember(request) { mutableStateOf(request.candidates) }
    var searching by remember(request) { mutableStateOf(false) }
    var searchFailed by remember(request) { mutableStateOf(false) }
    var searched by remember(request) { mutableStateOf(false) }
    var searchJob by remember(request) { mutableStateOf<Job?>(null) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        searching = true
        searchFailed = false
        searchJob = scope.launch {
            LocalRadioSeeds.manualSearch(request, q)
                .onSuccess { shown = it }
                .onFailure { searchFailed = true }
            searched = true
            searching = false
        }
    }

    AlertDialog(
        onDismissRequest = { LocalRadioSeeds.answer(request, LocalRadioChoiceAnswer.Cancelled) },
        title = { Text(stringResource(R.string.localmix_radio_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = listOfNotNull(
                        request.localTitle,
                        request.localArtist.takeIf { it.isNotBlank() },
                        request.localAlbum,
                        request.localDurationMs?.let { formatSeconds((it / 1000).toInt()) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (request.declinedBefore) {
                            R.string.localmix_radio_choice_declined_before
                        } else {
                            R.string.localmix_radio_choice_message
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.localmix_radio_choice_search_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { runSearch() }, enabled = !searching) {
                        Text(stringResource(R.string.localmix_radio_choice_search))
                    }
                }
                when {
                    searching -> CircularProgressIndicator(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(24.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                    searchFailed -> Text(
                        stringResource(R.string.localmix_radio_choice_search_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    shown.isEmpty() -> Text(
                        stringResource(
                            if (searched) {
                                R.string.localmix_radio_choice_no_results
                            } else {
                                R.string.localmix_radio_choice_no_candidates
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.videoId }) { candidate ->
                            CandidateRow(candidate) {
                                LocalRadioSeeds.answer(request, LocalRadioChoiceAnswer.Picked(candidate))
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { LocalRadioSeeds.answer(request, LocalRadioChoiceAnswer.NoneCorrect) }) {
                Text(stringResource(R.string.localmix_radio_choice_none))
            }
        },
        dismissButton = {
            TextButton(onClick = { LocalRadioSeeds.answer(request, LocalRadioChoiceAnswer.Cancelled) }) {
                Text(stringResource(R.string.localmix_radio_choice_cancel))
            }
        },
    )
}

@Composable
private fun CandidateRow(candidate: RadioSeedCandidate, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = candidate.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = candidate.artists.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val details = listOfNotNull(
            candidate.album?.takeIf { it.isNotBlank() },
            candidate.durationSec?.let(::formatSeconds),
        )
        if (details.isNotEmpty()) {
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatSeconds(total: Int): String {
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
