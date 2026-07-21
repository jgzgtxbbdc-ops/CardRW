# Cahier des charges — CardRW (encodage MIFARE DESFire EV3)

**Nom commercial :** CardRW  
**applicationId :** `com.cardrw.app`  
**Version document :** 1.2 — 21 juillet 2026  
**Statut :** document de travail — arbitrages §12 clos ✅  
**Historique :**

| Version | Date | Changements |
|---|---|---|
| 1.0 | 2026-07-21 | Version initiale validée sur les décisions ✅ |
| 1.1 | 2026-07-21 | Protocole DESFire explicite, découpage releases, sécurité locale des secrets, robustesse NFC terrain, NFR, presets détaillés |
| 1.2 | 2026-07-21 | Arbitrages : nom CardRW, open source différé, SecretStore, `{{uid}}`, idempotence hybride, labo cartes |

---

## 1. Vision du produit

**CardRW** est une application Android qui permet d'**encoder, lire et comprendre** des cartes MIFARE DESFire EV3 par le NFC, avec une approche **didactique par la manipulation** : chaque écran explique ce qui se passe, et le journal APDU rend visibles les vraies trames échangées avec la carte.

**Différenciation :** ce n'est ni un simple outil d'atelier, ni un cours théorique — c'est un outil de production qui apprend en s'utilisant.

**Promesse centrale :** le technicien voit le **vrai modèle** de la carte (applications, fichiers, clés, droits, modes de communication) et les **vraies trames**, avec des garde-fous qui évitent les erreurs irréversibles courantes en atelier.

---

## 2. Public cible

