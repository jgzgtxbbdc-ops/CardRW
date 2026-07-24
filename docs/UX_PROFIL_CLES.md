# UX profil de clés (key set)

**Statut :** P0 spec + **P1 livré** (modèle + repo + écran CRUD) — 2026-07-24  
**Portée :** relier **slots carte** et **matériaux coffre** pour dump / encode / moniteur multi-clés sans saisie manuelle à chaque intention  
**Motivation :** une carte DESFire réelle n’a pas « une clé » — elle a un **jeu** (PICC master + maîtres app + R/W par fichier)  
**Liens :** coffre [`UX_COFFRE_CLES.md`](UX_COFFRE_CLES.md) · moniteur [`UX_ECRAN_CARTE.md`](UX_ECRAN_CARTE.md) · CDC §6.2.1 / §7.3 / §8 · restore `DumpRestorePlanner`

---

## 1. Problème

Aujourd’hui (v0.6 moniteur + K1–K4 + dump labo) :

| Déjà livré | Limite |
|---|---|
| `AuthKeyPlanner` → **quels slots** pour une intention | Ne dit pas **quel matériau** |
| Coffre nommé → **matériau** réutilisable | Ne dit pas **où** le coller (AID + slot) |
| `rememberedKeysByAid` en session VM | **Éphémère** : nouvelle pose / redémarrage app = oubli |
| Usine `00…00` auto labo | Ne couvre pas un parc **non usine** |
| Dump = data **déjà lues** | Dump « complet » multi-clés encore semi-manuel |
| Restore sans secrets (usine AES) | Encode réel (clés custom) hors scope |

**Besoin atelier :** choisir un **profil « Site A »**, poser la carte, lancer Dump / Encode — l’app enchaîne les auth correctes sans demander chaque clé une à une.

---

## 2. Vocabulaire (source de vérité)

Quatre notions **distinctes**. Ne jamais les fusionner en UI ni en modèle.

| Concept | Définition | Portée | Contient des secrets ? |
|---|---|---|---|
| **Slot carte** | Index DESFire 0–13 sur PICC ou application | Modèle carte / droits R·W·RW·Ch | Non (c’est un n°) |
| **Coffre-fort** | Portefeuille appareil : **nom → matériau** (16 o AES) | Transverse aux cartes | Oui (via `SecretStore`) |
| **Anneau de clés** (CDC §7.3) | Statut de chaque **slot** sur **cette** app posée (usine / testée / échec…) | Moniteur, session carte | Non (métadonnées d’état) |
| **Profil de clés** (ce doc) | **Binding** : contexte carte → entrée coffre (+ options labo) | Appareil, réutilisable | Non dans le profil lui-même — seulement des **références** `vaultId` |

```text
                    ┌─────────────────────┐
                    │  Profil « Site A »  │  ← plan d’attaque (bindings)
                    └──────────┬──────────┘
         PICC k0 ──────────────┤
         AID F40101 k0 ────────┤──► vaultId ──► Coffre (matériau)
         AID F40101 k1 ────────┤
         AID F40101 k2 ────────┘
                    │
                    ▼
              AuthKeyPlanner        ← intention → slots candidats
                    │
                    ▼
           session DESFire (SM)  →  moniteur / dump / encode
```

**Règle d’or :**

```text
auth réussie =
    slot carte          (souvent imposé par P4 / AuthKeyPlanner)
  + matériau            (Usine | coffre via profil ou sheet | hex saisi)
```

Le profil **ne remplace pas** le planner : il **résout le matériau** pour un slot donné.

---

## 3. Principes produit

1. **Un geste, un profil actif** — moniteur / dump / encode consultent le même résolveur.  
2. **Bindings, pas copies** — le profil stocke `vaultId` (ou « usine »), jamais les 16 octets en double.  
3. **Création depuis l’expérience** — le meilleur profil est capturé après une session qui a marché (« Enregistrer ce jeu »).  
4. **Honnêteté de couverture** — dump / encode affichent ce qui a été fait avec le profil, ce qui a échoué, ce qui manque.  
5. **Labo d’abord** — fallback usine optionnel par binding ou global ; probing agressif hors défaut (CDC §8.2).  
6. **P2 moniteur** — sélection / édition de profil en sheet ou écran dédié, pas un long formulaire dans le scroll Carte.  
7. **Coffre verrouillé (K3)** — si le coffre exige biométrie, **un** unlock de session suffit pour tout le run profil (pas un prompt par slot).

---

## 4. Modèle de données

### 4.1 Profil (métadonnées, pas de secret)

