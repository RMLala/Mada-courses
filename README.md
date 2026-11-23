# MADA COURSES PRO

Application de gestion des courses, du budget et des stocks du foyer (Madagascar).

## Contenu

- `index.html` : application complète dans un seul fichier, fonctionne hors ligne.
- `manifest.json`, `sw.js`, `icon.svg` : fichiers PWA (installable en HTTPS).

## Données

Les données restent dans le navigateur de l'appareil. Faites une sauvegarde (.json) au moins tous les 2 jours depuis Réglages.

## Règle de stock

- Un achat validé dans une course fait entrer le stock.
- À chaque nouvelle course validée, les sorties sont calculées automatiquement à partir de la consommation observée sur les achats précédents du produit.