**Technicien en contrôle d'accès** qui connaît globalement ce qu'est une carte DESFire, mais qui a besoin de « jouer » avec pour comprendre son fonctionnement interne (applications, fichiers, clés, droits d'accès).

Conséquences de conception :

- Vocabulaire technique réel (AID, clé maître, droits d'accès, comm mode, secure messaging) **avec explication contextuelle** à chaque étape.
- Pas de simplification trompeuse : on montre le vrai modèle de la carte.
- Les erreurs sont des opportunités pédagogiques (messages explicatifs, pas de code d'erreur brut seul).
- Le journal APDU est un objet de première classe, pas un menu de debug caché.
- Deux densités d'UI : **débutant** (plus d'explications) / **expert** (plus compact) — bascule globale 🆕.

---

## 3. Modèle DESFire (référence produit)

### 3.1 Arborescence

```
Carte (PICC)
 ├── Clé maître PICC + key settings PICC
 └── Applications (identifiées par un AID ; limite théorique 28, effective selon mémoire / config)
      ├── Jusqu'à 14 clés (0–13), clé 0 = maître d'application
      ├── Key settings application
      └── Fichiers (FileNo typiquement 0–31 selon type / config)
           ├── Standard Data  (données brutes)
           ├── Value          (compteur / crédit : credit, debit, limited credit)
           ├── Linear Records (journal à enregistrements)
           └── Cyclic Records (journal tournant)
```

### 3.2 Concepts obligatoires dans l'UI

Ces notions font partie du **modèle affiché**, pas d'un glossaire caché :

| Concept | Affichage attendu |
|---|---|
| **Droits d'accès** | Par fichier : Read / Write / Read&Write / ChangeAccessRights → n° de clé, **Free (0xE / 14)** ou **Never (0xF / 15)** |
| **Communication mode** | Plain / MACed / Fully enciphered — par fichier 🆕 |
| **Key settings** | Vue lisible **bit à bit** (PICC et application) + avertissement avant gel irréversible 🆕 |
| **Session d'authentification** | Quelle app, quelle clé, niveau de SM ; invalidée par désélection / perte de champ 🆕 |
| **UID** | UID réel ou **Random ID** ; si random, possibilité de GetCardUID après auth 🆕 |
| **Mémoire** | Taille / libre (GetFreeMemory) ; limite d'apps dépend de la mémoire restante |

### 3.3 Noms d'applications

DESFire ne stocke pas de libellé humain pour un AID. L'app maintient une **base locale de mapping AID → nom** 🆕 :

- presets intégrés (voir §8.5) ;
- noms saisis par l'utilisateur ;
- exportables avec dumps/templates si utile.

---

## 4. Périmètre fonctionnel

### 4.1 Fonctionnalités cibles (périmètre produit complet)

| # | Fonctionnalité | Notes | Dès |
|---|---|---|---|
| 1 | Détection + lecture d'identité | UID / Random ID, type (heuristique EV1/EV2/EV3 via GetVersion), HW/SW, mémoire, mémoire libre | v0 |
| 2 | Liste et sélection des applications | Noms lisibles si connus (mapping local) | v0 |
| 3 | Journal APDU annoté | Trames brutes + explication ; niveaux résumé / détaillé / hex 🆕 | v0 |
| 4 | Authentification AES + secure messaging | Voir §5 — **exigence protocole, pas option** 🆕 | v0.5 |
| 5 | Explorateur arborescent (lecture) | PICC → apps → fichiers ; détail nœud | v0.5 |
| 6 | Lecture / écriture fichiers Standard | Comm mode respecté | v0.5–v1 |
| 7 | Gestion des clés par application | Changement, auth, statut usine (voir §7.1) | v1 |
| 8 | Création d'application | AID, nb clés, key settings | v1 |
| 9 | Création fichiers Standard | + droits + comm mode | v1 |
| 10 | Matrice des droits d'accès | Tableau clé × fichier × droit + Free/Never | v1 |
| 11 | Key settings explicites | Vue bits + gel irréversible | v1 |
| 12 | Dump JSON | Structure + données lisibles ; secrets optionnels | v1 |
| 13 | Changement clé maître PICC | Avertissements renforcés | v1 |
| 14 | Formatage carte | Double confirmation + UID | v1 |
| 15 | Fichiers Value | Credit / debit / lecture | v1.1 |
| 16 | Fichiers Record (linéaire / cyclique) | Écriture / lecture d'enregistrements | v1.1 |
| 17 | Templates + variables | `{{counter}}`, `{{uid}}`, `{{date}}` ; moteur extensible | v1.1 |
| 18 | Encodage en série | Idempotence, journal CSV, reprise | v1.1 |
| 19 | Génération template depuis dump (et inverse) | Placeholders choisis par l'utilisateur | v1.1 |
| 20 | Restauration depuis dump | Dry-run + plan d'ops avant exécution 🆕 | v1.2 |
| 21 | Profils / presets métier | Badge, porte-monnaie, compteur (annexes) | v1.1 |
| 22 | Mode débutant / expert | Densité UI | v1 |
| 23 | Comparaison de deux dumps | Diff d'arborescence 🆕 | v1.2 |
| 24 | `{{champ_libre}}` saisi à la volée | | v1.2 |
| 25 | i18n anglais | Strings externalisées dès v0 🆕 | EN en v1.2 |

### 4.2 Fonctionnalités exclues (assumées, avec encarts « Pour aller plus loin »)

- DES, 2K3DES, 3DES, 3K3DES ✅ (**AES-128 uniquement**)
- Transaction MAC, Secure Dynamic Messaging (SDM), originalité EV3
- Diversification de clés (AN10922)
- Fichiers Backup, transactions chaînées avancées
- NDEF, HCE, peer-to-peer
- **Chiffrement des fichiers exportés** dump/template ✅ (clés en clair si option cochée — avertissement). *Le stockage local dans l'app est lui chiffré — voir §9.* 🆕
- Backend, compte utilisateur, cloud
- Import de dumps binaires tiers (formats industrie) en v1 — hook d'import pluggable prévu pour plus tard 🆕

Chaque exclusion apparaît dans un encart « Pour aller plus loin » aux endroits pertinents.

---

## 5. Exigences protocole DESFire 🆕

> **Principe :** le secure messaging n’est pas un « nice to have ». Sans lui, les opérations authentifiées ne sont ni fiables ni fidèles au modèle réel.

### 5.1 Framing

| Mode | Support | Notes |
|---|---|---|
| **Native DESFire wrappé** (`90 CMD … 00` / status `91 xx`) | ✅ requis | Chemin principal via `IsoDep.transceive()` |
| ISO 7816 wrapping « complet » alternatif | ❌ hors scope v1 | Clarifier en doc interne pour éviter l’ambiguïté « ISO wrapped » |
| Command chaining / réponses longues | ✅ requis | Lecture/écriture de gros fichiers |

### 5.2 Authentification et secure messaging

| Capacité | Release | Détail |
|---|---|---|
| Authenticate AES legacy (`0xAA` / flux EV1) | **v0.5** | + SM EV1 (CRC / chiffrement session selon commandes) |
| AuthenticateEV2First / NonFirst (`0x71`…) | **v1** (cible EV3 « propre ») | + SM EV2 (CMAC, session keys EV2) |
| Détection / affichage du niveau de SM actif | **v0.5** | Badge session : « Auth AES · SM EV1 » / « EV2 » |
| Comm modes fichier | **v0.5+** | Plain / MAC / Full — appliqués correctement en lecture/écriture |

**Matrice de compatibilité à maintenir** (vivante, dans le repo) :

`EV1 | EV2 | EV3` × tailles mémoire × config usine × SM EV1 / EV2.

### 5.3 Module `desfire-core`

| Exigence | Détail |
|---|---|
| Langage | Kotlin pur, **indépendant d’Android** |
| Responsabilités | Construction/parsing commandes, CRC32, AES, CMAC, session, chaining |
| Tests | Unitaires + **vecteurs golden** (captures hex de cartes labo) |
| Mode capture | Si parsing/interprétation échoue, le journal enregistre quand même les trames brutes |
| Inventaire commandes | Table maintenue : cmd, nom, SM requis, status codes, notes support |

### 5.4 Heuristique type de carte

`GetVersion` ne fournit pas toujours un libellé marketing trivial. Documenter l’heuristique utilisée (vendor, HW major/minor, etc.) et afficher **les champs bruts** en plus du libellé « EV3 » / « EV2 » / « EV1 (probable) ».

---

## 6. Architecture technique

| Choix | Décision | Justification |
|---|---|---|
| Langage | **Kotlin natif** | `IsoDep.transceive()` + trames natives ; pas de stack Flutter/RN mûre |
| UI | **Jetpack Compose + Material 3** | Wizard, pédagogie, mode sombre |
| Architecture | **MVVM + Hilt** | Testable, standard moderne |
| Persistance | **Room (données non secrètes) + `SecretStore`** ✅ | Room pour templates/dumps/sessions **sans** secrets en clair ; secrets via interface `SecretStore` (voir §6.2) |
| Couche DESFire | **`desfire-core` maison** | Pas de TapLinx ; esprit didactique |
| NFC | **`enableReaderMode`** prioritaire 🆕 | Contrôle fiable en atelier (vs intents seuls) |
| minSdk | **26 (Android 8.0)** | Large couverture appareils NFC |
| Connectivité | **100 % hors-ligne** | Aucune donnée ne quitte l’appareil sauf export manuel |
| i18n | **Strings externalisées dès v0** 🆕 | FR UI en v1 ; EN en v1.2 |
| Identité app | **CardRW** · `com.cardrw.app` ✅ | Nom commercial stable ; applicationId figé avant toute distrib terrain |
| Licence / distribution | **Repo privé v0–v0.5** ; open source probable à **v1** (Apache-2.0) ✅ | Pas de clés/dumps réels dans le repo ; README assume l’export clair des secrets |

### 6.1 Exigences non fonctionnelles 🆕

| Domaine | Exigence |
|---|---|
| **Tests** | Unitaires `desfire-core` ; checklist manuelle NFC par release ; cartes sacrifiables pour format/gel |
| **Observabilité terrain** | Export support : journal APDU + version app + modèle appareil + GetVersion carte |
| **Session série** | Keep-screen-on, retour haptique succès/échec, latence UI minimale entre deux cartes |
| **Antenne** | Aide visuelle « où poser la carte » ; message si tag perdu mid-op |
| **Timeout IsoDep** | Configurable / valeurs éprouvées pour ops crypto (éviter timeouts trop courts) |
| **Accessibilité** | Contrastes Material 3 ; textes d’erreur actionnables |
| **Disclaimer** | Usage légitime / politique de clés d’entreprise (écran premier lancement) |

### 6.2 Stratégie secrets (`SecretStore`) ✅

| Phase | Stratégie |
|---|---|
| **v0 – v0.5** | Peu ou pas de secrets persistés ; interface Kotlin `SecretStore` définie dès le jour 1 |
| **v1** | Implémentation : **AES-GCM + clé dans Android Keystore**, blobs / fichiers chiffrés (`EncryptedFile` ou équivalent) pour clés et dumps « avec secrets » |
| **SQLCipher** | **Non par défaut** — uniquement si, à l’usage, le modèle métier est trop entremêlé aux secrets pour un double store |
| **Debug** | Build `debug` local éventuellement plus permissif ; **jamais** en release |
| **Backup Android** | Exclure les stores de secrets de l’auto-backup (ou master key non extractible) |
| **Export utilisateur** | Inchangé : JSON volontairement en clair si option cochée + avertissement |

---

## 7. Structure de l'interface

Structure retenue : **hybride wizard guidé + explorateur arborescent**, avec quatre entrées principales.

```
Accueil
 ├── 📡 Carte         — lire / encoder une carte (wizard + explorateur)
 ├── 🧩 Templates     — créer, éditer, lancer une série          (dès v1.1)
 ├── 💾 Dumps         — importer, exporter, restaurer            (restore v1.2)
 └── 📜 Journal APDU  — historique des trames (global ou par session)
```

Bascule **Débutant / Expert** (globale) : plus ou moins de prose pédagogique, même puissance fonctionnelle.

### 7.1 Parcours « Carte » (wizard)

```
Approche la carte → Profil détecté (UID/Random, type, mémoire, SM dispo)
  → Choisir une action :
      Explorer | Créer une application | Gérer les clés | Formater | …
  → Assistant en 3–4 écrans avec explications contextuelles
  → Récapitulatif → Exécution → Résultat + journal APDU
```

### 7.2 Explorateur arborescent

Arbre repliable : PICC → Applications → Fichiers.  
Tap sur un nœud = détail + actions contextuelles (lire, écrire, modifier droits, supprimer).  
Affichage des **comm modes** et droits (Free/Never inclus).  
Bouton « + » pour créer app/fichier (selon release).  
C’est la vue « je comprends ce qu’il y a sur ma carte ».

### 7.3 Écran « Anneau de clés » (par application)

```
Clé n°   Type      Statut              Actions
0        AES-128   Modifiée            [Authentifier] [Changer]
1        AES-128   Usine (test OK)     [Authentifier] [Changer]
2        AES-128   Non testée          [Authentifier] [Changer]
…
13       AES-128   Échec auth          [Authentifier] [Changer]
```

Statuts possibles 🆕 : `Non testée` | `Usine (auth OK)` | `Non usine (auth OK avec clé fournie)` | `Échec` | `Inconnu`.

### 7.4 Matrice des droits d'accès

Tableau croisé : lignes = fichiers, colonnes = Read / Write / Read+Write / Change.  
Cellules = n° de clé, **Free**, ou **Never**. Code couleur + légende.

### 7.5 Écran « Journal APDU »

```
→ 90 5A 00 00 03 02 F4 01 00   SelectApplication (AID 0x01F402)
← 91 00                         Success
→ 90 0A 00 00 01 01 00         AuthenticateAES (clé n°1)…
```

Chaque trame : horodatage, direction, hex brut, annotation, couleur (envoi / réponse / erreur).  
**Niveaux d’annotation** 🆕 : Résumé · Détaillé · Hex pur.  
Filtrable, exportable. Consultable pendant et après chaque opération.  
Timeline optionnelle : intention utilisateur → commandes → résultat carte 🆕.

### 7.6 Glossaire contextuel 🆕

Bottom sheet réutilisable sur les termes (AID, CMAC, key settings, file type, Free/Never, SM…).  
Accessible depuis les libellés « (i) » sans quitter le flux.

---

## 8. Dumps de cartes

### 8.1 Comportement

Un dump capture :

- type de carte (libellé + champs GetVersion bruts) ;
- UID ou indication Random ID ;
- arborescence (applications, fichiers, droits, key settings, comm modes) ;
- contenu des fichiers **lisibles avec les clés disponibles** ;
- métadonnées de test de clés (voir ci-dessous).

**Limite affichée honnêtement :**  
« Ce dump ne contient que ce qui était lisible avec les clés disponibles. Fichiers non lus : … »

### 8.2 Probing des clés usine 🆕 (amendé)

Le test automatique `00…00` sur toutes les clés **n’est plus le comportement unique**.

| Mode dump | Probing usine | Usage |
|---|---|---|
| **Rapide** (défaut) | Non | Terrain / cartes inconnues / prod |
| **Complet / labo** | Oui, optionnel | Apprentissage, cartes de test |

Règles :

- Ne probe que les clés **sans valeur fournie par l’utilisateur**.
- Stocker pour chaque clé : `tested_at`, `auth_result`, `is_factory` (si déterminable), pas un simple booléen opaque.
- Avertissement : « Ce test authentifie réellement la carte ; selon la config, les échecs peuvent compter comme tentatives. »

### 8.3 Secrets dans le dump

- **Option « Inclure les clés »** : cochable, **décochée par défaut** ✅.
- Si cochée : avertissement « ce fichier devient un secret ».
- **Pas de chiffrement du fichier exporté** ✅ — responsabilité utilisateur.
- Sections JSON distinctes : `structure` / `data` / `secrets` 🆕 (même fichier, parsing plus sûr).
- **Hash d’intégrité** (ex. SHA-256 du contenu canonique hors champ hash) pour détecter corruption 🆕.

### 8.4 Format JSON (schéma indicatif v2)

```json
{
  "format_version": 2,
  "created_at": "2026-07-21T09:30:00+02:00",
  "app_version": "1.0.0",
  "integrity_sha256": "…",
  "card": {
    "type_label": "DESFire EV3 (probable)",
    "version_raw": { "hw": "…", "sw": "…" },
    "uid": "04A3B2C1D4E580",
    "uid_kind": "fixed",
    "memory_bytes": 8192,
    "free_memory_bytes": 4200
  },
  "structure": {
    "picc_key_settings": { "raw": "0F", "bits": { "…": true } },
    "applications": [
      {
        "aid": "01F402",
        "name": "Badge d'accès",
        "key_settings": { "raw": "0F", "bits": { "…": true } },
        "keys": [
          {
            "number": 0,
            "type": "AES-128",
            "is_factory": true,
            "tested_at": "2026-07-21T09:30:05+02:00",
            "auth_result": "success_factory"
          }
        ],
        "files": [
          {
            "number": 0,
            "type": "standard",
            "size": 32,
            "comm_mode": "plain",
            "access_rights": {
              "read": 0,
              "write": 0,
              "read_write": 14,
              "change": 0,
              "notes": "14=Free(0xE), 15=Never(0xF)"
            }
          }
        ]
      }
    ]
  },
  "data": {
    "applications": [
      {
        "aid": "01F402",
        "files": [
          { "number": 0, "data_hex": "48656C6C6F…", "readable": true }
        ]
      }
    ]
  },
  "secrets": {
    "included": false,
    "picc_master_key": null,
    "application_keys": []
  }
}
```

**Politique de migration :** chaque `format_version` a un lecteur capable de migrer depuis N-1 (au minimum). 🆕

### 8.5 Restauration (v1.2)

1. Import du dump.  
2. **Dry-run** : plan d’opérations (créer app, créer fichier, écrire…).  
3. Options : structure seule / données sans toucher aux clés / complet.  
4. Exécution avec journal ; si clés manquantes → échec explicatif par étape, pas d’échec silencieux.

---

## 9. Sécurité et garde-fous

### 9.1 Risques carte / atelier

| Risque | Garde-fou |
|---|---|
| Gel irréversible des key settings | Écran d’avertissement explicite avant validation |
| Perte de la clé maître après changement | Récap + confirmation « j’ai noté la nouvelle clé » |
| Formatage | Double confirmation + rappel de l’UID affiché |
| Export dump/template avec secrets | Avertissement fort ; option décochée par défaut |
| Carte déjà encodée en série | Refus automatique (idempotence) |
| Probing usine agressif | Désactivé par défaut ; mode labo seulement 🆕 |

### 9.2 Secrets sur l’appareil 🆕

Hors-ligne ≠ sûr. Les templates et dumps importés peuvent contenir des clés.

| Mesure | Détail |
|---|---|
| Chiffrement au repos | `SecretStore` (Keystore + AES-GCM) pour secrets ; Room sans secrets en clair |
| Distinction claire | *Stockage app protégé* vs *export fichier volontairement en clair* |
| Verrou optionnel | Code app / biométrie avant affichage ou export des secrets |
| Transparence | Écran « Secrets présents sur cet appareil » + action de purge |
| Premier lancement | Disclaimer usage légitime et responsabilité des clés |

---

## 10. Templates d'encodage (v1.1+)

### 10.1 Définition

Un **template = une séquence ordonnée d’opérations** (créer application → créer fichiers → authentifier → écrire des données), avec des **placeholders** dans les données.

| Placeholder | Signification | v1.1 |
|---|---|---|
| `{{counter}}` | Compteur incrémental (formatable) | ✅ |
| `{{uid}}` | UID de la carte — voir §10.1.1 | ✅ |
| `{{date}}` | Date/heure d’encodage | ✅ |
| `{{champ_libre}}` | Saisie opérateur à la volée | v1.2 |

Le **moteur de placeholders est extensible dès v1.1** (ajout de tokens sans refonte). 🆕

#### 10.1.1 Format de `{{uid}}` ✅

| Règle | Valeur |
|---|---|
| Défaut v1.1 | `hex_upper` : hexadécimal **majuscule**, **sans séparateur** |
| Ordre des octets | **Tel que renvoyé par l’API NFC Android** (documenté dans l’UI + dry-run) |
| Longueur | Hex exact du UID obtenu — **pas de tronquage silencieux** |
| Champ template | `uid_format` dans le JSON (défaut `hex_upper`) pour extension sans refonte |
| Random ID | Si UID random : documenter ; privilégier GetCardUID après auth quand pertinent ; le CSV de session stocke l’UID **effectivement écrit** |
| v1.2+ | `hex_lower`, `hex_reversed`, `decimal_be`, `decimal_le` |

**Dry-run obligatoire en série :** prévisualiser la valeur concrète (« sera écrit : `04A3B2C1D4E580` à l’offset … »).

**Les templates incluent les clés** nécessaires à l’encodage — même politique d’avertissement / export que les dumps.  
Templates exportables/importables en JSON versionné.

### 10.2 Options d’exécution 🆕

| Option | Description |
|---|---|
| Politique si app existe déjà | `fail` / `skip` / `merge` (merge = sous-ensemble d’ops documenté) |
| Carte non vide sans AID template | `refuse` (défaut) / `format_then_apply` (danger, confirmations) |
| Dry-run | Affiche la séquence APDU prévue sans écrire (ou n’écrit que sur flag labo) |
| Verrou template | Pendant une session série, le template n’est pas éditable |
| Idempotence | Voir §10.4.1 — marqueur = vérité, hash layout = alerte |

### 10.3 Compteur

- **Un compteur par template** ✅ (confirmé pour v1.1).
- Paramètres : valeur de départ, pas (défaut 1), décimal ou hex, largeur fixe + padding (`0042`), fichier cible + offset.
- Persistant entre sessions, modifiable manuellement.
- **N’avance qu’en cas de succès complet** de l’encodage.
- État du compteur **exportable/importable** (backup atelier) 🆕.

### 10.4 Encodage en série

```
Template : Badge d'accès v1
Version template : 3
template_id : 3f2a…   (UUID)
Prochain compteur : 0042        [Modifier]

      [ 📡  En attente d'une carte… ]

Session : 12 encodées ✅   0 échec ❌   [Exporter le journal CSV]
```

**Journal de session CSV :** timestamp, UID écrit, counter, template id/version, status, message d’erreur humain.  
**Reprise** après interruption (compteur persiste).

#### 10.4.1 Idempotence (hybride) ✅

| Mécanisme | Rôle |
|---|---|
| **Fichier marqueur dédié** | **Source de vérité** en encodage série |
| **Hash de layout** | **Filet d’alerte** si layout identique sans marqueur |

**Contenu du marqueur** (fichier Standard réservé, FileNo documenté par preset — ex. `0x0F` ou dernier libre) :

- magic CardRW  
- `template_id` (UUID, pas seulement le nom affiché)  
- `template_version`  
- `counter` attribué  
- éventuellement champs d’audit courts  

**Règles d’exécution série :**

1. Marqueur présent avec même `template_id` → **refus** (compteur non consommé), message clair.  
2. Pas de marqueur, mais hash layout = template → **avertissement** mode expert : refuser (défaut) / forcer (double confirm).  
3. Dernière étape du template en série = **écriture du marqueur** (succès complet seulement si marqueur OK).  
4. Ré-encodage volontaire = action « forcer » protégée (double confirmation), rare.  
5. Presets partagent un layout mais chaque instance de template a son **UUID**.

### 10.5 Robustesse NFC en série 🆕

| Situation | Comportement |
|---|---|
| Retrait carte mid-op | Message « ne pas retirer » ; état partiel documenté ; carte à réexaminer / sacrifier selon étape |
| Timeout / tag lost | Échec explicite ; compteur non avancé ; réessai possible |
| Marqueur même `template_id` | Refus idempotent |
| Layout identique sans marqueur | Alerte / refus selon mode |
| Succès | Haptic + incrément compteur + marqueur + ligne CSV |

### 10.6 Synergie dump ↔ template

- Dump → template : les données deviennent des champs ; l’utilisateur place `{{counter}}` etc.
- Template appliqué puis dumpé : boucle pédagogique.
- Restauration complète dump : v1.2 (dry-run).

### 10.7 Presets métier (annexes fonctionnelles) 🆕

Chaque preset est un template documenté (AID d’exemple, fichiers, droits, clés de démo — **jamais des clés de prod par défaut**).

#### Preset A — Badge d’accès

| Élément | Valeur d’exemple |
|---|---|
| AID | `01F402` |
| Clés | 3 × AES (0 maître, 1 lecture, 2 écriture) |
| Fichier 0 Standard | 32–64 o : identifiant / site / compteur badge |
| Droits | Read=1, Write=2, R/W=Never ou 2, Change=0 |
| Comm mode | MACed ou Full selon niveau péda visé |

#### Preset B — Porte-monnaie (Value)

| Élément | Valeur d’exemple |
|---|---|
| AID | `01F403` |
| Fichier Value | crédit initial paramétrable |
| Droits | credit/debit selon clés distinctes (démo pédagogue) |

#### Preset C — Compteur / journal

| Élément | Valeur d’exemple |
|---|---|
| AID | `01F404` |
| Fichier Cyclic Records | N enregistrements horodatés / événements |

Les valeurs exactes (AID, tailles) sont finalisées à l’implémentation et versionnées avec le preset.

---

## 11. Décisions actées

| # | Décision | Statut |
|---|---|---|
| 1 | Kotlin natif + Compose + MVVM + Hilt + Room | ✅ |
| 2 | Couche DESFire maison (`desfire-core`), pas de TapLinx | ✅ |
| 3 | minSdk 26 | ✅ |
| 4 | 100 % hors-ligne | ✅ |
| 5 | Jusqu’à 14 clés gérables par application | ✅ |
| 6 | AES-128 uniquement | ✅ |
| 7 | Matrice des droits en tableau (Free/Never explicites) | ✅ |
| 8 | Key settings affichés + avertissement gel | ✅ |
| 9 | Dumps JSON structurés versionnés | ✅ |
| 10 | Clés dans export : option cochable, fichier non chiffré | ✅ |
| 11 | Templates avec `{{counter}}`, `{{uid}}`, `{{date}}` | ✅ |
| 12 | Templates incluent les clés | ✅ |
| 13 | Idempotence série : marqueur + alerte hash layout | ✅ |
| 14 | Dump → template | ✅ |
| 15 | UI hybride wizard + explorateur | ✅ |
| 16 | SM EV1 en v0.5, SM EV2 en v1 — exigence protocole | ✅ 🆕 |
| 17 | Probing clés usine : mode labo, pas défaut | ✅ 🆕 |
| 18 | Compteur **par template** | ✅ |
| 19 | Secrets au repos via **`SecretStore`** (pas SQLCipher par défaut) | ✅ |
| 20 | Strings externalisées dès v0 ; UI FR d’abord | ✅ 🆕 |
| 21 | `enableReaderMode` pour le flux NFC principal | ✅ 🆕 |
| 22 | Découpage releases v0 → v1.2 (voir §13) | ✅ 🆕 |
| 23 | Nom **CardRW** · `applicationId` `com.cardrw.app` | ✅ |
| 24 | Repo **privé** en v0–v0.5 ; open source **probable à v1** (Apache-2.0) | ✅ |
| 25 | `{{uid}}` = `hex_upper`, octets NFC natifs ; champ `uid_format` extensible | ✅ |
| 26 | Idempotence : **marqueur = vérité**, hash layout = alerte | ✅ |
| 27 | Jeu labo cartes : cibles quantitatives §14 | ✅ |

---

## 12. Points restant à confirmer

*Arbitrages majeurs du 2026-07-21 clos — voir §11.21–27.*

| # | Question | Statut |
|---|---|---|
| 1 | FileNo exact du marqueur par preset (0x0F vs dernier libre) | ❓ à figer avec les presets v1.1 |
| 2 | Organisation Git (compte / org GitHub au moment de l’open source) | ❓ plus tard |
| 3 | Icône / charte visuelle CardRW | ❓ design |
| 4 | Fournisseur exact des cartes labo (stock du moment) | ❓ achat — critères §14 |

---



## 13. Plan de versions (découpage réaliste) 🆕

### v0 — Prototype protocole & pédagogie de base

**Objectif :** valider `desfire-core` + NFC réel + journal.

- Détection IsoDep, identité, GetVersion, mémoire libre  
- Liste / sélection d’applications  
- Journal APDU annoté (niveaux basiques)  
- Erreurs pédagogiques (status `91 xx` expliqués)  
- Strings externalisées (FR)  
- Tests golden initiaux  

**Critère de done :** sur ≥ 2 appareils et ≥ 3 cartes EV3, lecture d’identité + liste apps stable ; export journal support.

### v0.5 — Session authentifiée & exploration

- Auth AES + **SM EV1**  
- Session auth visible dans l’UI  
- Explorateur arborescent **lecture**  
- Lecture fichiers Standard (plain / mac / full selon support atteint)  
- Gestion basique des timeouts / tag lost  

**Critère de done :** lecture authentifiée de fichiers Standard sur carte de labo avec clés connues.

### v1 — Outil d’atelier utile (sans usine à templates)

- Création application / fichiers Standard / écriture  
- Anneau de clés + changement de clés  
- Matrice des droits + key settings (vue bits) + garde-fous gel  
- Dump JSON v2 (rapide / labo), sans restauration complète  
- PICC master key + formatage (garde-fous)  
- Mode débutant / expert  
- `SecretStore` (Keystore + AES-GCM) pour les secrets  
- SM EV2 (cible cartes EV3 configurées pour EV2)  

**Critère de done :** un technicien peut créer une app + fichier, écrire des données, dumper, changer une clé, sans template.

### v1.1 — Production en série & modèle fichiers complet

- Value + Records  
- Templates + placeholders + compteur par template  
- Encodage en série + CSV + idempotence robuste  
- Dump → template  
- Presets Badge / Porte-monnaie / Compteur  
- Export/import état compteur  

**Critère de done :** session de 20 cartes d’affilée avec journal CSV cohérent et zéro double attribution de compteur.

### v1.2 — Confort, restore, i18n

- Restauration dump (dry-run + plan)  
- `{{champ_libre}}`  
- EN  
- Comparaison de dumps  
- Formats UID étendus / imports binaires éventuels (si priorisé)  

---

## 14. Prérequis matériel & labo ✅

### 14.1 Cibles d’achat (labo)

| Rôle | Cible | Quantité indicative |
|---|---|---|
| Workhorse | DESFire **EV3** 4K ou 8K, form factor **carte ISO** (PVC) | **10–20** |
| Sacrifiables | DESFire **EV3** 2K (format / gel / mauvaises manips) | **5–10** |
| Compat | DESFire **EV2** si dispo | **2–3** |
| Random ID | Au moins un lot / config Random ID si possible | **1+** |
| Cartes « sales » | Déjà écrites (apps existantes) pour explorateur / non-vide | **2–3** |

Préférer l’état **usine** (clés `00…00`) pour les lots neufs. Marketplaces OK pour le labo si chaque lot est **caractérisé** à réception.

### 14.2 Process à réception (obligatoire)

Pour chaque UID : GetVersion, free mem, liste apps, note du lot vendeur → entrée dans la **matrice de compatibilité** (annexe F) **avant** les features d’écriture avancées.

### 14.3 Environnement

- **L’émulateur Android ne simule pas les tags NFC** — tests protocole sur appareil + carte réels.  
- Au moins **2 modèles Android NFC** différents.  
- Jeu de clés de labo documenté en local équipe — **jamais** de clés de production ni dumps réels dans le repo.  
- Captures APDU de référence versionnées dans les tests.

---

## 15. Backlog priorisé (vue synthèse)

| Priorité | Sujet | Release |
|---|---|---|
| **P0** | `desfire-core` + SM + tests golden | v0–v0.5 |
| **P0** | Journal APDU + erreurs pédagogiques | v0 |
| **P0** | Identité carte + liste apps + reader mode | v0 |
| **P1** | Explorateur lecture + auth session | v0.5 |
| **P1** | Create/Write Standard + clés + garde-fous | v1 |
| **P1** | Dump JSON + secrets au repos | v1 |
| **P2** | Templates + série + compteur | v1.1 |
| **P2** | Value / Records + presets | v1.1 |
| **P3** | Restore, diff dumps, EN, champ libre | v1.2 |

---

## 16. Annexes (à enrichir en cours de projet)

- **A.** Table des commandes DESFire supportées (vivante)  
- **B.** Table des status codes `91 xx` et messages UI  
- **C.** Vecteurs de test / procédure de capture golden  
- **D.** Schéma JSON dump `format_version` (JSON Schema formel)  
- **E.** Schéma JSON template  
- **F.** Checklist QA release (appareils × cartes × scénarios)  
- **G.** Glossaire produit (texte des bottom sheets)

---

## 17. Prochaines étapes de travail (équipe)

1. ~~Trancher les ❓ majeurs~~ ✅ (CardRW, licence, secrets, uid, idempotence, labo).  
2. **Initialiser le repo CardRW** : `app` (`com.cardrw.app`) + module `desfire-core` + `SecretStore` (interface) + tests golden.  
3. **Annexe A** — table des commandes v0 prioritaires (scope freeze).  
4. Commander / réunir le jeu de cartes labo (§14) en parallèle.  
5. Implémenter **v0** jusqu’au critère de done.  
6. JSON Schema dump `format_version: 2` quand la lecture d’arborescence se précise (fin v0 / v0.5).

---

*Document vivant : toute décision de code qui contredit ce CDC doit mettre à jour ce fichier dans le même changement.*
