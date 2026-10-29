package dev.apollo

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** A saved position: a list's first item and its offset, a column's scroll ([a]), or a page's scrollY and url. */
data class Mark(val a: Int, val b: Int = 0, val url: String? = null)

/**
 * Where each screen on the back stack (and the one showing) was scrolled, by spot and part, so Back lands exactly there.
 * A spot that leaves the stack (popped, or a nav tab starting over) loses its marks. Pure.
 */
class ScrollBook {
    private var live = emptySet<String>()
    private val marks = HashMap<String, Mark>()

    /** The spots that may keep marks: the stack and the one showing. */
    @Synchronized fun live(spots: Collection<Spot>) {
        live = spots.map { it.wire }.toSet()
        marks.keys.retainAll { it.substringBefore(SEP) in live }
    }

    /** A screen leaving: its mark is kept while its spot is live, dropped otherwise. */
    @Synchronized fun leave(spot: String, part: String, m: Mark) {
        if (spot in live) marks[spot + SEP + part] = m else marks.remove(spot + SEP + part)
    }

    @Synchronized fun get(spot: String, part: String): Mark? = marks[spot + SEP + part]

    companion object {
        private const val SEP = '\u001e'
        val main = ScrollBook()
    }
}

/** The spot (wire) the screens being drawn belong to; MainActivity provides it. */
val LocalSpot = compositionLocalOf { "" }

/** This screen's mark from when he left it, if Back brought him here. */
@Composable
fun backMark(part: String): Mark? { val spot = LocalSpot.current; return remember(spot, part) { ScrollBook.main.get(spot, part) } }

/** A list that comes back where he left it: scrolled once its items have loaded, saved when it leaves. */
@Composable
fun rememberBackList(part: String): LazyListState {
    val spot = LocalSpot.current
    val state = remember(spot, part) { LazyListState() }
    val saved = backMark(part)
    LaunchedEffect(spot, part) {
        val m = saved ?: return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > m.a }
        state.scrollToItem(m.a, m.b)
    }
    DisposableEffect(spot, part) { onDispose { ScrollBook.main.leave(spot, part, Mark(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)) } }
    return state
}

/** A scrolling column that comes back where he left it. */
@Composable
fun rememberBackScroll(part: String): ScrollState {
    val spot = LocalSpot.current
    val saved = backMark(part)
    val state = remember(spot, part) { ScrollState(saved?.a ?: 0) }
    DisposableEffect(spot, part) { onDispose { ScrollBook.main.leave(spot, part, Mark(state.value)) } }
    return state
}
