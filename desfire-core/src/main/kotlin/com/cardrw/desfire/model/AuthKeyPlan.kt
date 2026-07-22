package com.cardrw.desfire.model

/**
 * Candidats de clé pour une intention d’auth (U3 / moniteur diagnostic).
 * Slot carte 0–13 — distinct du coffre appareil.
 */
data class KeyCandidate(
    val keyNo: Int,
    val roleLabel: String,
)

enum class AuthBarrier {
    /** Free ou session déjà suffisante — pas de sheet. */
    NONE,
    /** Never — ne pas demander de clé. */
    NEVER,
    /** Une ou plusieurs clés requises. */
    NEEDS_KEY,
}

/**
 * Intention d’authentification (matériau + slot carte).
 */
sealed class AuthIntent {
    /** Lire le contenu d’un fichier (droits R / RW). */
    data class ReadFile(val fileNo: Int, val rights: AccessRights) : AuthIntent()

    /** Écrire un fichier (W / RW) — v1. */
    data class WriteFile(val fileNo: Int, val rights: AccessRights) : AuthIntent()

    /**
     * Lister structure (GetKeySettings / FileIDs / FileSettings).
     * @param freeDirectoryListWithoutMaster null = inconnu → oriente maître 0 + allowAnyKey.
     */
    data class ExploreStructure(
        val freeDirectoryListWithoutMaster: Boolean?,
    ) : AuthIntent()

    /** Auth générique (session bar, labo) — tous les slots. */
    data object Generic : AuthIntent()
}

/**
 * Plan d’UI pour la sheet auth.
 */
data class AuthKeyPlan(
    val intent: AuthIntent,
    val barrier: AuthBarrier,
    val candidates: List<KeyCandidate>,
    /** True : afficher 0–13 (expert / info inconnue). */
    val allowAnyKey: Boolean,
    val preferKeyNo: Int?,
    val titleHint: String,
    val detailMessage: String?,
) {
    val needsSheet: Boolean get() = barrier == AuthBarrier.NEEDS_KEY
}

/**
 * Calcule les n° de clé capables d’une intention.
 *
 * Heuristique préselection (si pas de session candidate) :
 * 1. Droit le plus spécifique (R avant RW pour lecture ; W avant RW pour écriture)
 * 2. Plus petit keyNo
 */
object AuthKeyPlanner {

    fun plan(intent: AuthIntent, currentSessionKey: Int? = null): AuthKeyPlan = when (intent) {
        is AuthIntent.ReadFile -> planRead(intent, currentSessionKey)
        is AuthIntent.WriteFile -> planWrite(intent, currentSessionKey)
        is AuthIntent.ExploreStructure -> planStructure(intent, currentSessionKey)
        AuthIntent.Generic -> planGeneric(currentSessionKey)
    }

    private fun planRead(intent: AuthIntent.ReadFile, session: Int?): AuthKeyPlan {
        val rights = intent.rights
        val fileNo = intent.fileNo
        if (rights.canReadWith(session)) {
            return AuthKeyPlan(
                intent = intent,
                barrier = AuthBarrier.NONE,
                candidates = emptyList(),
                allowAnyKey = false,
                preferKeyNo = session,
                titleHint = "Lire fichier $fileNo",
                detailMessage = "Session déjà suffisante pour lire.",
            )
        }
        if (rights.isReadNever && (rights.readWrite and 0x0F) == 0x0F) {
            return AuthKeyPlan(
                intent = intent,
                barrier = AuthBarrier.NEVER,
                candidates = emptyList(),
                allowAnyKey = false,
                preferKeyNo = null,
                titleHint = "Lire fichier $fileNo",
                detailMessage = "Lecture interdite (Never) — aucune clé ne permet cette opération.",
            )
        }
        val candidates = rights.readKeyCandidates()
        if (candidates.isEmpty()) {
            // Cas limite : R=Never mais RW free? canReadWith Free on R only; if R free handled above
            return AuthKeyPlan(
                intent = intent,
                barrier = AuthBarrier.NEVER,
                candidates = emptyList(),
                allowAnyKey = false,
                preferKeyNo = null,
                titleHint = "Lire fichier $fileNo",
                detailMessage = "Aucun n° de clé lisible (R=${rights.readLabel}, RW=${rights.readWriteLabel}).",
            )
        }
        val prefer = preferSpecificThenMin(candidates, specificRoles = listOf("Read", "Read&Write"))
        return AuthKeyPlan(
            intent = intent,
            barrier = AuthBarrier.NEEDS_KEY,
            candidates = candidates,
            allowAnyKey = false,
            preferKeyNo = prefer,
            titleHint = "Lire fichier $fileNo",
            detailMessage = "Clé(s) possibles : " +
                candidates.joinToString(" · ") { "n°${it.keyNo} (${it.roleLabel})" },
        )
    }

