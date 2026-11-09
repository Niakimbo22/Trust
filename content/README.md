# Contenu de Trust

| Fichier | Rôle |
|---|---|
| `books.json` | Livres disponibles (code, nom, nb de chapitres/versets, chemins, URL audio). |
| `text/<CODE>.json` | Texte Crampon 1923 : `chapters[].verses[]` avec `v` (n°), `t` (texte), `h` (titre de section, optionnel), `n` (notes de Crampon, optionnel). |
| `timings/<CODE>.json` | Début de chaque verset dans l'audio, en secondes : `chapters[].verses[i]` = début du verset i+1 ; `chapters[].start` = annonce « Chapitre N ». `version` augmente à chaque correction. |

## Sources
- Texte : *La Sainte Bible*, trad. Augustin Crampon, éd. 1923 — domaine public, transcription Wikisource.
- Audio : Mission Web (archive.org), licence CC BY-NC-ND — lu en streaming, **jamais modifié ni redistribué**.

## Régénérer (outils dans `tools/`)
1. Texte : `extract_wikisource.py page.html CODE "Nom"` sur `https://fr.wikisource.org/w/index.php?title=Bible_Crampon_1923/<Livre>&action=render`.
2. Timings : transcription horodatée de l'audio (faster-whisper `small`, local, usage interne uniquement), puis `align_timings.py` qui aligne mot à mot avec le texte.
