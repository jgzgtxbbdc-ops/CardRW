# UX écran Carte — moniteur diagnostic

**Statut :** spec v0.6 (U0) — 2026-07-22  
**Portée :** écran **Carte** (lecture / visualisation ; écriture plus tard sur le même contrat)  
**CDC :** renvoie §7.1–7.2 ; ce fichier est la **source de vérité** du comportement moniteur  
**Implémentation :** jalon **v0.6 UX**, tranches U1→U5 (ci-dessous)

---

## 1. Écart v0.5 → cible

| Livré v0.5 (terrain OK) | Cible moniteur |
|---|---|
| `readIdentity` auto à la pose | Inchangé + base du moniteur |
| Select app → panneau auth inline + bouton **Explorer** | Select / auth → **pull auto** ; Explorer = Actualiser |
| Auth = long formulaire dans le scroll | Auth = **surface secondaire** (sheet) |
| PICC = ligne comme une app (`00 00 00`) | PICC = **super-nœud racine** |
| Fichiers dans une section après Explore | Fichiers **dans** l’application (arbre) |
| « Authentifier » générique (clé 0–13) | Clé(s) **candidates** pour l’opération |
| Data lues souvent repliées sans preview | Info dispo **visible** (preview + détail) |

Les capacités protocole v0.5 (auth AES, SM EV1, explore, ReadData) restent ; c’est le **comportement UI / enchaînement** qui change.

---

## 2. Quatre principes

### P1 — Diagnostic : afficher dès que c’est lisible

> Si la carte (et la session) peuvent répondre, l’UI a déjà la réponse à l’écran.

- Pas de clic « Lire / Explorer » pour **révéler** une info déjà obtenable.
- Select app / PICC → explore (structure + ReadData si droits OK).
- Auth réussie → re-explore (+ GetCardUID si Random ID).
- Résultats **partiels** toujours visibles (lu / refusé / besoin clé N).
- Free → pas d’auth. Never → message, pas de formulaire vain.

### P2 — Scroll = parcourir des informations

> Si l’utilisateur doit saisir ou confirmer, une fenêtre s’ouvre ; le moniteur ne bouge pas.

- Corps principal = **moniteur** (profil, session, arbre, erreurs).
- Saisie clé, confirm destructif, options rares → **dialog / bottom sheet**.
- Scroll moniteur = données plus longues, pas « trouver le formulaire Auth ».
- Scroll **dans** la sheet (clavier, chips) : OK.

### P3 — UI = arborescence DESFire

> Suggérer le modèle PICC → apps → fichiers, pas une liste plate.

```
Couche radio ISO 14443-4     (profil : UID / Random, ATS…)
└── Carte (PICC)             super-nœud — pas une app parmi d’autres
    ├── Identité / config    (bandeau profil, souvent hors scroll long)
    ├── Key settings PICC
    └── Application · AID
          ├── Key settings app
          └── Fichier · settings
                └── Contenu (si lu / sinon pointillé + CTA)
```

- **Trait plein** : lisible sans auth (ou déjà lu).  
- **Trait pointillé** : dépend free-list / droits / clé manquante.  
- Une seule app **sélectionnée** sur la carte à la fois ; les autres = nœuds repliés (+ cache si déjà visités).  
- Key settings ≠ fichier : nœud méta distinct.

### P4 — Demander la ou les clés **capables** de l’opération

> On n’authentifie pas « la carte » : on authentifie pour une intention, avec le sous-ensemble de clés désigné par le modèle.

| Intention | Candidates (si connues) |
|---|---|
| Lire contenu fichier | n° **R** et/ou **RW** (sauf Free / Never) |
| Écrire contenu | n° **W** et/ou **RW** |
| Change access rights | n° **Change** |
| Structure (IDs / settings) free-list OFF | souvent **maître app 0** (hypothèse explicite si settings absents) |
| Ops PICC | **maître PICC 0** (libellé domaine PICC ≠ app) |

- Session déjà suffisante → **pas** de sheet ; enchaîner l’op.  
- Une candidate → n° fixé + libellé droit.  
- Plusieurs → choix restreint (pas 0–13 par défaut).  
- Info inconnue → orienter (ex. maître 0) + repli expert « autre clé ».  
- Libellés : « Authentifier pour lire le fichier 0 », pas seulement « Authentifier ».

---

## 3. Partition moniteur / modal

### Moniteur (scroll = info)

- Profil identité (type, UID, mémoire, production)  
- Badge / barre **session** (app · n° clé · SM) — sticky si possible  
- Arbre PICC → apps → fichiers (+ previews, notes, erreurs d’accès)  
- CTA courts contextuels : « Authentifier (clé 1)… », « Actualiser »  
- Détails techniques GetVersion : collapsable **info** (pas saisie)

### Surface secondaire (sheet / dialog)

- Auth pour une intention (`AuthRequest`)  
- (plus tard) Write, ChangeKey, Format, options labo  
- Glossaire (i) — déjà prévu CDC §7.6  
- Hex long optionnel (« voir tout / copier »)

### Navigation qui reste sur le moniteur

- Pose de carte, sélection / expand d’app, expand fichier (si pas de saisie)

---

## 4. Contrat `AuthRequest` (cible code)

Intention + candidates ; brouillon de saisie **local à la sheet** ; commit sur OK.

