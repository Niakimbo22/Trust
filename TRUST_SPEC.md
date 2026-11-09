# TRUST — Spécification

## Vision
Un **lecteur audio biblique** d'abord, pas une app de lecture avec de l'audio en option. On écoute la Bible comme un livre audio, avec un contrôle précis **au verset près**, une reprise exacte et une interface sobre.

Usage : perso (matin, transports, soir). Publication éventuelle plus tard, donc l'architecture doit pouvoir évoluer.

**Interdits** : réseau social, pubs, abonnement, gamification, tracking, interface surchargée.

## Contenu et droits
- **Texte** : Bible Crampon, édition 1923 (domaine public). Source : Wikisource « Bible Crampon 1923 ».
- **Audio** : enregistrements de **Mission Web** (mission-web.com), Bible Crampon lue, hébergés sur archive.org. Licence **CC BY-NC-ND** : citer la source, aucun usage commercial, **aucune modification des fichiers audio**. On les streame tels quels, on ne les ré-encode pas et on ne les redistribue pas modifiés.
- Un écran **« À propos »** affiche obligatoirement ces crédits et la licence.
- Format audio : on utilise les fichiers **OGG Vorbis** (le seek y est précis, contrairement au MP3 VBR). Un fichier = un livre entier.

## Format des données (dossier `content/`, préparé à part)
- `content/books.json` : liste des livres
  `[{ "id": "JHN", "name": "Jean", "testament": "NT", "chapters": 21, "audioUrl": "https://archive.org/download/Bible-NouveauTestament/04-jean_entier_128.ogg" }]`
- `content/text/JHN.json` : texte
  `{ "chapters": [ { "n": 1, "verses": [ { "n": 1, "t": "Au commencement…" } ] } ] }`
- `content/timings/JHN.json` : repères audio (générés par alignement automatique)
  `{ "version": 1, "durationMs": 0, "verses": [ { "c": 1, "v": 1, "startMs": 0 } ] }`
- Le texte est **embarqué** dans l'APK (léger). Les timings sont **téléchargés** depuis le repo (raw GitHub) et mis en cache, avec un numéro de version. Corriger un timing ne demande donc pas de nouvel APK.

## Architecture
- Kotlin, Jetpack Compose (Material 3), un seul module `app`, package `fr.niakimbo.trust`.
- Pattern MVVM simple, injection manuelle (un `AppContainer`), pas de Hilt pour l'instant.
- Paquets : `audio/` (lecture), `data/` (Room, JSON, dépôts), `ui/` (écrans, thème), `update/` (MAJ in-app).
- **Audio** : Media3 (ExoPlayer + `MediaSessionService`) pour l'arrière-plan, la notification, l'écran verrouillé et le Bluetooth. Cache disque avec `SimpleCache` (streaming + cache).
- **Stockage** : Room. Tables : `progress` (dernière position par livre + global), `history`, `bookmarks` (livre, chapitre, verset, position ms, date, libellé), `notes` (livre, chapitre, verset, texte, dates).
- minSdk 26, targetSdk et versions de libs : dernières versions stables.

## Fonctions — Lecteur (priorité absolue)
- Lecture/pause, barre de progression (temps écoulé et restant, **du chapitre** et du livre).
- Avance/retour réglables : 5, 10, 15, 30 s.
- Verset précédent/suivant, chapitre précédent/suivant.
- Vitesse : 0.75× à 2× par pas de 0.25.
- Arrière-plan, notification, écran verrouillé.
- Minuterie d'arrêt (15, 30, 45, 60 min, fin du chapitre).
- **Reprise exacte** : position sauvegardée toutes les 5 s, à la pause et à la fermeture.

## Fonctions — Bible
- Navigation Livre → Chapitre → Verset rapide.
- Texte affiché pendant l'écoute, **verset en cours surligné**, défilement automatique.
- Toucher un verset → l'audio saute à son début.

## Fonctions — Perso
- Marque-pages (appui long sur un verset), notes par verset, historique d'écoute, reprise depuis l'accueil.

## Design
- Moderne, minimaliste, sobre, très lisible. Thèmes sombre (défaut) et clair.
- Texte biblique en police serif lisible (Literata, licence OFL), UI en sans-serif (Inter).
- Un seul accent de couleur, doux (or/ambre discret).
- Lecteur inspiré des apps de livres audio : grand titre (livre, chapitre), verset en cours, gros bouton lecture, contrôles clairs.

## MAJ automatiques (repris de Dial)
- Au lancement (max 1 fois par jour) et via un bouton **« Vérifier maintenant »** dans Réglages.
- Interroge `https://api.github.com/repos/Niakimbo22/trust/releases/latest` (repo public, sans token), compare avec le `versionCode` installé.
- **Version = tag** : `vX.Y.Z` → versionName `X.Y.Z`, versionCode `X*10000 + Y*100 + Z` (toujours croissant, indépendant du numéro de build).
- Aucun téléchargement silencieux : la MAJ est proposée (carte sur l'accueil + Réglages), l'utilisateur lance l'installation.
- Installation par-dessus via **PackageInstaller** : l'APK est écrit directement dans la session d'installation (barre de progression, aucun fichier temporaire). Permission `REQUEST_INSTALL_PACKAGES` ; si « sources inconnues » n'est pas autorisé, bouton vers le réglage puis reprise automatique au retour.
- Builds debug (`fr.niakimbo.trust.debug`) : MAJ in-app désactivées.
- Signature **toujours avec le même keystore** (sinon l'installation par-dessus échoue).
- Vérifier une MAJ ne consomme aucune minute GitHub Actions. Seul le build en consomme, et il ne tourne qu'à la publication d'une version.

## Plan par étapes
1. **Socle** : projet, thème, navigation, écrans vides, écran À propos, MAJ in-app, CI release.
2. **Contenu Jean** : texte + timings de l'Évangile de Jean (préparés hors app).
3. **Lecteur** : Media3, service, notification, reprise, vitesse, ±s, minuterie.
4. **Texte synchronisé** : surlignage, défilement, toucher pour sauter.
5. **Navigation et perso** : livres/chapitres, marque-pages, historique.
→ **MVP = Évangile de Jean complet.**
6. Notes, hors-ligne par livre, finitions.
7. Toute la Bible (73 livres).
