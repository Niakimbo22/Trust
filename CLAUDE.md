# Règles du projet Trust

Spec complète : `TRUST_SPEC.md` (source de vérité).

- Langue : code en anglais, textes de l'UI en français, commentaires courts en français.
- **Repo public : AUCUN secret dans le code ou l'historique.** Keystore, mots de passe, tokens → uniquement dans les Secrets GitHub (`TRUST_KEYSTORE_BASE64`, `TRUST_KEYSTORE_PASSWORD`, `TRUST_KEY_ALIAS`, `TRUST_KEY_PASSWORD`).
- **Économie CI** :
  - Le build release ne tourne **que** sur un tag `v*` ou en manuel (`workflow_dispatch`). Jamais sur chaque push ou PR.
  - `concurrency` avec `cancel-in-progress: true`, cache Gradle activé.
  - Aucune CI pour les changements de documentation.
  - Pas de push à chaque micro-changement : regrouper les commits, une PR par étape.
- **Versions** : uniquement via les tags `vX.Y.Z` (mineur et patch < 100). versionCode = X*10000 + Y*100 + Z, calculé dans `app/build.gradle.kts` et dans `update/VersionCodes.kt` — les deux doivent rester identiques.
- **MAJ in-app** (`update/`) : ne pas casser. Signature toujours avec le même keystore, sinon l'installation par-dessus échoue.
- **Audio CC BY-NC-ND** : jamais de modification, ré-encodage ou redistribution modifiée des fichiers audio. Crédits obligatoires dans « À propos ».
- Ne pas toucher aux fichiers de données (`content/`) sauf demande explicite. Ils sont produits par `tools/` :
  texte = `extract_wikisource.py` (lancé en local), timings = workflow manuel **Contenu** (`books`: codes, `missing` ou `all`).
- L'app lit `content/` depuis raw.githubusercontent.com (cache disque) : un nouveau contenu n'exige pas de nouvel APK.
- Publier une version depuis l'environnement Claude : le push de tags est refusé → `workflow_dispatch` de **Release** via l'API REST.
- Vérifier que le projet compile (`./gradlew assembleDebug`) avant de pousser.
- Jamais de dépendance payante ni de SDK de pub/analytics/tracking.
