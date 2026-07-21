# Vecteurs golden

Déposer ici des captures APDU de cartes labo (hex), **sans clés de production**.

Format suggéré (JSON par scénario) :

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
