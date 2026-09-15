package np.com.nepalikeyboard.engine

/**
 * Bundled bigram frequency model.
 *
 * Backed by a two-level map (`first -> second -> count`) built once during
 * lexicon loading and never mutated, so concurrent reads from the candidate
 * worker need no synchronisation. The learned (per-user) bigrams live in
 * [np.com.nepalikeyboard.data.LearningRepository] and are blended with these at
 * ranking time, so a fresh install still predicts sensible next words.
 */
class BiGramModel internal constructor(
    private val table: HashMap<String, HashMap<String, Int>>,
) {

    val isEmpty: Boolean get() = table.isEmpty()

    val size: Int get() = table.size

    /** Raw co-occurrence count for `first -> second`. */
    fun score(first: String, second: String): Int {
        if (table.isEmpty() || first.isEmpty() || second.isEmpty()) return 0
        return table[first]?.get(second) ?: 0
    }

    /** Most frequent successors of [first], descending. */
    fun successors(first: String, limit: Int): List<Pair<String, Int>> {
        val bucket = table[first] ?: return emptyList()
        if (bucket.isEmpty()) return emptyList()
        return bucket.entries.asSequence()
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
            .toList()
    }

    companion object {
        val Empty: BiGramModel = BiGramModel(HashMap(0))
    }
}
