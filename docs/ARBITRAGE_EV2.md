# Arbitrage SM EV1 vs EV2 — CardRW

**Statut :** livré côté code (auth EV2 + fallback) — **validation terrain parc** à faire  
**Date :** 2026-07-22  
**CDC :** § auth AES EV1 v0.5 / EV2 v1 ; écritures après décision parc

---

## 1. Contexte

| Mode | Opcode auth | SM | État CardRW |
|---|---|---|---|
| **EV1** (legacy AES) | `0xAA` AuthenticateAES | CMAC/CRC session key unique | ✅ validé terrain labo |
| **EV2** | `0x71` AuthenticateEV2First | SesAuthENC + SesAuthMAC, TI, CmdCtr | ✅ code + tests unitaires |

Certaines cartes **EV3 « propres »** / politiques EV2-only **refusent** `0xAA` (`0x1C` illegal command, parfois permission).  
Le parc labo actuel (apps `B0…B6 13 F5`) répond à **EV1** — logs APDU session U4/U5.

### PICC carte **vierge usine** (important)

Les DESFire **neuves** ont la **PICC Master Key en DES / 2KTDEA** (souvent 16×`0x00`), **pas en AES**.  
`AuthenticateAES (0xAA)` → `0xAE` est **normal** sur blank.  
CardRW enchaîne alors `AuthenticateDES (0x0A)` avec clé usine 00…00 → badge `Auth DES · legacy`.

---

## 2. Comportement app (depuis ce jalon)

`DesfireClient.authenticateAesPreferEv1` :

1. Tente **EV1** `0xAA`  
2. Si refus **méthode** (illegal / permission / not supported) → **EV2 First** `0x71`  
3. Si simple `0xAE` (mauvaise clé) → **pas** de bascule (évite double échec confus)

Le ViewModel moniteur appelle ce helper pour toutes les auth manuelles / auto / usine.

Badge session :

- `Auth AES · SM EV1`
- `Auth AES · SM EV2`

---

## 3. Protocole de test terrain (parc)

Pour **chaque type** de carte du parc (vierge, encodée labo, éventuelle EV3 neuve) :

### 3.1 EV1 encore OK ?

1. Pose carte → moniteur  
2. Select PICC ou app → auth clé connue (usine ou coffre)  
3. Journal APDU :  
   - `AuthenticateAES` → `91 00` + suite explore OK → **EV1 vivant**  
   - `AuthenticateAES` → `91 1C` / permission puis `AuthenticateEV2First` → **EV2-only**

### 3.2 EV2-only (si 3.1 bascule)

1. Badge **SM EV2**  
2. GetKeySettings / ReadData encore OK  
3. Noter UID / type GetVersion (SW major)

### 3.3 Matrice décision Write

| Résultat parc | Stratégie Write v1 |
|---|---|
| 100 % EV1 OK | Write en **SM EV1** (`prepareCommand` header=7) — chemin actuel |
| Mix EV1 + EV2 | Write via session courante (EV1 **ou** EV2) ; tests des deux |
| EV2-only dominant | Write prioritaire chemin **EV2** ; EV1 en secours lecture |

---

## 4. Code livré

| Fichier | Rôle |
|---|---|
| `session/DesfireSecureSession.kt` | Interface SM |
| `session/Ev1Session.kt` | EV1 (existant, impl interface) |
| `session/Ev2Session.kt` | EV2 dérivation SV1/SV2, MAC truncate, CmdCtr/TI |
| `client/DesfireClient.kt` | `authenticateEv2First`, `authenticateAesPreferEv1/Ev2` |
| `Ev2SessionTest.kt` | Auth simulée + fallback illegal AA |

---

## 5. Limites connues (dettes)

- **AuthenticateEV2NonFirst** (`0x77`) non implémenté (re-auth même app sans re-select)  
- SM EV2 **FULL** / padding : aligné AN12343 ; valider ReadData FULL EV2 sur carte réelle  
- Pas encore de golden APDU terrain EV2 versionné  
- Write / ChangeKey EV2 : réutiliser `prepareCommand(clearHeaderLength)` côté EV2 (à figer après 3.3)

---

## 6. Checklist labo (à cocher dans NOTES_LABO)

- [ ] Carte labo encodée : toujours EV1 (régression)  
- [ ] Carte vierge usine : EV1 usine OK  
- [ ] Si dispo EV3 neuve / EV2-only : bascule auto + badge EV2  
- [ ] Journal : annotation `AuthenticateEV2First` lisible  
- [ ] Décision Write : cocher une ligne du §3.3  

---

*Mettre à jour ce fichier après la première campagne parc EV2.*
