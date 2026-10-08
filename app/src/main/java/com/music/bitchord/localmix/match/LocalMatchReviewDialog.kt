package com.music.bitchord.localmix.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.localmix.radio.LocalRadioSeeds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.localmix.equivalent.MatchDoubt
import moe.rukamori.archivetune.localmix.equivalent.MatchReviewBook
import moe.rukamori.archivetune.localmix.radio.LocalRadioLink

@Composable
fun LocalMatchReviewDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    LocalRadioSeeds.attach(context)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val revision by LocalMatches.revision.collectAsStateWithLifecycle()
    val entries = remember(revision) { LocalMatches.entries() }
    val rejections = remember(revision) { LocalMatches.rejections() }
    var radioVersion by remember { mutableIntStateOf(0) }
    val links by produceState(emptyList<LocalRadioLink>(), radioVersion) {
        value = withContext(Dispatchers.IO) { LocalRadioSeeds.links() }
            .sortedWith(compareBy<LocalRadioLink>({ it.localTitle ?: it.localKey }, { it.localKey }))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.localmix_review_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.localmix_review_close)) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TabButton(stringResource(R.string.localmix_review_tab_tracks, entries.size), tab == 0) { tab = 0 }
                    TabButton(stringResource(R.string.localmix_review_tab_detached, rejections.size), tab == 1) { tab = 1 }
                    TabButton(stringResource(R.string.localmix_review_tab_radio, links.size), tab == 2) { tab = 2 }
                }
                HorizontalDivider()
                when (tab) {
                    0 -> TrackMatches(entries)
                    1 -> Detached(rejections)
                    else -> RadioLinks(links) { key ->
                        LocalRadioSeeds.removeLink(key)
                        radioVersion++
                    }
                }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackMatches(entries: List<LocalMatchEntry>) {
    if (entries.isEmpty()) {
        Hint(stringResource(R.string.localmix_review_no_matches))
        return
    }
    val uncertain = entries.count { it.uncertain }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Hint(
                if (uncertain > 0) stringResource(R.string.localmix_review_uncertain_count, uncertain)
                else stringResource(R.string.localmix_review_all_certain),
            )
        }
        items(entries, key = { it.videoId }) { entry ->
            ReviewRow(
                title = listOfNotNull(entry.hit.title ?: entry.videoId, entry.hit.artist).joinToString(" · "),
                detail = "= " + (entry.localLabel ?: entry.hit.path ?: entry.hit.uri),
                note = when {
                    entry.uncertain -> stringResource(R.string.localmix_review_uncertain) + ": " +
                        entry.hit.doubts.orEmpty().map { doubtLabel(it) }.joinToString(", ")
                    entry.confirmed -> stringResource(R.string.localmix_review_confirmed)
                    entry.hit.doubts == null -> stringResource(R.string.localmix_review_not_checked)
                    else -> null
                },
                noteIsWarning = entry.uncertain,
            ) {
                if (!entry.confirmed) {
                    TextButton(onClick = { LocalMatches.confirm(entry.videoId) }) {
                        Text(stringResource(R.string.localmix_review_confirm))
                    }
                }
                TextButton(onClick = { LocalMatches.detach(entry.videoId) }) {
                    Text(stringResource(R.string.localmix_detach_file))
                }
            }
        }
    }
}

@Composable
private fun Detached(rejections: List<MatchReviewBook.Rejection>) {
    if (rejections.isEmpty()) {
        Hint(stringResource(R.string.localmix_review_no_detached))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Hint(stringResource(R.string.localmix_review_detached_hint)) }
        items(rejections, key = { it.catalogId + "|" + it.file.uri }) { r ->
            ReviewRow(
                title = listOfNotNull(r.title ?: r.catalogId, r.artist).joinToString(" · "),
                detail = "≠ " + (LocalMatches.localLabel(r.file.uri) ?: r.file.path ?: r.file.uri),
            ) {
                TextButton(onClick = { LocalMatches.restore(r) }) {
                    Text(stringResource(R.string.localmix_review_restore))
                }
            }
        }
    }
}

@Composable
private fun RadioLinks(links: List<LocalRadioLink>, onRemove: (String) -> Unit) {
    if (links.isEmpty()) {
        Hint(stringResource(R.string.localmix_review_no_radio))
        return
    }
    var confirming by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Hint(stringResource(R.string.localmix_review_radio_hint)) }
        items(links, key = { it.localKey }) { link ->
            val local = listOfNotNull(link.localArtist, link.localTitle).joinToString(" - ")
                .ifBlank { link.uri ?: link.localKey }
            ReviewRow(
                title = local,
                detail = if (link.isNoCorrectMatch) {
                    stringResource(R.string.localmix_review_radio_none)
                } else {
                    "→ " + listOfNotNull(link.title ?: link.videoId, link.artists).joinToString(" · ")
                },
                note = stringResource(
                    if (link.manual) R.string.localmix_review_radio_manual else R.string.localmix_review_radio_auto,
                ),
            ) {
                if (confirming == link.localKey) {
                    TextButton(onClick = {
                        confirming = null
                        onRemove(link.localKey)
                    }) {
                        Text(
                            stringResource(R.string.localmix_review_remove_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    TextButton(onClick = { confirming = link.localKey }) {
                        Text(stringResource(R.string.localmix_review_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(
    title: String,
    detail: String,
    note: String? = null,
    noteIsWarning: Boolean = false,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (noteIsWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
    }
    HorizontalDivider()
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun doubtLabel(doubt: MatchDoubt): String = stringResource(
    when (doubt) {
        MatchDoubt.SOFT_VERSION -> R.string.localmix_doubt_soft_version
        MatchDoubt.DURATION_GAP -> R.string.localmix_doubt_duration
        MatchDoubt.TITLE_VARIANT -> R.string.localmix_doubt_title
        MatchDoubt.ARTISTS_DIFFER -> R.string.localmix_doubt_artists
    },
)