```text
KeyProfile
  id: String (UUID)
  displayName: String              // « Site A », « Labo EV3 vierge »
  notes: String?
  createdAt: Long
  updatedAt: Long
  lastUsedAt: Long?
  // Options run
  allowFactoryFallback: Boolean    // défaut true en labo, false « prod atelier »
  preferEv2: Boolean?              // null = auto (comportement client actuel)
  bindings: List<KeyBinding>
```

### 4.2 Binding

```text
KeyBinding
  scope: BindingScope
    PICC                          // AID logique 00 00 00
    Application(aidHex: String)   // 6 hex majuscules
  keyNo: Int                      // 0–13
  materialRef: MaterialRef
    VaultEntry(vaultId: String)
    FactoryZero                   // 00…00 (AES ou DES selon flux client)
    // plus tard: Derived(...)    // hors scope P0
  roleHint: String?               // « master », « read », « write » — UI only
  required: Boolean               // true = échec auth = stop run dump/encode
```

**Unicité :** au plus un binding actif pour `(scope, keyNo)` dans un profil.  
**Pas de binding ≠ pas de matériau connu** pour ce slot → tenter mémorisée session → usine si autorisée → sheet / skip selon intention.

### 4.3 Stockage

| Donnée | Où |
|---|---|
| `KeyProfile` + bindings | Fichier / Room **non secret** (comme meta coffre) |
| Matériaux | Uniquement `SecretStore` via coffre (`vault.<id>`) |
| Session runtime | `rememberedKeysByAid` (déjà) **hydraté** depuis le profil au début de pose |

**Suppression coffre :** si `vaultId` référencé disparaît → binding **cassé** (UI : ⚠ « entrée manquante ») ; ne pas silence-fail à l’auth.

### 4.4 Export / import profil

| Mode | Contenu | Usage |
|---|---|---|
| **Meta only** (défaut) | noms + scopes + keyNo + `vaultId` locaux | backup appareil / git-safe ❌ (ids locaux) |
| **Portable sans secrets** | noms + scopes + keyNo + **noms** d’entrées coffre | partager structure entre appareils ; résolution par nom à l’import |
| **Bundle secrets** | portable + matériaux | **secret** ; avertissement fort ; hors P0 si possible |

P0 : export meta + résolution par `displayName` coffre à l’import. Bundle secrets = P2+.

---

## 5. Résolveur (cœur runtime)

API conceptuelle (à placer côté app, pas dans `desfire-core` pur si dépend du coffre) :

```text
KeyMaterialResolver
  activeProfile: KeyProfile?

  fun materialFor(scope: BindingScope, keyNo: Int): ResolveResult

sealed class ResolveResult {
  data class Ready(val keyBytes: ByteArray, val source: Source)  // Profile | Remembered | Factory | Sheet
  data class NeedsUser(val candidates: List<KeyCandidate>, val reason: String)
  data class Impossible(val reason: String)  // Never, binding required manquant, etc.
}
```

### 5.1 Ordre de résolution (lecture / moniteur)

Pour un couple `(AID|PICC, keyNo)` demandé par le planner :

1. **Session mémorisée** déjà validée pour ce slot (octets en RAM) — rejeu immédiat.  
2. **Profil actif** : binding exact → `vault.material(id)` (après unlock K3 si besoin).  
3. **Profil actif** : si `allowFactoryFallback` et pas de binding → tenter usine (une fois ; mémoriser échec).  
4. **Sans profil** : comportement moniteur actuel (mémorisée → usine labo → sheet).  
5. **NeedsUser** → sheet auth (U2) avec dropdown coffre ; option « ajouter ce binding au profil actif ».

### 5.2 Multi-clés dans un run

Dump / explore complet / encode ne font **pas** une seule auth :

```text
pour chaque intention du plan :
  slots = AuthKeyPlanner.plan(intent).candidates
  pour chaque slot utile (ordre : spécifique → master) :
    resolve(material)
    si Ready → Authenticate* → op → merge résultats
    si NeedsUser et mode interactif → sheet puis reprendre
    si Impossible / required binding KO → noter lacune, continuer ou stop selon politique
```

**Politique dump (défaut) :** best-effort — continuer, lister les fichiers non lus.  
**Politique encode (défaut) :** stop sur première étape `required` en échec (dry-run avant).

### 5.3 Relation avec `rememberedKeysByAid`

| Moment | Action |
|---|---|
| Pose carte + profil actif | Option « précharger » : ne **pas** auth tout de suite ; résoudre à la demande |
| Auth OK (quelle que soit la source) | Écrire dans `rememberedKeysByAid` (comportement actuel) |
| Fin de carte / wipe | Clear mémoire session ; profil inchangé |
| « Enregistrer ce jeu dans un profil » | Dump des slots mémorisés → bindings (matériaux déjà en coffre ou proposés à l’enregistrement) |

