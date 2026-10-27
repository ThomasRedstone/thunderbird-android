package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

@Composable
internal fun rememberMailSortViewport(sort: MailSort, busy: Boolean): LazyListState {
    val list = rememberLazyListState()
    val latestBusy by rememberUpdatedState(busy)
    LaunchedEffect(sort) {
        snapshotFlow { latestBusy }.first { !it }
        list.scrollToItem(0)
    }
    return list
}
