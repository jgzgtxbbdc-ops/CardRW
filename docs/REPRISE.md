# Reprise session — CardRW

**Dernière mise à jour :** 2026-07-23 (auto-auth moniteur)  
**Machine d’arrêt :** session courante  
**Remote :** `git@github.com:jgzgtxbbdc-ops/CardRW.git` (privé)  
**Commit :** (voir `git log -1`) — auth auto select + re-auth structure  
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
| **v0.6 UX** moniteur diagnostic écran Carte | ✅ **U0–U5** moniteur v0.6 clos |
| **NFC reader mode app-wide** | ✅ MainActivity + NfcTagBus (pas de chooser sur Accueil) |
| **Coffre-fort de clés** | 🔄 **K0–K2 ✅** — K3 biométrie optionnelle plus tard |
| **CI GitHub Actions** | ✅ desfire-core:test + app assembleDebug/testDebug |
| Décision EV2 avant écritures massives | 🔄 **code + fallback livrés** — campagne parc `docs/ARBITRAGE_EV2.md` |
| **v1** Write / Create / ChangeKey / dumps | ✅ Write/Create/Delete ; ChangeKey AES+DES→AES ; FormatPICC ; **dumps moniteur** |

### v0.6 UX — moniteur Carte

**Spec :** [`docs/UX_ECRAN_CARTE.md`](UX_ECRAN_CARTE.md) (source de vérité)

| Tranche | Contenu | État |
|---|---|---|
| **U0** | Spec 4 principes + découpage | ✅ |
| **U1** | Pull auto post-select / post-auth ; Explorer → Actualiser | ✅ |
| **U2** | Auth en bottom sheet ; session sticky | ✅ |
| **U3** | Clés candidates selon intention / droits | ✅ |
| **U4** | Arbre PICC super-nœud ; fichiers sous apps | ✅ **livré** |
| **U4b** | Re-auth auto : dernière clé OK mémorisée par AID | ✅ **livré** (session carte) |
| **U5** | GetCardUID auto, preview hex, polish | ✅ **livré** |

**U5 livré :**
- GetCardUID **auto** après explore si Random ID + session auth (bouton secours si échec)
- Preview hex **8 octets** sur nœud fichier sans expand ; expand = hex complet + taille
- Badges accès : Lu / Auth / Free / Never / Erreur
- Restore scroll moniteur après busy (auth / explore)
- Coffre : dialog « enregistrer quand même » si auth KO + option cochée

**U4 livré :**
- `DesfireCardTree.kt` : PICC super-nœud → apps enfants → fichiers imbriqués (plus de PICC « comme une app »)
- Trait **plein** / **pointillé** selon complétude (lu / Free / Never vs lacune auth)
- Key settings = nœud méta (PICC ou app), pas un faux fichier
- Une seule app détaillée à la fois ; apps déjà visitées = badge **Cache** + compteur
- `CardUiState.exploreByAid` : cache multi-AID (reselect sans flash vide)
- Libellés session / sheet : PICC distinct de « App 00 00 00 »

**U4b — clés mémorisées + clé standard auto (session VM) :**
- Auth réussie → `rememberedKeysByAid[AID][keyNo]` (plusieurs slots : 2, 3, 0…)
- Re-select app → re-auth dernière clé + **fill auto** multi-clés mémorisées
- **Clé standard usine 00…00** tentée auto sur slots utiles (maître 0 structure, R/RW fichiers non lus)
- **Merge** : données déjà lues conservées si session change de clé
- CTA fichier → mémorisée **ou** usine sur candidat, sinon sheet
- Échecs usine mémorisés par slot (pas de boucle 0xAE) ; wipe à nouvelle carte

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

**Promesse produit actuelle :** lecteur DESFire pédagogique + auth AES/DES + moniteur arbre + Write/Create/Delete + bascule DES→AES + FormatPICC.  
**Pas encore :** dumps, templates, série, open source.

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
4. Sanity check rapide : lecture profil → arbre PICC → select app → auth → fichiers imbriqués → ReadData.

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
  v0.6 UX moniteur : U0–U5 ✅
  Coffre : K0–K2 ✅ (+ save malgré auth KO) ; NFC app-wide ✅ ; CI ✅
  U4–U5 : arbre + multi-clés + clé standard auto + preview / GetCardUID auto
  ReadData FULL TX=PLAIN inchangé ; Write 0x3D header=7 ; ChangeKey 0xC4 header=1
Hors scope immédiat : refaire v0/crypto livré, biométrie K3 non prioritaire

État récent : cycle vierge + FormatPICC + auto-auth moniteur (select / structure).

### Philosophie moniteur (product)

**Ne jamais refaire manuellement une opération déjà validée dans la session carte** :
- clé OK → mémoire VM par AID/slot ; rejeu auto au select / structure
- Create/Format/Delete app → `ensurePiccMasterAesSession` + restore post-`readIdentity`
- AID / FileNo → suggestions libres (pas de doublon)

Prochaine tâche (une seule par session) :
  A) Terrain dumps (export + liste + share)
  B) K3 biométrie coffre — non prioritaire
  C) Restauration dump (v1.2) — plus tard
Ne pas committer clés prod / dumps réels / local.properties
```

---

## Ordre de bataille recommandé (prochaine fois)

1. **`git pull`** + tests verts + 5 min terrain (ne rien casser).  
2. **Une** des pistes (ne pas tout mélanger) :
   - **A — Terrain dumps** + polish export.  
   - **B — K3** biométrie coffre (optionnel).  
3. Fin de session : MAJ ce fichier → commit clair → **`git push`**.

Avis fil rouge : dump moniteur **sans secrets** livré ; restore / include keys plus tard.

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

## Dettes notées — moniteur / auth

- Auth auto par intention **livré** (Read/Write CTA, flash rôle, `pendingWriteFileNo`) —
  terrain multi-clés (ex. R=2 W=0) à valider.
- Select app force encore usine 0 si aucune mémorisée — OK labo ; peaufiner « clé lecture
  si structure en cache » plus tard si besoin.

## Dettes notées (crypto / dettes code)

- Auth EV2 **livré** (`authenticateEv2First` + fallback) — valider ReadData FULL EV2 terrain  
- AuthenticateEV2NonFirst (`0x77`) non livré
- KDoc CMAC (mute `iv`) si pas déjà clair  
- Wipe clés session mémoire avant open source  
- Golden auth + ReadData FULL versionné  
- Split restant `CardScreen` / `DesfireClient` quand wizards v1  
- Veille concurrentielle avant gros investissement v1.1 templates  
- README public explicite sur export clés en clair (jour open source)

---

*Mettre à jour ce fichier à chaque fin de session utile (commit + 3 lignes « fait / suivant »).*
