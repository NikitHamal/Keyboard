package np.com.nepalikeyboard.keyboard

import androidx.compose.runtime.Stable
import kotlin.math.roundToInt

/**
 * Pixel geometry of the keyboard, kept in primitive arrays.
 *
 * Compose lays the keys out once per size change and records the resulting
 * rectangles here; pointer dispatch then hit-tests against these arrays with
 * integer comparisons only. That is what allows the whole-keyboard pointer
 * handler to run without allocating (no Rect, no Offset, no lambda capture per
 * event, no recomposition).
 *
 * The instance is [Stable] and reused for the lifetime of the input view.
 */
@Stable
class KeyboardGeometry {

    private var xs = IntArray(64)
    private var ys = IntArray(64)
    private var hs = IntArray(64)
    private var ws = IntArray(64)

    private var rowTops = IntArray(8)
    private var rowBottoms = IntArray(8)
    private var rowStarts = IntArray(8)
    private var rowCounts = IntArray(8)

    var keyCount: Int = 0
        private set

    var rowCount: Int = 0
        private set

    var widthPx: Int = 0
        private set

    var heightPx: Int = 0
        private set

    /**
     * Recomputes every key rectangle. Called from the Compose measure policy, so
     * it runs only when the layout or the available size actually changes.
     */
    fun update(layout: KeyboardLayout, width: Int, height: Int) {
        widthPx = width
        heightPx = height
        rowCount = layout.rows.size
        keyCount = layout.keyCount
        ensureKeyCapacity(keyCount)
        ensureRowCapacity(rowCount)

        val rows = layout.rows
        var keyCursor = 0
        var yCursor = 0
        // Distribute the height evenly; the final row absorbs rounding error so
        // the keyboard always ends exactly at `height`.
        val baseRowHeight = if (rowCount == 0) 0 else height / rowCount

        for (rowIndex in 0 until rowCount) {
            val row = rows[rowIndex]
            val rowHeight = if (rowIndex == rowCount - 1) height - yCursor else baseRowHeight
            val totalWeight = if (row.totalWeight <= 0f) 1f else row.totalWeight
            val unit = width / totalWeight
            var xCursor = (row.indentLeft * unit).roundToInt()

            rowStarts[rowIndex] = keyCursor
            rowCounts[rowIndex] = row.keys.size
            rowTops[rowIndex] = yCursor
            rowBottoms[rowIndex] = yCursor + rowHeight

            var rowX = xCursor
            for (keyIndex in row.keys.indices) {
                val key = row.keys[keyIndex]
                val isLastInRow = keyIndex == row.keys.lastIndex
                val keyWidth = if (isLastInRow) {
                    (width - rowX).coerceAtLeast(0)
                } else {
                    (key.weight * unit).roundToInt()
                }
                val slot = keyCursor + keyIndex
                xs[slot] = rowX
                ys[slot] = yCursor
                ws[slot] = keyWidth
                hs[slot] = rowHeight
                rowX += keyWidth
            }

            keyCursor += row.keys.size
            yCursor += rowHeight
        }
    }

    /** Index of the key under the pointer, or -1 when the point is between keys. */
    fun hitTest(x: Float, y: Float): Int {
        if (keyCount == 0) return -1
        var rowIndex = -1
        for (index in 0 until rowCount) {
            if (y.toInt() >= rowTops[index] && y < rowBottoms[index]) {
                rowIndex = index
                break
            }
        }
        if (rowIndex < 0) return -1
        val start = rowStarts[rowIndex]
        val count = rowCounts[rowIndex]
        val px = x.toInt()
        for (offset in 0 until count) {
            val slot = start + offset
            if (px >= xs[slot] && px < xs[slot] + ws[slot]) return slot
        }
        return -1
    }

    fun isInside(x: Float, y: Float): Boolean = x >= 0f && x < widthPx && y >= 0f && y < heightPx

    fun left(index: Int): Int = if (index in 0 until keyCount) xs[index] else 0

    fun top(index: Int): Int = if (index in 0 until keyCount) ys[index] else 0

    fun width(index: Int): Int = if (index in 0 until keyCount) ws[index] else 0

    fun height(index: Int): Int = if (index in 0 until keyCount) hs[index] else 0

    fun centerXFraction(index: Int): Float {
        if (index !in 0 until keyCount || widthPx == 0) return 0.5f
        return (xs[index] + ws[index] / 2f) / widthPx
    }

    fun rowTop(row: Int): Int = if (row in 0 until rowCount) rowTops[row] else 0

    fun rowBottom(row: Int): Int = if (row in 0 until rowCount) rowBottoms[row] else 0

    /**
     * Nearest key index for a pointer that left its original key during a slide
     * (used while a long-press popup is open, where the finger is below the
     * keyboard). Returns the original index when the point matches nothing.
     */
    fun nearestKeyIndex(x: Float, y: Float, fallback: Int): Int {
        val hit = hitTest(x, y)
        return if (hit >= 0) hit else fallback
    }

    private fun ensureKeyCapacity(required: Int) {
        if (required <= xs.size) return
        var capacity = xs.size
        while (capacity < required) capacity = capacity shl 1
        xs = xs.copyOf(capacity)
        ys = ys.copyOf(capacity)
        ws = ws.copyOf(capacity)
        hs = hs.copyOf(capacity)
    }

    private fun ensureRowCapacity(required: Int) {
        if (required <= rowTops.size) return
        var capacity = rowTops.size
        while (capacity < required) capacity = capacity shl 1
        rowTops = rowTops.copyOf(capacity)
        rowBottoms = rowBottoms.copyOf(capacity)
        rowStarts = rowStarts.copyOf(capacity)
        rowCounts = rowCounts.copyOf(capacity)
    }
}
