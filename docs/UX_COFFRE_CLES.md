# Coffre-fort de clés

**Statut :** spec K0 — 2026-07-22  
**Portée :** bibliothèque **nommée** de matériaux crypto sur l’appareil  
**Motivation principale :** ergonomie (noms vs hex 32 car.) — pas la sécu d’entreprise  
**Sécu :** plafond progressif via `SecretStore` ; biométrie optionnelle plus tard  
**Liens :** moniteur Carte [`UX_ECRAN_CARTE.md`](UX_ECRAN_CARTE.md) · profils multi-clés [`UX_PROFIL_CLES.md`](UX_PROFIL_CLES.md) · CDC §6.2 `SecretStore` · anneau §7.3  

---

## 1. Problème

En atelier / labo, les mêmes clés AES reviennent sans cesse. Coller ou retaper 32 hex :

- est lent et source d’erreurs (ex. collage d’un journal APDU) ;
- n’a pas de sens mnémotechnique (« Site A – lecture » vs `A1B2…`) ;
- multiplie la surface où le secret apparaît en clair à l’écran.

Le coffre-fort fournit des **entrées nommées** réutilisables au point d’usage (sheet auth, plus tard write / change key).

---

## 2. Trois notions à ne pas confondre

| Concept | Définition | Portée |
|---|---|---|
| **N° de clé (slot) carte** | Index DESFire 0–13 sur PICC ou application | Modèle carte / droits R·W·RW·Ch |
| **Anneau de clés** (CDC §7.3) | Statut de chaque **slot** sur **cette** app (usine, testée, échec…) | Moniteur, lié à la carte posée |
| **Coffre-fort** | Portefeuille appareil : **nom → matériau** (16 o AES v0) | Indépendant de la carte |

L’authentification combine toujours :

```text
slot carte (n° DESFire, souvent imposé par P4)
  + matériau (hex saisi  |  entrée coffre  |  raccourci Usine)
```

Le dropdown coffre choisit le **matériau**, jamais le n° de slot.

---

## 3. Principes produit

1. **Noms d’abord** — l’utilisateur travaille avec des libellés ; le hex est un mode d’entrée / secours.  
2. **Accès au point d’usage** — dès qu’une op demande un matériau : coffre **ou** saisie, sans quitter le flux (sheet).  
3. **Enregistrer sans friction** — saisie hex + case « Enregistrer dans le coffre » (nom défaut incrémental).  
4. **Mécanique avant forteresse** — K1–K2 utiles sans biométrie ; le modèle de données permet le verrou plus tard.  
5. **Pas de secret dans Room en clair** — métadonnées listables séparées des octets (`SecretStore`).  
6. **Labo ≠ prod** — disclaimer ; pas de promesse « coffre fort entreprise » au MVP.

---

## 4. UX

### 4.1 Sheet auth (intégration U2 / K2)

```
Authentifier pour <intention>
Slot carte requis : n°K (rôle)     ← P4, UX_ECRAN_CARTE

Matériau
  ○ Coffre-fort
      [ Nom de l’entrée          ▾ ]   ← récents en tête
  ○ Saisie hex
      [ 32 caractères hex ]
      ☐ Enregistrer dans le coffre-fort
         Nom : [ key3 ]                ← si coché ; défaut keyN libre

[ Usine 00…00 ]                      ← raccourci labo, hors auto-save
[ Annuler ]  [ Authentifier ]
```

| Situation | Défaut UI |
|---|---|
| Coffre ≥ 1 entrée | Mode **Coffre** ; dernière utilisée (globale ou par contexte si dispo) |
| Coffre vide | Mode **Saisie hex** ; case enregistrer **proposée** (non forcée) |
| Session déjà OK pour l’op | Pas de sheet (P1) |
| R = Free | Pas de matériau requis |

**Enregistrement :** par défaut **après auth réussie** si la case était cochée (évite de stocker un hex erroné). Option expert ultérieure : « enregistrer même si échec ».

**Usine `00…00` :** ne pas auto-créer une entrée coffre ; l’utilisateur peut l’enregistrer manuellement sous un nom s’il le souhaite.

**Libellés anti-ambiguïté :** toujours distinguer « Slot carte n°1 » et « Matériau : *Site A – lecture* ».

### 4.2 Noms

- Défaut à la création : `key1`, `key2`, … = plus petit `keyN` **libre** (réutilise les trous après suppression).  
- Renommage en un geste (liste coffre ou après save).  
- Contraintes : non vide, unique insensible à la casse, longueur max raisonnable (ex. 40), trim.  
- Si le « nom » ressemble à 32 hex → avertissement (confusion champ nom / matériau).  
- **Suggestion** contextuelle optionnelle (ex. `App F4 01 01 · lecture`) à l’enregistrement depuis une sheet — éditable, pas imposée.  
- Pas de défaut du type `AID-…-slot1` imposé (le coffre est transverse aux cartes).

### 4.3 Écran gestion « Coffre-fort »

- Entrée navigation (Accueil ou menu) : liste des entrées.  
- Affichage : **nom** · dates · éventuellement `••••` + 4 derniers hex en mode expert seulement.  
- Actions : renommer, supprimer, créer (hex + nom), révéler hex (geste explicite).  
- Pas de hex complet en clair dans la liste par défaut.

### 4.4 Scroll / moniteur

