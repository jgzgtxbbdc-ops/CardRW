# Notes labo CardRW

## 2026-07-21 — Validation v0 terrain ✅

**Résultat :** UID + GetVersion + apps OK. Tests multi-cartes / multi-fabricants (encodées et vierges). Aucun bug noté.

**Critère CDC v0 :** atteint (identité + liste apps stable + journal APDU).

### Capture APDU (carte vierge, session type)

Voir `desfire-core/src/test/resources/golden/lab-blank-getversion-apps.json`.

Interprétation rapide :

| Étape | Résultat |
|---|---|
| GetVersion (3 frames, AF) | OK — vendor NXP (0x04), SW 2.1, UID `04350B92EB5A80` |
| SelectApplication PICC `000000` | 91 00 |
| GetFreeMemory | `00 20 00` → **8192 o** libres (little-endian) |
| GetApplicationIDs | 91 00 **sans data** → aucune application (carte vide) |

## 2026-07-21 — v0.5 implémentée (code)

**Périmètre livré :**

- `SelectApplication` sur AID choisi (tag live maintenu)
- `AuthenticateAES` + dérivation session key + **SM EV1** (CMAC / CRC32 / AES-CBC)
- Explorateur lecture : GetKeySettings, GetFileIDs, GetFileSettings, ReadData Standard
- Badge session UI : « Auth AES · SM EV1 · app · clé n° »
- Saisie clé hex (défaut usine `00…00`) + n° de clé 0–7

**Tests unitaires :** auth AES carte simulée, CMAC NIST, parse FileSettings, ReadData plain.

### Checklist manuelle terrain v0.5

- [ ] Carte **vierge** : Select PICC, auth master clé usine `00…00`, GetKeySettings
- [ ] Carte **encodée** (apps présentes) : Select AID, auth clé 0 usine ou connue
- [ ] Explore : liste fichiers + droits + comm mode affichés
- [ ] Fichier Standard **Free / plain** : ReadData hex visible sans auth
- [ ] Fichier Standard **clé requise** : ReadData OK après AuthenticateAES
- [ ] Fichier **MACed / Full** : lecture OK avec SM (sinon message pédagogique)
- [ ] Retrait carte mid-op : message « carte perdue », session invalidée
- [ ] Journal APDU : trames AA / AF / BD annotées

### Critère CDC v0.5

> Lecture authentifiée de fichiers Standard sur carte de labo avec clés connues.

### Journal terrain (extrait) — auth OK

- GetVersion + 7 apps `B0…B6 13 F5` OK  
- AuthenticateAES PICC clé 0 + GetKeySettings (`0F 81` + CMAC 8 o) OK → **SM EV1 confirmé**  
- GetFileIDs PICC → `91 9D` (attendu) — **corrigé** : explore PICC ne appelle plus 6F  
- AuthenticateAES app `B013F5` OK  
- Double Select avant auth — **corrigé** (`ensureApplicationSelected`)

### Correctifs UI / client (suite immédiate)

- [x] PICC : pas de GetFileIDs ; note pédagogique  
- [x] Pas de double Select  
- [x] Badge session 2 lignes ; moins de doublons statusLine  
- [x] Clé hex compacte + usine + coller ; clés 0–13  
- [x] CTA Auth / Explore hiérarchisés  
- [x] GetCardUID si Random ID + auth (**validé terrain** : UID réel OK)  
- [x] Mémoire : pas de « libre > total » absurde  

### Journal labo — app `B013F5` fichier 0

| Étape | Résultat |
|---|---|
| Auth AES clé 0 (usine) | OK |
| GetKeySettings | `0x08`, maxKeys≈4 |
| GetFileIDs | `00 01 02` |
| GetFileSettings(0) | Standard · 16 o · **FULL** · R=**clé 2** W=clé 0 RW=clé 1 |
| ReadData clé 0 | `91 AE` — lecture réservée clé 2 ; AE tue la session SM |
| Auth clé 1 puis meta | AE en cascade (session morte) |

**Correctifs :**
- Ne pas ReadData si la clé de session n’a pas le droit Read  
- `key settings 0x08` → free-list OFF → GetKeySettings/GetFileIDs/GetFileSettings = **clé maître 0** uniquement (AE en clé 1/2)  
- Explore **2 phases** : clé 0 = structure (cache) → clé R/RW = lecture seule (pas de 45/6F/F5)

### 2026-07-21 — Critère CDC v0.5 **atteint** ✅

| Étape | Résultat |
|---|---|
| Auth clé **0** → structure | GetKeySettings `0x08`, files 0/1/2 |
| Auth clé **1** (RW) → lecture cache | ReadData FULL **0, 1, 2** OK (32 o cipher → 16 o plain) |
| Auth clé **2** (R) | ReadData **0, 1** OK ; F2 refusé (R=clé 3) — message OK |
| SM EV1 | CMAC meta + FULL ReadData bout-en-bout |

Exemples plain : F0 `00 00 00 04 B2…` · F1 `00 00 00 12 02…`

**Critère CDC :** *lecture authentifiée de fichiers Standard avec clés connues* — **OK**.

### Suite

- [x] v0 terrain
- [x] v0.5 code (auth + SM EV1 + explorateur)
- [x] GetCardUID terrain
- [x] v0.5 **ReadData Standard FULL** (clés 1 / 2, mode cache)
- [x] Git + remote privé `jgzgtxbbdc-ops/CardRW`
- [x] `FACTORY_KEY` copie défensive + `prepareCommand(clearHeaderLength)` (`51a6d52`)
- [ ] Enrichir golden avec capture auth + ReadData FULL
- [x] **v0.6 U0** — spec moniteur Carte `docs/UX_ECRAN_CARTE.md` (2026-07-22)
- [x] **v0.6 U1** — pull auto post-select / post-auth ; Actualiser (2026-07-22)
- [x] **v0.6 U2** — auth bottom sheet + session sticky (2026-07-22)
- [ ] **v0.6 U3–U5** — clés candidates, arbre PICC, polish (voir spec)
- [x] **Coffre K0** — spec `docs/UX_COFFRE_CLES.md` (2026-07-22)
- [x] **Coffre K1–K2** — EncryptedPrefs + écran + sheet dropdown/save (2026-07-22)
- [ ] Arbitrage EV2 avant writes
- [ ] v1 : write / create / ChangeKey / dumps (WriteData header=7, ChangeKey header=1)

Voir aussi `docs/REPRISE.md`, `docs/UX_ECRAN_CARTE.md`, `docs/UX_COFFRE_CLES.md`.
