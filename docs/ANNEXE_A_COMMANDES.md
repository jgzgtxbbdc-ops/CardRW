# Annexe A — Table des commandes DESFire (vivante)

Source de vérité code : `com.cardrw.desfire.command.DesfireCommand`.

## Scope freeze v0

| Opcode | Nom | SM | Notes |
|---|---|---|---|
| `0x60` | GetVersion | — | 3 frames (AF) |
| `0xAF` | AdditionalFrame | — | Chaining |
| `0x6A` | GetApplicationIDs | — | Liste AID |
| `0x5A` | SelectApplication | — | AID 3 o / PICC `000000` ; **invalide session auth** |
| `0x6E` | GetFreeMemory | — | 3 o LE |

## v0.5 (auth + exploration) ✅

| Opcode | Nom | SM | Notes |
|---|---|---|---|
| `0xAA` | AuthenticateAES | → SM EV1 | Flux EV1 : ek(RndB) / ek(RndA\|\|RndB') / ek(RndA') |
| `0x51` | GetCardUID | FULL après auth | UID réel si Random ID |
| `0x6F` | GetFileIDs | plain + CMAC si auth | Liste FileNo |
| `0xF5` | GetFileSettings | plain + CMAC si auth | Type, comm mode, droits, taille |
| `0x45` | GetKeySettings | plain + CMAC si auth | Settings + max keys |
| `0xBD` | ReadData | plain / MAC / full fichier | Chaining AF ; SM selon FileSettings |

### SM EV1 (après AuthenticateAES)

| Comm mode fichier | TX commande | RX données |
|---|---|---|
| Plain | CMAC IV (sans append) | payload + CMAC 8 o (si auth) |
| MACed | CMAC IV + append 8 o | payload + CMAC 8 o |
| Fully enciphered | CRC32(cmd\|\|data) + AES-CBC | AES-CBC + CRC32(payload\|\|status) |

Session key AES : `RndA[0..3]\|\|RndB[0..3]\|\|RndA[12..15]\|\|RndB[12..15]`.

## v1+

CreateApplication `0xCA`, CreateStdDataFile `0xCD`, WriteData `0x3D`, ChangeKey `0xC4`, FormatPICC `0xFC`, AuthenticateEV2First `0x71`, …

Voir enum complète dans le module `desfire-core`.
