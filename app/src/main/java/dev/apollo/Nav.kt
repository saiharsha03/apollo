package dev.apollo

import dev.apollo.chat.Room

/** One entry of the nav: a place, or a mini app (opens as sub "app:<name>"). */
data class NavEntry(val word: String, val place: Place? = null, val app: String? = null) {
    val sub get() = app?.let { "app:$it" }
}

/**
 * His nav order (phone_setting nav_order, e.g. "chat,app:money,iris,more"). Pure.
 * Without app entries it is the old rule: the places he named, then the rest. With app entries his list is the bar, Home
 * and More always on it (More holds the rest). At most [CAP] entries; the ones past it, and places he left out, go under More.
 */
object Nav {
    const val CAP = 6

    /** More's own rows: word, line, the sub it opens. Search finds them too. */
    val MORE_ROWS = listOf(
        Triple("Atlas", "Atlas's own chat", "atlas"),
        Triple("Calypso", "Sealed items, shielded items and your keys", "calypso"),
        Triple("Senses", "What Apollo can see, and your car", "senses"),
        Triple("Approvals", "What Athena may do on her own", "approvals"),
        Triple("Phone", "Lock, Do Not Disturb, background, NFC tags", "phone"),
        Triple("Server", "Pair with the server, hand Athena her key", "pair"),
        Triple("Home", "Your lights, plugs and fans (Google Home)", "house"),
        Triple("Lemnos", "Your server: health, files, CloudHost", "lemnos"),
        Triple("Usage", "Claude, Codex and Athena's credit", "app:usage"),
        Triple("Logs", "What Apollo did (Clio)", "logs"),
        Triple("What left the phone", "Every host Apollo reached, and why", "netlog"),
        Triple("Backup", "Nightly, locked on the phone (Mnemosyne)", "backup"),
        Triple("Updates", "A newer Apollo from the server", "updates"),
        Triple("Pan", "The Plazmi resident: talk, limits, claim, key", "pan"),
    )

    /** [apps]: the mini apps on the phone, name to title (archived and unknown ones are skipped). Bar, then what's under More. */
    fun order(raw: String?, apps: Map<String, String>): Pair<List<NavEntry>, List<NavEntry>> {
        val listed = raw.orEmpty().split(',').map { it.trim() }.mapNotNull { w ->
            if (w.startsWith("app:", true)) w.substring(4).lowercase().let { n -> apps[n]?.let { NavEntry(it, app = n) } }
            else Place.entries.firstOrNull { it.name.equals(w, true) }?.let { NavEntry(it.word, place = it) }
        }.distinct()
        val places = Place.entries.map { NavEntry(it.word, place = it) }
        if (listed.none { it.app != null }) return (listed + places.filter { it !in listed }) to emptyList()
        val home = places.first { it.place == Place.HOME }
        val more = places.first { it.place == Place.MORE }
        val bar = (if (home in listed) listed else listOf(home) + listed).filter { it != more }
        // Home and More keep their slots wherever he listed Home; the cap cuts only the others.
        val others = bar.filter { it != home }
        val kept = others.take(CAP - 2)
        val shown = kept.toMutableList().apply { add(bar.indexOf(home).coerceAtMost(size), home); add(more) }
        return shown to (others.drop(CAP - 2) + places.filter { it !in bar && it != more })
    }
}

/** Where he is: a place, what's open over it ([sub]), Chat's room, and the thread open in the chat showing. */
data class Spot(val place: Place, val sub: String = "", val room: Room = Room.ATHENA, val thread: String? = null) {
    val home get() = place == Place.HOME && sub.isEmpty() && thread == null
    val wire get() = listOf(place.name, sub, room.name, thread.orEmpty()).joinToString("")

    companion object {
        fun of(wire: String): Spot? = runCatching { wire.split('').let { Spot(Place.valueOf(it[0]), it[1], Room.valueOf(it[2]), it[3].ifEmpty { null }) } }.getOrNull()
    }
}

/**
 * Where Back goes: the spots he came through. Going somewhere remembers where he was; going to a spot already behind
 * him goes back to it (no loops); a nav tab starts over (empty stack). With nothing behind, Back goes Home. Pure.
 */
object BackStack {
    const val MAX = 30

    fun push(stack: List<Spot>, from: Spot, to: Spot): List<Spot> {
        if (to == from) return stack
        val i = stack.indexOf(to)
        if (i >= 0) return stack.take(i)
        return (if (stack.lastOrNull() == from) stack else stack + from).takeLast(MAX)
    }

    /** The spot Back shows and the stack left, or null at Home with nothing behind (the system's Back then leaves Apollo). */
    fun pop(stack: List<Spot>, here: Spot): Pair<Spot, List<Spot>>? = when {
        stack.isNotEmpty() -> stack.last() to stack.dropLast(1)
        !here.home -> Spot(Place.HOME, room = here.room) to emptyList()
        else -> null
    }
}
