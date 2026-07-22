# Reprise session — CardRW

**Dernière mise à jour :** 2026-07-22  
**Machine d’arrêt :** (session design UX + coffre)  
**Remote :** `git@github.com:jgzgtxbbdc-ops/CardRW.git` (privé)  
**Commit :** voir `git log -1`  
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
| **v0.6 UX** moniteur diagnostic écran Carte | 🔄 **U0–U2 ✅** — U3→U5 à faire |
| **NFC reader mode app-wide** | ✅ MainActivity + NfcTagBus (pas de chooser sur Accueil) |
| **Coffre-fort de clés** | 🔄 **K0–K2 ✅** — K3 biométrie optionnelle plus tard |
| Décision EV2 avant écritures massives | ⬜ à trancher (parc cartes) |
| **v1** Write / Create / ChangeKey / dumps | ⬜ après U1+ (idéal U2–U3) |

### v0.6 UX — moniteur Carte

**Spec :** [`docs/UX_ECRAN_CARTE.md`](UX_ECRAN_CARTE.md) (source de vérité)

| Tranche | Contenu | État |
|---|---|---|
| **U0** | Spec 4 principes + découpage | ✅ |
| **U1** | Pull auto post-select / post-auth ; Explorer → Actualiser | ✅ |
| **U2** | Auth en bottom sheet ; session sticky | ✅ |
| **U3** | Clés candidates selon intention / droits | ⬜ **prochaine** (ou K1–K2 coffre) |
| **U4** | Arbre PICC super-nœud ; fichiers sous apps | ⬜ |
| **U5** | GetCardUID auto, preview hex, polish | ⬜ |

**Principes (rappel) :** (1) afficher dès que lisible (2) scroll = info, saisie = fenêtre (3) arbre DESFire (4) demander la/les clés capables de l’op.

### Coffre-fort de clés

**Spec :** [`docs/UX_COFFRE_CLES.md`](UX_COFFRE_CLES.md) (source de vérité)

| Tranche | Contenu | État |
|---|---|---|
| **K0** | Spec mécanique + sécu progressive + modèle données | ✅ |
| **K1** | Meta + SecretStore persistant + CRUD + liste | ✅ |
| **K2** | Sheet auth : dropdown **ou** hex + ☐ enregistrer | ✅ |
| **K3** | Unlock biométrie / device credential optionnel | ⬜ |
| **K4** | Polish (suggestions nom, récents, export…) | ⬜ |

**Rappel :** slot carte 0–13 ≠ entrée coffre (nom → matériau). Anneau CDC §7.3 = statut sur la carte.

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
- **UX Carte (v0.6) :** `docs/UX_ECRAN_CARTE.md`  
- **Coffre-fort (K0) :** `docs/UX_COFFRE_CLES.md`  
- Labo : `docs/NOTES_LABO.md`  
- Commandes : `docs/ANNEXE_A_COMMANDES.md`  
- Cette note : `docs/REPRISE.md`

---

## Brief à coller dans Build / IA

```text
Projet CardRW — reprise
Repo : (chemin local) — origin jgzgtxbbdc-ops/CardRW
git log -1
CDC : docs/cahier-des-charges-desfire-ev3.md
UX Carte : docs/UX_ECRAN_CARTE.md   ← moniteur v0.6
Coffre : docs/UX_COFFRE_CLES.md     ← noms de clés / SecretStore
NOTES : docs/NOTES_LABO.md + docs/REPRISE.md

État : v0 + v0.5 validés terrain ; git privé OK ;
  FACTORY_KEY + prepareCommand(clearHeaderLength) livrés (51a6d52)
  v0.6 UX : U0–U2 ✅ ; prochaine U3 candidates ou U4 arbre
  Coffre : K0–K2 ✅ (EncryptedPrefs + screen + sheet dropdown/save)
  ReadData FULL TX=PLAIN inchangé ; Write 0x3D header=7 ; ChangeKey 0xC4 header=1
Hors scope immédiat : refaire v0/crypto livré, biométrie K3 non prioritaire

Prochaine tâche (une seule par session) :
  A) U3 clés candidates selon intention (UX_ECRAN_CARTE.md) — recommandé
  B) U4 arbre PICC → apps → fichiers
  C) Arbitrage SM EV2 avant Write (parc)
  D) v1 WriteData — urgence seulement
Ne pas committer clés prod / dumps réels / local.properties
```

---

## Ordre de bataille recommandé (prochaine fois)

1. **`git pull`** + tests verts + 5 min terrain (ne rien casser).  
2. **Une** des pistes (ne pas tout mélanger) :
   - **A — U3** : clés candidates (R/W/RW) dans la sheet.  
   - **B — U4** : arbre PICC super-nœud ; fichiers sous apps.  
   - **C — EV2** : tester refus AES EV1 (`0xAA`) avant writes massifs.  
   - **D — v1 Write** : header=7 ; carte sacrifiable.  
3. Fin de session : MAJ ce fichier → commit clair → **`git push`**.

Avis fil rouge : **U3 puis U4** pour finir le moniteur ; Write après.

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
