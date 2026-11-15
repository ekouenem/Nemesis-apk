package com.nemap.android.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Cadence d'envoi et refroidissement après un refus du provider.
 *
 * Pourquoi ce module existe :
 * une vérification de sécurité refusée (PoW/challenge) est un **signal de risque**
 * pour DeepSeek, pas une panne réseau. Rejouer immédiatement le même envoi
 * (ce que faisaient les 3 tentatives du client Python, espacées d'une seconde)
 * transforme un refus ponctuel en série de refus sur le même compte, et c'est
 * exactement le motif qui fait basculer un compte en blocage.
 *
 * Règles :
 *  1. deux envois ne s'enchaînent pas moins de [MIN_INTERVAL_MS] ;
 *  2. après un challenge, aucun nouvel envoi pendant [CHALLENGE_COOLDOWN_MS] ;
 *  3. le blocage de cadence est un code d'erreur explicite : le client sait
 *     qu'il doit attendre au lieu de réessayer tout seul.
 */
object Pacing {

    /** Intervalle minimal entre deux envois au provider (compte unique). */
    const val MIN_INTERVAL_MS = 8_000L

    /** Refroidissement après une vérification de sécurité refusée. */
    const val CHALLENGE_COOLDOWN_MS = 180_000L

    /** Nombre de refus de sécurité acceptés avant un refroidissement plus long. */
    const val LONG_COOLDOWN_MS = 900_000L

    private val lastSubmitAt = AtomicLong(0L)
    private val cooldownUntil = AtomicLong(0L)
    private val challengeCount = AtomicLong(0L)

    /** Millisecondes restantes de refroidissement (0 si l'on peut envoyer). */
    fun cooldownRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val until = cooldownUntil.get()
        return if (until <= now) 0L else until - now
    }

    /**
     * Délai restant pour respecter la cadence normale entre deux envois.
     * Ce délai doit être attendu silencieusement par le moteur ; il n'est pas
     * un refus du provider et ne doit jamais devenir une erreur HTTP 429.
     */
    fun minimumIntervalWaitMs(now: Long = System.currentTimeMillis()): Long {
        val last = lastSubmitAt.get()
        if (last <= 0L) return 0L
        val elapsed = now - last
        return if (elapsed >= MIN_INTERVAL_MS) 0L
        else (MIN_INTERVAL_MS - elapsed).coerceIn(0L, MIN_INTERVAL_MS)
    }

    /** À appeler juste avant l'envoi réel. */
    fun noteSubmit(now: Long = System.currentTimeMillis()) {
        lastSubmitAt.set(now)
    }

    /** À appeler quand le provider refuse une vérification de sécurité. */
    fun noteChallenge(now: Long = System.currentTimeMillis()) {
        val n = challengeCount.incrementAndGet()
        val duration = if (n >= 3L) LONG_COOLDOWN_MS else CHALLENGE_COOLDOWN_MS
        cooldownUntil.set(now + duration)
        NemLog.w("dom", "refus de sécurité du provider : refroidissement ${duration / 1000} s (refus #$n)")
    }

    /** Remet le compteur de refus à zéro (session provider réinitialisée). */
    fun resetChallenges() {
        challengeCount.set(0L)
        cooldownUntil.set(0L)
    }
}
