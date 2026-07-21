# Reprise session — CardRW

**Dernière mise à jour :** 2026-07-21  
**Machine d’arrêt :** iMac (push GitHub OK)  
**Remote :** `git@github.com:jgzgtxbbdc-ops/CardRW.git` (privé)  
**Commit :** `51a6d52` — `security+sm-api: FACTORY_KEY copy + prepareCommand clear headers`  
**Branche :** `main` = `origin/main`

---

## Où on en est (résumé)

| Jalon | État |
|---|---|
| **v0** lecture identité + apps + journal APDU | ✅ validé terrain (multi-cartes / fabricants) |
| **v0.5** Select AID, AuthenticateAES, SM EV1, explorateur, ReadData (y compris FULL) | ✅ validé terrain |
| **Git** init + push GitHub privé | ✅ |
| **FACTORY_KEY** copie défensive | ✅ (dans `51a6d52`) |
| **prepareCommand** en-têtes clairs (prêt Write/ChangeKey) | ✅ (dans `51a6d52`) |
| Passe UX (UI peu pratique) | ⬜ pas commencée |
| Décision EV2 avant écritures massives | ⬜ à trancher (parc cartes) |
| **v1** Write / Create / ChangeKey / dumps | ⬜ suivant majeur |

**Promesse produit actuelle :** lecteur DESFire pédagogique + auth + lecture fichiers protégés.  
**Pas encore :** écriture, templates, série, formatage, SM EV2, open source.

---

## Au démarrage sur l’autre ordi

```bash
# Si pas encore cloné
git clone git@github.com:jgzgtxbbdc-ops/CardRW.git
cd CardRW

# Si déjà cloné
git pull origin main

./gradlew :desfire-core:test :app:assembleDebug
```

1. Ouvrir le dossier dans **Android Studio** → sync Gradle.  
2. `local.properties` se régénère tout seul (ne pas le committer).  
3. Run sur téléphone NFC + 1 carte vierge + 1 carte encodée labo.  
4. Sanity check rapide : lecture profil → auth usine → explore → ReadData.

**Docs utiles :**

- CDC : `docs/cahier-des-charges-desfire-ev3.md`  
- Labo : `docs/NOTES_LABO.md`  
- Commandes : `docs/ANNEXE_A_COMMANDES.md`  
- Cette note : `docs/REPRISE.md`

---

## Brief à coller dans Build / IA

```text
Projet CardRW — reprise
Repo : (chemin local) — origin jgzgtxbbdc-ops/CardRW
git log -1  # attendu ≥ 51a6d52
CDC : docs/cahier-des-charges-desfire-ev3.md
NOTES : docs/NOTES_LABO.md + docs/REPRISE.md

État : v0 + v0.5 validés terrain ; git privé OK ;
  FACTORY_KEY + prepareCommand (headers clairs) dans 51a6d52
Hors scope immédiat : refaire v0, relire tout le CDC

Prochaine tâche (choisir une) :
  A) Passe UX courte sur flux Carte/Auth/Explore (douleurs utilisateur)
  B) Arbitrage CDC : SM EV2 avant Write ou Write EV1 d’abord (selon parc)
  C) v1 WriteData Standard + tests + terrain
Ne pas committer clés prod / dumps réels / local.properties
```

---

## Ordre de bataille recommandé (prochaine fois)

1. **`git pull`** + tests verts + 5 min terrain (ne rien casser).  
2. **Une** des pistes (ne pas tout mélanger dans la même session) :
   - **A — UX** : hiérarchie CTA, auth lisible, explorateur dense, moins de friction labo.  
   - **B — EV2** : tester si des cartes refusent AES EV1 (`0xAA`) → décider v0.6 auth EV2 avant writes.  
   - **C — v1 Write** : s’appuyer sur le nouveau `prepareCommand` ; carte sacrifiable ; journal APDU.  
3. Fin de session : `git status` → commit clair → **`git push`**.

Avis fil rouge : **A ou B avant un gros C**, sauf urgence d’écrire des badges.

---

## Rappels multi-machine

| Règle | Détail |
|---|---|
| Début | `git pull` |
| Fin | `git push` |
| Secrets | jamais dans le repo |
| Autre ordi | nouvelle clé SSH sur le compte `jgzgtxbbdc-ops`, ou réutiliser une clé déjà ajoutée |
| Capture bug | screenshots + journal APDU texte (pas vidéo en premier) |

---

## Dettes notées (Claude / revue) — pas urgent ce soir

- Auth EV2 avant create/write si parc EV2-only / EV3 “propre”  
- KDoc CMAC (mute `iv`) si pas déjà clair  
- Wipe clés session mémoire avant open source  
- Golden auth + ReadData FULL versionné  
- Split `CardScreen` / `DesfireClient` quand wizards v1  
- Veille concurrentielle avant gros investissement v1.1 templates  
- README public explicite sur export clés en clair (jour open source)

---

*Mettre à jour ce fichier à chaque fin de session utile (commit + 3 lignes « fait / suivant »).*