Conforme P2 : pas de gestion du coffre dans le scroll du moniteur Carte ; dropdown + save **dans la sheet** ; CRUD long sur l’écran Coffre.

---

## 5. Modèle de données

### Métadonnées (Room ou équivalent non secret)

```text
KeyVaultEntry
  id: String (UUID)
  displayName: String
  createdAt: Long
  updatedAt: Long
  lastUsedAt: Long?          // tri dropdown
  // PAS de key hex ici
```

### Secret

```text
SecretStore alias = "vault." + id
  → ByteArray 16 (AES-128 DESFire labo / v0)
```

- `list` UI = métadonnées seules.  
- `get(alias)` uniquement au moment auth / reveal / export.  
- Wipe entrée = delete métadonnée + `SecretStore.delete`.  
- `clearAll` coffre = toutes les entrées `vault.*` (+ rows).

### Implémentations `SecretStore`

| Phase | Store secrets | Gate utilisateur |
|---|---|---|
| **K1 mécanique** | Persistant app : AES-GCM + Android Keystore **ou** équivalent CDC §6.2 (plus `InMemory` seul) | Aucun prompt à chaque usage |
| **K3 optionnel** | Idem | BiometricPrompt / device credential avant `get` si option « verrouiller le coffre » |
| Debug | Peut rester permissif | Jamais en release |

**Mot de passe applicatif CardRW maison :** non retenu pour le MVP (réinventer auth, oubli mdp). S’appuyer sur biométrie / credential appareil au K3.

**Backup Android :** exclure secrets (déjà CDC §6.2).

---

## 6. API / couches code (cible)

```text
KeyVaultRepository
  listEntries(): Flow<List<KeyVaultEntryMeta>>
  nextDefaultName(): String              // keyN libre
  put(name, key16): id                   // crée ou update nom
  rename(id, newName)
  delete(id)
  material(id): ByteArray                // via SecretStore ; touch lastUsedAt
  // plus tard: unlockIfNeeded()
```

- ViewModel auth reçoit un `material: ByteArray` déjà résolu (sheet) — ne pas stocker le hex longtemps dans `CardUiState`.  
- Journal APDU : **ne jamais** annoter avec le matériau en clair.

Tests unitaires : `nextDefaultName`, unicité noms, alias `vault.*`, pas de secret dans la couche meta.

---

## 7. Sécurité progressive (hors MVP mécanique)

| Niveau | Contenu | Quand |
|---|---|---|
| **N0** | At-rest via Keystore / EncryptedFile ; isolation app | K1 |
| **N1** | Option « exiger biométrie / PIN appareil pour utiliser le coffre » ; session unlock timeout | K3 |
| **N2** | Export coffre chiffré, politique entreprise, anti-screenshot reveal | plus tard |

Ne **pas** bloquer K1–K2 sur N1 : en atelier (gants, une main, carte posée) le prompt permanent est une friction excessive pour clés labo.

Disclaimer UI : coffre destiné aux clés de labo / test ; politiques d’entreprise pour la prod.

---

## 8. Tranches d’implémentation

| ID | Contenu | Critère « done » | Dépendances |
|----|---------|------------------|-------------|
| **K0** | Cette spec + REPRISE + renvoi CDC | Docs mergées | — |
| **K1** | Meta + SecretStore persistant + CRUD + écran liste minimal | ✅ 2026-07-22 — EncryptedPrefsSecretStore + VaultScreen | `SecretStore` réel |
| **K2** | Sheet auth : dropdown **ou** hex + ☐ enregistrer (nom keyN) | ✅ 2026-07-22 — AuthSheet coffre/hex + save après succès | U2 |
| **K3** | Unlock biométrique / device credential optionnel | Option OFF par défaut ; ON → prompt avant `material()` | K1 |
| **K4** | Polish : suggestions de nom, récents par app, reveal, export | Confort | K2 |

**Ordre recommandé global :** U1 → U2 → **K1–K2** (ou K1 // U2) → U3 → U4…  
K2 sans U2 est possible (intégrer au panneau auth inline) mais **doublon de travail** si U2 suit : préférer U2 puis K2.

**Write v1 :** même composant « matériau » (coffre | hex | usine).

---

## 9. Hors scope K0–K2

- Diversification AN10922 / dérivation dans le coffre  
- Sync cloud / partage multi-appareils  
- Un coffre par AID imposé  
- Remplacer l’anneau de slots (§7.3)  
- Auto-probe usine qui remplit le coffre  
- Mot de passe maître CardRW custom  

---

## 10. Checklist terrain (K2)

- [ ] Créer `key1` via saisie + case enregistrer après auth OK  
- [ ] Auth suivante : choisir `key1` dans le dropdown, sans retaper l’hex  
- [ ] Renommer en libellé mnémo ; dropdown à jour  
- [ ] Supprimer une entrée ; `keyN` réutilisé pour le prochain défaut si trou  
- [ ] Raccourci Usine sans créer d’entrée  
- [ ] Kill app → entrées toujours là (K1 persistant)  
- [ ] Hex absent du journal APDU  

---

## 11. Règles courtes

1. Slot carte ≠ entrée coffre — toujours les deux libellés.  
2. Au point d’usage : coffre **ou** hex (+ save optionnel).  
3. Noms `keyN` libres, renommables ; secrets hors table meta.  
4. Mécanique d’abord ; verrou appareil optionnel ensuite — pas de mdp app custom au MVP.