---

## 6. UX

### 6.1 Profil actif (global session UI)

- Chip / ligne sous la barre session moniteur : `Profil : Site A ▾` ou `Aucun profil`.  
- Changer de profil = sheet courte (liste + « Gérer… »).  
- Pas de profil = moniteur labo actuel (usine + mémorisée).

### 6.2 Écran « Profils » (navigation)

À côté de Coffre-fort :

```
Profils de clés
  [ + Nouveau ]
  Site A          6 bindings · il y a 2 j
  Labo usine      0 bindings · fallback usine
  …
```

Détail profil :

```
Site A
  ☐ Fallback usine si slot inconnu
  PICC
    k0  master     →  « SiteA-PICC »     [tester]
  App F4 01 01
    k0  master     →  « SiteA-App-M »    [tester]
    k1  read       →  « SiteA-Read »     [tester]
    k2  write      →  « SiteA-Write »    [tester]
  [ + Binding ]  [ Enregistrer depuis session carte ]
```

Libellés anti-ambiguïté (comme le coffre) :

- « Slot carte n°1 » vs « Matériau : *SiteA-Read* » vs « Profil : *Site A* ».

### 6.3 Capture depuis session (« le geste magique »)

Après une session moniteur réussie (plusieurs slots auth OK) :

```
Enregistrer le jeu de clés de cette carte
  Nom du profil : [ Site A          ]
  Slots mémorisés :
    ☑ PICC k0     (usine / coffre / hex)
    ☑ F40101 k0
    ☑ F40101 k2
  Matériaux pas encore au coffre :
    → créer entrées nommées auto (suggestion K4) puis lier
  [ Annuler ]  [ Créer le profil ]
```

C’est le **chemin principal** de création (pas un formulaire vide de 14 slots).

### 6.4 Dump avec profil

```
Dump carte
  Profil : Site A ▾
  ○ Rapide (structure + déjà lu / free)
  ● Complet avec profil (auth multi-slots + ReadData)
  ☐ Inclure secrets dans le JSON   (off — CDC)
  [ Dry-run couverture ]  [ Lancer ]
```

**Dry-run couverture (sans NFC ou avec cache) :**  
liste les fichiers / apps et pour chacun : Free | binding OK | fallback usine | **manque matériau**.

**Après run :** note honnête CDC §8.1 + liste `unread_files` + slots testés (`auth_result` si on enrichit le JSON).

### 6.5 Encode / restore avec profil

Étend `DumpRestorePlanner` :

| Étape planner | Auth via résolveur |
|---|---|
| FormatPICC / CreateApplication | PICC master (binding ou usine) |
| CreateStdDataFile / ChangeKey… | app master (souvent k0) |
| WriteData | slots **W / RW** du fichier (planner Write), **pas** master par défaut |

Dry-run :

```
Plan restore — 12 étapes
  ✓ matériaux résolus pour toutes les étapes required
  ⚠ F40101 k2 absent du profil → Write F0 s’arrêtera
  [ Exécuter quand même ]  [ Compléter le profil ]
```

**ChangeKey depuis dump secrets :** hors P0 (REPRISE : ChangeKey dump secrets hors scope). P1+ si section `secrets` peuplée.

### 6.6 Templates v1.1 (lien futur)

Un template d’encodage **référence** un `profileId` (ou embarque des noms de matériaux).  
Série : même profil + variables `{{uid}}` / `{{counter}}` — le profil ne change pas à chaque carte.

---

## 7. Tranches d’implémentation

| ID | Contenu | Critère « done » | Dépendances | État |
|----|---------|------------------|-------------|------|
| **P0** | Cette spec + renvois REPRISE / CDC / coffre | Docs merge | — | ✅ |
| **P1** | Modèle `KeyProfile` + repo + écran liste/détail CRUD bindings | CRUD sans NFC | Coffre K1+ | ✅ |
| **P2** | `KeyMaterialResolver` branché moniteur (select / CTA / write) | Auth multi-clés sans retaper si profil complet | P1, U4b | ⬜ |
| **P3** | Capture « Enregistrer ce jeu » depuis `rememberedKeysByAid` | 1 session → 1 profil | P1–P2 | ⬜ |
| **P4** | Dump **Complet avec profil** + dry-run couverture | Fichiers non usine lus si bindings OK | P2, CardDumpBuilder | ⬜ |
| **P5** | Restore/encode : resolve W/RW + dry-run matériaux | Encode non-usine labo | P2, DumpRestorePlanner | ⬜ |
| **P6** | Chip profil actif moniteur + polish + export portable noms | UX atelier fluide | P1–P3 | ⬜ |
| **P7** | Templates liés au profil | Série v1.1 | P5, templates | ⬜ |

