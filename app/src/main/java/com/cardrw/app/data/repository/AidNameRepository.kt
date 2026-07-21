package com.cardrw.app.data.repository

/**
 * Mapping local AID → nom lisible (CDC §3.3).
 * Presets intégrés + noms saisis utilisateur (Room en v1).
 */
interface AidNameRepository {
    fun nameFor(aidHex: String): String?
    fun put(aidHex: String, name: String)
    fun all(): Map<String, String>
}

class InMemoryAidNameRepository : AidNameRepository {
    private val map = linkedMapOf(
        // Presets métier d’exemple (CDC §10.7) — clés de démo uniquement, jamais prod
        "01F402" to "Badge d'accès (preset)",
        "01F403" to "Porte-monnaie (preset)",
        "01F404" to "Compteur / journal (preset)",
    )

    override fun nameFor(aidHex: String): String? = map[aidHex.uppercase()]

    override fun put(aidHex: String, name: String) {
        map[aidHex.uppercase()] = name
    }

    override fun all(): Map<String, String> = map.toMap()
}
