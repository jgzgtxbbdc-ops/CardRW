# Vecteurs golden

Déposer ici des captures APDU de cartes labo (hex), **sans clés de production**.

## Fichiers

| Fichier | Contenu |
|---|---|
| `lab-blank-getversion-apps.json` | Capture terrain GetVersion + apps (blank) |
| `lab-ev3-7apps-2026-07-21.json` | Capture terrain 7 apps |
| `ev1-auth-readdata-full-vectors.json` | Vecteurs fixes labo : dérivation session EV1, ReadData TX PLAIN, Write FULL header=7 |

## Format capture (APDU)

```json
{
  "card_lot": "lab-ev3-4k-001",
  "uid": "…",
  "exchanges": [
    { "tx": "9060000000", "rx": "…91AF" },
    { "tx": "90AF000000", "rx": "…9100" }
  ]
}
```

Procédure de capture : CDC annexe C (à rédiger).

**Interdit :** clés de production, dumps avec secrets, UIDs de cartes clients.