**Ordre recommandé :** P0 (ce doc) → **P1 → P3 → P2** (créer depuis session d’abord, utile tout de suite) → P4 → P5 → P6.

Alternative acceptable : P1 → P2 → P3 si on préfère d’abord brancher le résolveur avec bindings saisis à la main.

---

## 8. Hors scope (P0–P5)

- Diversification AN10922 / dérivation  
- Profil qui **contient** les hex (doublon du coffre)  
- Probing usine de tous les slots 0–13 en mode « prod »  
- Anneau §7.3 écran complet (le profil peut *alimenter* les statuts plus tard)  
- SM EV2NonFirst, Value/Records  
- Chiffrement du fichier dump exporté  
- Sync cloud des profils  

---

## 9. Sécurité & labo

| Règle | Détail |
|---|---|
| Secrets | Uniquement coffre / `SecretStore` ; profil = références |
| Export profil portable | Noms d’entrées, pas hex |
| Journal APDU | Jamais de matériau en clair |
| K3 | Un unlock session pour tout le run |
| Disclaimer | Profils = labo / atelier ; politiques entreprise hors promesse MVP |
| Backup Android | Exclure secrets (déjà CDC) ; meta profils OK si sans hex |

---

## 10. Critères d’acceptation atelier (cible P4–P5)

**Scénario A — Dump multi-clés**

1. Carte non usine : app avec F0 read=k1, F1 write=k2.  
2. Profil avec bindings k1 + k2 (+ master si besoin structure).  
3. Dump complet → F0 et structure lus **sans** sheet manuelle.  
4. Note dump liste correctement tout fichier encore illisible.

**Scénario B — Capture**

1. Auth manuelle une fois des slots utiles.  
2. « Enregistrer ce jeu » → profil créé + entrées coffre si besoin.  
3. Retrait / repose carte → dump complet sans resaisie.

**Scénario C — Encode**

1. Dump structure+data (sans secrets clés custom).  
2. Carte cible usine ou profil master connu.  
3. Restore dry-run vert → exec Write avec auth W/RW via profil.

---

## 11. Impact code (indicatif)

```text
app/
  data/model/KeyProfile*.kt
  data/repository/KeyProfileRepository.kt
  viewmodel/ProfileViewModel.kt          // écran gestion
  // CardViewModel : resolver + profil actif + capture session
  ui/screens/profiles/ProfilesScreen.kt

desfire-core/   (inchangé pour le secret)
  dump/ : couverture / steps peuvent accepter un
          KeyAvailability hint (interface pure, pas de vault)
```

`desfire-core` reste sans Android : le résolveur **matériau** vit dans `:app` ; le core reçoit déjà des `ByteArray` clés comme aujourd’hui.

---

## 12. Décisions (figées P1)

| # | Question | Décision |
|---|---|---|
| D1 | Profil actif global vs par écran | **Global session UI** (chip moniteur en P2/P6) |
| D2 | Plusieurs bindings même slot | **Non** — un seul ; `upsert` remplace (scope,keyNo) |
| D3 | AID inconnu du profil | Explorer en free-list / usine ; ne pas inventer de bindings |
| D4 | Nommer auto les entrées coffre à la capture | Oui (P3), suggestion K4 `AID · kN · rôle`, éditable |
| D5 | Persister aussi les échecs usine dans le profil | **Non** — seulement session (évite faux négatifs cross-cartes) |

### P1 livré (code)

| Élément | Emplacement |
|---|---|
| Modèle | `app/.../data/model/KeyProfile.kt` |
| Règles / unicité | `KeyProfileRules` |
| Repo meta JSON | `KeyProfileRepository` → `key_profiles_meta.json` |
| UI | `ProfilesScreen` + `ProfileDetailScreen` |
| Nav | Accueil → Profils de clés (à côté du Coffre) |
| Tests | `KeyProfileRulesTest` |

---

## 13. Résumé une phrase

> Le **coffre** nomme les matériaux ; le **profil** dit *où* les utiliser sur une carte ; le **planner** dit *quand* ; le moniteur / dump / encode **enchaînent** sans redemander ce qui est déjà lié.

---

*Mettre à jour ce fichier à chaque tranche livrée (P2+).*
