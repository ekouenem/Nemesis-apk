package com.nemap.android.core

/**
 * Sante du moteur DOM : distingue une lecture lente isolee d'un renderer
 * durablement bloque. Volontairement sans dependance Android pour rester
 * testable sur JVM. Aucune session, cookie ou donnee utilisateur n'est stockee.
 */
object DomHealth {
    const val UNRESPONSIVE_AFTER = 3

    @Volatile var consecutiveTimeouts: Int = 0
        private set
    @Volatile var unresponsive: Boolean = false
        private set
    @Volatile var lastSuccessAt: Long = 0L
        private set
    @Volatile var lastTimeoutAt: Long = 0L
        private set
    @Volatile var lastLagMs: Long = 0L
        private set
    @Volatile var totalTimeouts: Long = 0L
        private set
    @Volatile var restarts: Long = 0L
        private set

    @Synchronized fun noteSuccess() {
        consecutiveTimeouts = 0
        unresponsive = false
        lastSuccessAt = System.currentTimeMillis()
    }

    /** @return true quand le moteur est considere comme bloque. */
    @Synchronized fun noteTimeout(timeoutMs: Long, lagMs: Long): Boolean {
        consecutiveTimeouts++
        totalTimeouts++
        lastTimeoutAt = System.currentTimeMillis()
        lastLagMs = lagMs
        if (consecutiveTimeouts >= UNRESPONSIVE_AFTER) unresponsive = true
        return unresponsive
    }

    /** Uniquement sur redemarrage explicite demande par l'utilisateur. */
    @Synchronized fun noteRestart() {
        consecutiveTimeouts = 0
        unresponsive = false
        lastLagMs = 0L
        lastSuccessAt = System.currentTimeMillis()
        restarts++
    }

    /** Diagnostic lisible, sans aucune donnee de session. */
    fun summary(): String =
        "echecs consecutifs=$consecutiveTimeouts, total=${totalTimeouts}L, " +
            "retard looper=${lastLagMs}ms, bloque=$unresponsive, " +
            "redemarrages=$restarts, derniere reussite=$lastSuccessAt"
}
