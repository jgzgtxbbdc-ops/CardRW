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
| **FACTORY_KEY** copie défensive | ✅ `51a6d52` — voir contrat ci-dessous |
| **prepareCommand** en-têtes clairs (prêt Write/ChangeKey) | ✅ `51a6d52` — voir contrat ci-dessous |
| Tests `desfire-core` (FactoryKey + PrepareCommand) | ✅ BUILD SUCCESSFUL |
| Passe UX (UI peu pratique) | ⬜ pas commencée |
| Décision EV2 avant écritures massives | ⬜ à trancher (parc cartes) |
| **v1** Write / Create / ChangeKey / dumps | ⬜ suivant majeur |

### Contrat crypto déjà livré (ne pas refaire)

**1) `FACTORY_KEY`**

```kotlin
val FACTORY_KEY: ByteArray
    get() = ByteArray(KEY_SIZE_BYTES) // 16×0x00, instance neuve à chaque accès
```

Plus de singleton mutable partagé.

**2) `prepareCommand` — en-têtes clairs pour v1**

```kotlin
fun prepareCommand(
    opcode: Int,
    data: ByteArray,
    mode: CommMode,
    clearHeaderLength: Int = 0,  // défaut 0 = rétro-compatible
): ByteArray
```

| Mode | Comportement |
|---|---|
| PLAIN | Inchangé — ReadData TX (params clairs + CMAC IV) |
| MACED | Inchangé — data + CMAC 8 o |
| FULL | CRC sur `cmd‖data` entier ; chiffre seulement `data[clearHeaderLength..]` ; en-tête clair en tête du data field |

Conventions v1 (à utiliser dès Write/ChangeKey) :

- **WriteData `0x3D`** → `clearHeaderLength = 7` (FileNo ‖ Offset ‖ Length)
- **ChangeKey `0xC4`** → `clearHeaderLength = 1` (KeyNo)

**ReadData FULL v0.5 inchangé :** TX reste `CommMode.PLAIN` ; FULL uniquement en RX via `postprocessResponse`.

Tests : `FactoryKeyTest`, `Ev1SessionPrepareCommandTest`.

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
  FACTORY_KEY (copie défensive) + prepareCommand(clearHeaderLength) dans 51a6d52
  Tests FactoryKeyTest + Ev1SessionPrepareCommandTest verts
  ReadData FULL TX=PLAIN inchangé ; Write 0x3D header=7 ; ChangeKey 0xC4 header=1
Hors scope immédiat : refaire v0, refaire FACTORY_KEY/prepareCommand, relire tout le CDC

Prochaine tâche (choisir une) :
  A) Passe UX courte sur flux Carte/Auth/Explore (douleurs utilisateur)
  B) Arbitrage CDC : SM EV2 avant Write ou Write EV1 d’abord (selon parc)
  C) v1 WriteData Standard (clearHeaderLength=7) + tests + terrain
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
