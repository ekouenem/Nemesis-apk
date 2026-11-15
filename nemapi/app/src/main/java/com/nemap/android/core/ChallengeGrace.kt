package com.nemap.android.core

/**
 * Attente d'une réaction NATIVE de la page après un refus de vérification de
 * sécurité.
 *
 * Le provider peut résoudre lui-même le challenge (PoW) et poursuivre la
 * conversation : pendant ce temps, l'application ne doit surtout pas renvoyer
 * la requête — un nouvel essai immédiat est le motif qui fait basculer un
 * compte en blocage.
 *
 * 45 s : le PoW de DeepSeek (`DeepSeekHashV1`, ~144 000 tours de SHA3-256 en
 * WebAssembly) est rapide sur un poste de bureau mais peut prendre nettement
 * plus longtemps sur un appareil mobile, et davantage encore si le WebView est
 * en arrière-plan (minuteries et Web Workers ralentis par le navigateur).
 */
internal class ChallengeGrace(private val waitMs: Long = 45_000L) {
    private var record = Long.MIN_VALUE
    private var startedAt = 0L
    fun shouldWait(id: Long, now: Long): Boolean {
        if (id != record) { record = id; startedAt = now }
        return now - startedAt < waitMs
    }
}
