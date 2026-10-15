# Vision

## Objectif

> **Handy remplace le LiDAR sur Android.**
> À partir de la seule caméra RGB d'un téléphone, Handy produit une profondeur
> métrique fiable en temps réel. Le suivi du corps et les applications créatives
> (VTubing, modélisation, animation) sont construits par-dessus.

La plupart des téléphones Android n'ont pas de LiDAR. Le point le plus important du
projet est de **créer la technologie qui compense ce manque**. Tout le reste en dépend.

## Les trois couches

```
DÉTECTION  (le cœur)           TRADUCTION                  APPLICATION
Substitut au LiDAR      →      Suivi du corps        →     VTubing, modélisation,
caméra RGB → profondeur        squelette en mètres         animation, export 3D
```

| Couche | Rôle | Sortie | Modules visés |
|---|---|---|---|
| **Détection** | Estimer la profondeur et la pose de la caméra sans capteur dédié | `DepthFrame` : profondeur, confiance, paramètres optiques, pose, horodatage | `camera`, `lidar`, `lidar-scene` |
| **Traduction** | Transformer images et profondeur en mouvement | Squelettes main/visage/corps en mètres, dans le repère de la scène | `tracking`, `mocap` |
| **Application** | Rendre ce mouvement utile | OSC, BVH, glTF/GLB, VRM, scan de la main, capture de pièce | `render`, `export`, `handscan`, `app` |

**Règle de dépendance :** une couche ne connaît que celle du dessous, et uniquement à
travers son API publique. La Détection ne sait rien des mains ni des squelettes.

## Public visé

- **Animateurs 3D et développeurs de jeux** : Blender, Unity, Unreal, via OSC, BVH et glTF.
- **VTubers et streamers** : visage, VRM et blend shapes, avec une faible latence.

## Critères de réussite de la Détection

« Remplacer le LiDAR » doit se mesurer. Les valeurs ci-dessous sont des **objectifs
de départ**, à réviser une fois le banc d'essai en place.

| Mesure | Objectif | Référence LiDAR téléphone (ordre de grandeur) |
|---|---|---|
| Erreur absolue de profondeur à 1 m | ≤ 2 cm | ~1 cm |
| Erreur absolue de profondeur à 3 m | ≤ 5 cm | ~2–3 cm |
| Erreur relative moyenne (AbsRel) | ≤ 0,05 | — |
| Cadence | ≥ 30 images/s (mi-gamme) | 60 Hz |
| Latence capture → `DepthFrame` | ≤ 50 ms | ~16 ms |
| Dérive de la pose caméra (ATE) | ≤ 2 cm sur 1 min | — |

## Banc d'essai

On ne peut pas améliorer ce qu'on ne mesure pas. Aucun appareil à capteur de
profondeur n'est disponible, le banc d'essai repose donc sur des **jeux de données
publics** :

| Jeu de données | Contenu | Licence | Usage |
|---|---|---|---|
| **TUM RGB-D** | Séquences RGB + profondeur Kinect + pose de référence | CC BY 4.0 | Principal : profondeur et dérive de pose |
| **ARKitScenes** | Séquences iPad avec LiDAR (capteur de téléphone/tablette) | CC BY-NC-SA 4.0 | Comparaison au LiDAR mobile, en interne uniquement |
| **NYU Depth v2** | Images RGB-D en intérieur | Usage recherche | Évaluation du canal neuronal image par image |

Le banc rejoue une séquence enregistrée à travers le moteur, sans caméra. Il mesure
chaque canal seul, puis la fusion, et produit un rapport. Toute modification de la
Détection doit faire progresser ces chiffres, ou au moins ne pas les dégrader.

## Feuille de route

| # | Étape | Couche |
|---|---|---|
| 0 | Banc d'essai sur jeux publics et premier rapport de précision | Détection |
| 1 | API publique `LidarSession` → `DepthFrame`, l'app branchée dessus sans changement de comportement | Détection |
| 2 | Interface `DepthChannel` unique pour tous les canaux, tri des canaux d'après les mesures | Détection |
| 3 | Reconstruction de la main sortie de `depth`, nouveau module `handscan` | Application |
| 4 | Services de scène (SLAM, plans, TSDF, maillage) séparés en `lidar-scene`, `depth` renommé `lidar` | Détection |
| 5 | Squelette ancré dans le `DepthFrame` (positions en mètres, occlusions) | Traduction |
| 6 | Export de la pièce (PLY/GLB) et caméra virtuelle en OSC | Application |

## Points ouverts

- **Modèle Depth Anything v2.** Le canal neuronal est sans doute le meilleur
  substitut au LiDAR sans ARCore, mais son fichier de poids est absent du dépôt. La
  variante *Small* est sous licence Apache-2.0 et peut être convertie au format ONNX
  Runtime (`.ort`). Il faut l'intégrer avant l'étape 0 pour mesurer ce canal.
- **Canaux à fort coût pour l'utilisateur.** La photométrie fait clignoter la lampe,
  la lumière structurée fait flasher l'écran. Leur sort dépendra des mesures de l'étape 2.
