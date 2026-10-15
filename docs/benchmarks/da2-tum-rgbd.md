# Banc d'essai — canal Depth Anything v2 sur TUM RGB-D

Première mesure de l'étape 0 ([VISION.md](../VISION.md)). Résultats bruts :
[`da2-tum-rgbd.json`](da2-tum-rgbd.json). Script : [`tools/benchmark/da2_tum.py`](../../tools/benchmark/da2_tum.py).

## Protocole

- **Données** : TUM RGB-D `freiburg1_desk` (bureau, surtout 0,5–2 m) et `freiburg1_room`
  (pièce entière, jusqu'à ~4 m). 60 images réparties sur chaque séquence. Vérité terrain :
  profondeur Kinect alignée sur l'image RGB, pixels valides entre 0,1 et 10 m.
- **Modèle** : Depth Anything V2 Small, export ONNX `onnx-community`, trois variantes
  (fp32 99 Mo, fp16 50 Mo, int8 quantifié 27 Mo). Prétraitement identique à l'app
  (redimensionnement, normalisation ImageNet, CHW).
- **Calibration**, comme dans l'app (affine sur l'inverse de la profondeur) :
  - *oracle* : ajustée sur tous les pixels — le plafond du canal ;
  - *épars* : ajustée sur 50 pixels tirés au hasard — proche de ce que fournissent ARCore/SfM.
- **Mesures** : AbsRel, δ1 (< 1,25), erreur absolue moyenne dans les bandes 1 m ± 25 cm et
  3 m ± 25 cm, temps d'inférence médian.

## Résultats (variante int8, calibration éparse)

| Entrée | desk AbsRel | room AbsRel | Erreur à 1 m | Erreur à 3 m (room) |
|---|---|---|---|---|
| 518×518 écrasé (ancien réglage) | 0,051 | 0,067 | 4,5–7,4 cm | 43 cm |
| 518×392 | 0,050 | 0,067 | 4,7–7,4 cm | 43 cm |
| **364×280 (retenu)** | **0,055** | **0,073** | **5,3–8,1 cm** | **46 cm** |
| 266×196 | 0,070 | 0,086 | 6,4–10,2 cm | 52 cm |
| 182×140 | 0,144 | 0,170 | 16–22 cm | 107 cm |

- **Variantes** : fp32, fp16 et int8 donnent la même précision (écarts ≤ 0,002 AbsRel jusqu'à
  266×196 ; seule exception, 182×140, où les trois sont de toute façon inutilisables).
  L'int8 est retenu pour la taille de l'APK.
- **Calibration éparse** : 50 points suffisent presque à atteindre l'oracle (+0,003 à +0,005 AbsRel).
- **Orientation** : sur un recadrage portrait droit, une entrée 280×364 donne AbsRel 0,044 / 0,064
  contre 0,050 / 0,071 pour la même image écrasée en 364×280. L'app oriente donc l'entrée selon
  l'image.
- **Temps** : mesurés sur le processeur x86 du serveur de build (4 threads), **pas représentatifs
  d'un téléphone**. Seul l'ordre relatif compte : en fp32, 364×280 est ~2× plus rapide que 518×392
  (132–135 ms contre 273–282 ms).
  Les temps int8 sur x86 sont irréguliers (157 à 301 ms en 364×280) : sur ARM, à mesurer.

## Écart avec les objectifs

| Objectif (VISION.md) | Mesuré (364×280) | Atteint |
|---|---|---|
| ≤ 2 cm à 1 m | 5–8 cm | ❌ |
| ≤ 5 cm à 3 m | ~46 cm | ❌ |
| AbsRel ≤ 0,05 | 0,055–0,073 | ❌ (proche sur desk) |

**Conclusion** : seul, le canal neuronal reste loin d'un LiDAR, même avec une calibration
idéale. Une seule transformation affine par image ne corrige pas les erreurs qui varient dans
l'image ; la précision viendra de la **fusion** avec des mesures métriques (ARCore, SfM,
stéréo) et d'une calibration locale. La Kinect elle-même a une erreur de quelques centimètres
à 3 m, qui pèse sur la bande 3 m.

## Limites

- Kinect ≠ caméra de téléphone (optique, bruit, exposition).
- Pas encore de mesure de la fusion ni des autres canaux : ils dépendent de classes Android et
  ne tournent pas hors de l'appareil (voir étape 1 de la feuille de route).