    private fun planWrite(intent: AuthIntent.WriteFile, session: Int?): AuthKeyPlan {
        val rights = intent.rights
        val fileNo = intent.fileNo
        if (rights.canWriteWith(session)) {
            return AuthKeyPlan(
                intent = intent,
                barrier = AuthBarrier.NONE,
                candidates = emptyList(),
                allowAnyKey = false,
                preferKeyNo = session,
                titleHint = "Écrire fichier $fileNo",
                detailMessage = "Session déjà suffisante pour écrire.",
            )
        }
        val w = rights.write and 0x0F
        val rw = rights.readWrite and 0x0F
        if (w == 0x0F && rw == 0x0F) {
            return AuthKeyPlan(
                intent = intent,
                barrier = AuthBarrier.NEVER,
                candidates = emptyList(),
                allowAnyKey = false,
                preferKeyNo = null,
                titleHint = "Écrire fichier $fileNo",
                detailMessage = "Écriture interdite (Never).",
            )
        }
        val candidates = rights.writeKeyCandidates()
        if (candidates.isEmpty()) {
            return AuthKeyPlan(
                intent = intent,
                barrier = AuthBarrier.NEVER,
                candidates = emptyList(),
                allowAnyKey = false,
                preferKeyNo = null,
                titleHint = "Écrire fichier $fileNo",
                detailMessage = "Aucun n° de clé pour écrire.",
            )
        }
        val prefer = preferSpecificThenMin(candidates, specificRoles = listOf("Write", "Read&Write"))
        return AuthKeyPlan(
            intent = intent,
            barrier = AuthBarrier.NEEDS_KEY,
            candidates = candidates,
            allowAnyKey = false,
            preferKeyNo = prefer,
            titleHint = "Écrire fichier $fileNo",
            detailMessage = "Clé(s) possibles : " +
                candidates.joinToString(" · ") { "n°${it.keyNo} (${it.roleLabel})" },
        )
    }

    private fun planStructure(intent: AuthIntent.ExploreStructure, session: Int?): AuthKeyPlan {
        val freeList = intent.freeDirectoryListWithoutMaster
        when (freeList) {
            true -> {
                // Structure souvent sans auth
                return AuthKeyPlan(
                    intent = intent,
                    barrier = AuthBarrier.NONE,
                    candidates = emptyList(),
                    allowAnyKey = true,
                    preferKeyNo = session,
                    titleHint = "Explorer la structure",
                    detailMessage = "Free-list ON — structure souvent lisible sans auth.",
                )
            }
            false -> {
                if (session == 0) {
                    return AuthKeyPlan(
                        intent = intent,
                        barrier = AuthBarrier.NONE,
                        candidates = listOf(KeyCandidate(0, "Master")),
                        allowAnyKey = false,
                        preferKeyNo = 0,
                        titleHint = "Explorer la structure",
                        detailMessage = "Session maître (clé 0) déjà active.",
                    )
                }
                return AuthKeyPlan(
                    intent = intent,
                    barrier = AuthBarrier.NEEDS_KEY,
                    candidates = listOf(KeyCandidate(0, "Master")),
                    allowAnyKey = false,
                    preferKeyNo = 0,
                    titleHint = "Explorer la structure",
                    detailMessage = "Free-list OFF → key settings / FileIDs souvent réservés à la clé maître (n°0).",
                )
            }
            null -> {
                // Inconnu : oriente 0, laisse expert
                return AuthKeyPlan(
                    intent = intent,
                    barrier = AuthBarrier.NEEDS_KEY,
                    candidates = listOf(KeyCandidate(0, "Master (probable)")),
                    allowAnyKey = true,
                    preferKeyNo = 0,
                    titleHint = "Explorer la structure",
                    detailMessage = "Droits structure inconnus — clé maître n°0 souvent requise si free-list OFF.",
                )
            }
        }
    }

    private fun planGeneric(session: Int?): AuthKeyPlan =
        AuthKeyPlan(
            intent = AuthIntent.Generic,
            barrier = AuthBarrier.NEEDS_KEY,
            candidates = emptyList(),
            allowAnyKey = true,
            preferKeyNo = session?.coerceIn(0, 13),
            titleHint = "Authentification AES",
            detailMessage = "Choisis le n° de slot carte et le matériau (coffre ou hex).",
        )

    private fun preferSpecificThenMin(
        candidates: List<KeyCandidate>,
        specificRoles: List<String>,
    ): Int? {
        for (role in specificRoles) {
            val hit = candidates.filter { it.roleLabel == role }.minByOrNull { it.keyNo }
            if (hit != null) return hit.keyNo
        }
        return candidates.minOfOrNull { it.keyNo }
    }
}

/** Candidats lecture : R puis RW (n° 0–13 uniquement). */
fun AccessRights.readKeyCandidates(): List<KeyCandidate> {
    val out = linkedMapOf<Int, KeyCandidate>()
    val r = read and 0x0F
    val rw = readWrite and 0x0F
    if (r in 0..13) out[r] = KeyCandidate(r, "Read")
    if (rw in 0..13) {
        out[rw] = out[rw]?.let {
            // Même n° pour R et RW : libellé combiné
            if (it.roleLabel == "Read") KeyCandidate(rw, "Read / Read&Write") else it
        } ?: KeyCandidate(rw, "Read&Write")
    }
    return out.values.toList()
}

/** Candidats écriture : W puis RW. */
fun AccessRights.writeKeyCandidates(): List<KeyCandidate> {
    val out = linkedMapOf<Int, KeyCandidate>()
    val w = write and 0x0F
    val rw = readWrite and 0x0F
    if (w in 0..13) out[w] = KeyCandidate(w, "Write")
    if (rw in 0..13) {
        out[rw] = out[rw]?.let {
            if (it.roleLabel == "Write") KeyCandidate(rw, "Write / Read&Write") else it
        } ?: KeyCandidate(rw, "Read&Write")
    }
    return out.values.toList()
}

fun AccessRights.canWriteWith(authKeyNo: Int?): Boolean {
    val w = write and 0x0F
    val rw = readWrite and 0x0F
    if (w == 0x0E) return true
    if (w == 0x0F && rw == 0x0F) return false
    if (authKeyNo == null) return false
    val k = authKeyNo and 0x0F
    return k == w || k == rw
}
