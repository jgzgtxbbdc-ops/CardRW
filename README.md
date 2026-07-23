# CardRW

Application Android pour **encoder, lire et comprendre** des cartes MIFARE DESFire EV3 par NFC.

- **applicationId :** `com.cardrw.app`
- **Cahier des charges :** [`docs/cahier-des-charges-desfire-ev3.md`](docs/cahier-des-charges-desfire-ev3.md)
- **Release :** **v0.5** — session authentifiée & exploration lecture (**validée terrain**)

## Modules

| Module | Rôle |
|---|---|
| `:app` | UI Jetpack Compose, NFC (`enableReaderMode`), Hilt, Room, `SecretStore` |
| `:desfire-core` | Kotlin pur (sans Android) — framing APDU, AuthenticateAES, SM EV1, lecture |

## Prérequis

- Android Studio / JDK 11+ (JBR inclus Android Studio OK)
- SDK Android 35, minSdk **26**
- Appareil NFC réel (l’émulateur ne simule pas les tags)

```bash
# Tests unitaires desfire-core
./gradlew :desfire-core:test

# Build debug
./gradlew :app:assembleDebug
```

## Structure v0.5

- Détection IsoDep, identité (GetVersion), mémoire libre, liste d’applications
- **SelectApplication** sur AID choisi (connexion NFC maintenue)
- **AuthenticateAES** + **SM EV1** (CMAC / CRC32 / AES session)
- Badge session UI : app · n° clé · niveau SM
- Explorateur lecture : key settings, fichiers, droits, comm mode, **ReadData Standard**
- Journal APDU annoté
- Interface `SecretStore` (impl. Keystore en v1)
- Strings externalisées (FR)

### Hors scope v0.5

Write, create app/fichier, templates, dumps, formatage, SM EV2.

## Sécurité / labo

- **Jamais** de clés de production ni dumps réels dans le repo
- Clé usine labo `00…00` proposée par défaut dans l’UI d’auth
- **Export dump moniteur** : structure + données lues **sans** matériaux de clés ; le hex d’un dump n’est pas un secret de carte
- **Export / révélation coffre** : le hex AES peut être copié **volontairement** (clair) — responsabilité utilisateur ; verrou biométrie (K3) optionnel
- Sessions : clés zérotées en mémoire à l’invalidation (Select / re-auth / clear)
- Cartes labo : voir CDC §14 et [`docs/NOTES_LABO.md`](docs/NOTES_LABO.md)

## Licence

Repo **privé** en v0–v0.5 ; open source probable à v1 (Apache-2.0).
