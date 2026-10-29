package dev.apollo.approvals

import dev.apollo.calypso.Biometric

/** How much of Owner an action needs. Ordered: a later one is stricter. */
enum class Level(val wire: String, val word: String) {
    FREE("free", "Does it, tells you with Undo"),
    TAP("tap", "Asks, you tap Yes"),
    FINGERPRINT("fingerprint", "Needs your fingerprint"),
    NEVER("never", "Never, whoever asks");

    /** Free, Tap, Fingerprint, Never. */
    val label get() = wire.replaceFirstChar { it.uppercase() }

    companion object { fun of(wire: String?): Level? = entries.firstOrNull { it.wire == wire } }
}

/** The pure rules over the approvals rows, so they are tested as they run. */
object Rules {
    /** An action nobody wrote a row for asks first. */
    fun levelFor(rules: Map<String, Level>, action: String): Level = rules[action] ?: Level.TAP

    /** Only stricter: an agent's rule may raise a level, never lower or remove one. */
    fun canAdd(rules: Map<String, Level>, action: String, level: Level): Boolean = level >= levelFor(rules, action)

    /** What his Yes on a queued ask needs: the stricter of the level it was queued at and his rule now; null on never. */
    fun atAnswer(queued: Level, now: Level): Level? = if (now == Level.NEVER) null else maxOf(queued, now)

    /** Right before it runs: the approval he gave ([given]) still covers what his rule needs now. */
    fun stillCovers(given: Level, queued: Level, now: Level): Boolean = atAnswer(queued, now)?.let { it <= given } == true

    /** What his pick of [to] for a row now at [from] needs: nothing (same level), his fingerprint (looser), or just the write (stricter, as Athena may). */
    fun change(from: Level, to: Level): Change = when {
        to == from -> Change.NONE
        to < from -> Change.FINGERPRINT
        else -> Change.WRITE
    }

    /** [change] from the row as it is in [rules] now, never from what a screen showed (Athena may have raised it since). */
    fun pick(rules: Map<String, Level>, action: String, to: Level): Change = change(levelFor(rules, action), to)

    /** The approval row an action is checked under: an alias's handler declares its row in [aliases] (scan F8). */
    fun key(aliases: Map<String, String>, action: String): String = aliases[action] ?: action

    /** The Approvals screen: every row, plus each action the phone can run that has none yet (it asks: TAP). */
    fun listed(rows: List<RuleRow>, actions: Collection<String>): List<RuleRow> {
        val have = rows.map { it.action }.toSet()
        return (rows + actions.filter { it !in have }.distinct().map { RuleRow(it, Level.TAP, "no rule yet", 0L) }).sortedBy { it.action }
    }
}

enum class Change { NONE, WRITE, FINGERPRINT }

/** Asks being answered: only the first answer to an ask gets it (ids are never reused, so a claim is never given back). */
object AskClaims {
    private val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun claim(id: String): Boolean = ids.add(id)
}

/** The approvals table behind Apollo's actions. Athena's `add_rule` lands in [addStricter]; loosening is his, by fingerprint. */
class Approvals(private val db: ApolloDb, private val clock: () -> Long = System::currentTimeMillis) {

    fun levelFor(action: String): Level = Rules.levelFor(db.rules(), action)

    /** False when it would loosen anything (the row is then left as it was). Read and write under one lock. */
    fun addStricter(action: String, level: Level, by: String): Boolean = synchronized(LOCK) {
        if (!Rules.canAdd(db.rules(), action, level)) return false
        db.put(action, level, by, clock())
        true
    }

    /**
     * His pick from the row's menu: one fingerprint when it loosens, none when it tightens. Decided from the row as it is
     * in the db at that moment (Rules.pick, under the lock a tighten writes in), not the level his screen showed.
     */
    fun set(action: String, to: Level, bio: Biometric, onDone: () -> Unit, onFail: (String) -> Unit) {
        val change = synchronized(LOCK) {
            Rules.pick(db.rules(), action, to).also { if (it == Change.WRITE) db.put(action, to, "Owner", clock()) }
        }
        when (change) {
            Change.NONE -> Unit
            Change.WRITE -> onDone()
            Change.FINGERPRINT -> bio.authenticate("Change $action", "to: ${to.word}", null, { synchronized(LOCK) { db.put(action, to, "Owner", clock()) }; onDone() }, onFail)
        }
    }

    fun rows(): List<RuleRow> = db.rows()

    private companion object { val LOCK = Any() }
}
