package com.nemap.android.core

/**
 * Catalogue fermé des codes d'erreur (cf. 01-SPEC-DEEPSEEK-WEB.md §8).
 * Le code est stable et fait partie du contrat public de l'API.
 */
class NemApiException(
    val code: String,
    override val message: String
) : Exception(message) {

    companion object {
        const val PROVIDER_NOT_CONFIGURED = "provider_not_configured"
        const val PROVIDER_BUSY = "provider_busy"
        const val PROVIDER_NOT_READY = "provider_not_ready"
        const val SELECTOR_MISSING = "selector_missing"
        const val SELECTOR_AMBIGUOUS = "selector_ambiguous"
        const val LOGIN_REQUIRED = "login_required"
        const val USER_INTERVENTION_REQUIRED = "user_intervention_required"
        const val NETWORK_ERROR = "network_error"
        const val STREAM_INTERRUPTED = "stream_interrupted"
        const val TIMEOUT = "timeout"
        /** Renderer vivant mais durablement muet : exige un redemarrage explicite. */
        const val DOM_UNRESPONSIVE = "dom_unresponsive"
        const val CANCELLED = "cancelled"
        const val UPSTREAM_ERROR = "upstream_error"
        /**
         * Refus explicite du provider pour une vérification de sécurité (PoW/challenge).
         * CE CODE N'EST JAMAIS RETENTABLE : un nouvel envoi immédiat après un challenge
         * fait monter le score de risque du compte jusqu'au blocage. Le client doit
         * laisser la page résoudre le challenge et rejouer plus tard, ou demander
         * une action humaine.
         */
        const val SECURITY_CHALLENGE = "security_challenge"
        /** Refus temporaire du provider : cadence trop élevée, compte en cooldown. */
        const val PROVIDER_COOLDOWN = "provider_cooldown"

        fun notReady(msg: String = "La page du provider n'est pas prête. Ouvre l'onglet et vérifie la connexion.") =
            NemApiException(PROVIDER_NOT_READY, msg)

        fun login() = NemApiException(
            LOGIN_REQUIRED,
            "La session web a expiré. Reconnecte-toi dans l'onglet du provider."
        )

        fun busy() = NemApiException(
            PROVIDER_BUSY,
            "Une génération est déjà en cours sur ce provider."
        )

        fun timeout(what: String) = NemApiException(TIMEOUT, what)

        fun domUnresponsive() = NemApiException(
            DOM_UNRESPONSIVE,
            "Le moteur DOM ne répond plus après plusieurs délais dépassés. "
                + "Redémarre-le depuis Paramètres → « Redémarrer le moteur DOM ». "
                + "Le message n’est pas renvoyé automatiquement ; vérifie la discussion avant de reprendre."
        )

        fun cancelled() = NemApiException(CANCELLED, "La génération a été annulée.")

        fun challenge(msg: String) = NemApiException(SECURITY_CHALLENGE, msg)

        fun cooldown(msLeft: Long) = NemApiException(
            PROVIDER_COOLDOWN,
            "DeepSeek a refusé une vérification de sécurité. Aucun nouveau message n’a été envoyé ; " +
                "la pause de sécurité restante est de ${(msLeft / 1000).coerceAtLeast(1)} s. " +
                "Ouvre la conversation dans NemApi pour vérifier la session."
        )
    }
}

object ErrorTypes {
    /** type OpenAI associé au code interne */
    fun typeFor(code: String): String = when (code) {
        NemApiException.PROVIDER_BUSY,
        NemApiException.PROVIDER_NOT_CONFIGURED,
        NemApiException.PROVIDER_NOT_READY,
        NemApiException.SELECTOR_MISSING,
        NemApiException.SELECTOR_AMBIGUOUS,
        NemApiException.LOGIN_REQUIRED,
        NemApiException.USER_INTERVENTION_REQUIRED -> "invalid_request_error"
        NemApiException.CANCELLED -> "cancelled"
        NemApiException.TIMEOUT -> "timeout"
        NemApiException.SECURITY_CHALLENGE,
        NemApiException.PROVIDER_COOLDOWN -> "rate_limit_error"
        NemApiException.DOM_UNRESPONSIVE,
        NemApiException.NETWORK_ERROR,
        NemApiException.STREAM_INTERRUPTED,
        NemApiException.UPSTREAM_ERROR -> "api_error"
        else -> "api_error"
    }
}
