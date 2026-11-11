package com.nemap.android.core

/** Never mistake the previous assistant turn for the current one in DOM fallback. */
object DomFreshness {
    fun isFresh(ok: Boolean, text: String, blocks: Int, oldText: String, oldBlocks: Int): Boolean =
        ok && text.isNotBlank() && (blocks > oldBlocks || text != oldText)
}