```text
AuthRequest(
  intent: ReadFile(fileNo) | WriteFile(fileNo) | ExploreStructure(aid)
        | PiccMasterOp | ChangeAccessRights(fileNo) | …
  domain: PICC | Application(aidHex)
  candidates: List<KeyCandidate>   // keyNo + rôle ("Read", "Read&Write", "Master", …)
  allowAnyKey: Boolean             // expert / info inconnue
  preferKeyNo: Int?                // préselection
)

KeyCandidate(keyNo: Int, roleLabel: String)
```

**Heuristique de préselection :**

1. Session courante si elle est candidate → ne pas ouvrir la sheet.  
2. Sinon dernière clé OK pour ce domaine.  
3. Sinon droit le plus spécifique (ex. R avant RW pour une lecture — documenté en U3).  
4. Sinon plus petit `keyNo` candidat.

**Après succès :** fermer la sheet → re-pull selon intention (P1) → restaurer position scroll / focus nœud si possible.

**Tag lost pendant sheet :** message clair ; ne pas jeter la saisie sans dire pourquoi ; OK désactivé si plus de client.

---

## 5. Flux cible (lecture)

```
Carte posée
  → profil + liste AIDs sous PICC (readIdentity)
  → [GetCardUID auto plus tard si Random + auth]

Expand / select Application (ou focus PICC)
  → SelectApplication + explore auto
  → nœuds structure / fichiers remplis (partiel OK)

Tap « Authentifier pour … » sur nœud lacunaire
  → sheet AuthRequest(candidates)
  → OK → AuthenticateAES → resume intention + refresh moniteur

Actualiser (optionnel)
  → re-explore contexte courant
```

**Ne pas** auto-auth avec clé usine sans geste utilisateur (tentatives / cartes inconnues).  
Option labo opt-in éventuelle : hors scope U1–U4.

---

## 6. Tranches d’implémentation (v0.6)

| ID | Contenu | Critère « done » | Risque |
|----|---------|------------------|--------|
| **U0** | Cette spec + REPRISE + renvoi CDC | Docs mergees | Nul |
| **U1** | Pull auto post-select et post-auth ; bouton → « Actualiser » | ✅ 2026-07-22 — select/auth → `runExplore` ; CTA Actualiser | Faible |
| **U2** | Auth en bottom sheet ; panneau retiré du scroll ; barre session | ✅ 2026-07-22 — ModalBottomSheet + AuthSessionBar sticky | Moyen |
| **U3** | `keysFor(intent)` ; candidates ; Free/Never ; CTA nœud | ✅ 2026-07-22 — AuthKeyPlanner + sheet slots + CTA fichier | Moyen |
| **U4** | Arbre PICC super-nœud ; fichiers sous apps ; style plein/pointillé | ✅ 2026-07-22 — `DesfireCardTree` + cache multi-AID | Élevé |
| **U5** | GetCardUID auto ; preview hex ; restore scroll ; légende accès | ✅ 2026-07-22 — auto UID Random, preview 8 o, scroll, badges | Faible |

**Ordre imposé :** U1 → U2 → U3 → U4 → U5.  
**Ne pas** démarrer U4 sans U1 (arbre vide ou encore plein de CTA manuels).

**Write v1 :** réutiliser `AuthRequest` + moniteur ; ne pas figer l’auth dans l’ancien panneau inline.  
**U1 minimum** avant gros Write ; idéal U2–U3.

**EV2 (option B REPRISE) :** orthogonal ; peut se glisser avant Write selon parc ; ne bloque pas U0–U1.

---

## 7. Hors scope v0.6

- Refonte wizard création app / templates  
- Anneau de clés complet (CDC §7.3) — mais U3 prépare les statuts « clé testée »  
- **Coffre-fort de clés** — spec et jalon séparés : [`UX_COFFRE_CLES.md`](UX_COFFRE_CLES.md) (idéal après U2)  
- Mode débutant / expert global (peut s’appuyer sur densité d’arbre plus tard)  
- SM EV2, dumps, formatage  

---

## 8. Fichiers code probablement touchés (tranches suivantes)

| Zone | Fichiers |
|---|---|
| État / ops | `app/.../viewmodel/CardViewModel.kt` (`exploreByAid` cache multi-AID) |
| UI Carte | `CardScreen.kt` + **`DesfireCardTree.kt`** (U4) |
| Droits → candidates | `desfire-core/.../model/FileModels.kt` + `AuthKeyPlanner` |
| Strings | `app/src/main/res/values/strings.xml` |

Aucun changement protocole requis pour U1–U3 si `exploreSelectedApplication` / auth restent stables.

---

## 9. Checklist terrain (à recopier dans NOTES_LABO en fin v0.6)

- [ ] Pose carte → profil sans action  
- [ ] Select app → structure/fichiers ou erreur d’accès **sans** bouton Explorer obligatoire  
- [ ] Auth → moniteur se met à jour ; sheet fermée ; position raisonnable  
- [ ] Fichier R=1 → sheet propose clé 1 (et RW si pertinent), pas 0–13 par défaut  
- [ ] R=Free → lecture sans sheet  
- [ ] R=Never → pas de demande de clé  
- [ ] PICC distinct des apps ; pas de fausse liste de fichiers PICC  
- [ ] Tag lost pendant sheet : message compréhensible  

---

## 10. Règles courtes (rappel)

1. Si la carte peut répondre, l’UI a déjà la réponse à l’écran.  
2. Si l’utilisateur doit saisir ou confirmer, une fenêtre s’ouvre — le moniteur ne bouge pas.  
3. L’UI est l’arbre DESFire : PICC au-dessus, fichiers dans les apps.  
4. Si une clé est requise, demander **la ou les** clés capables de l’opération.
