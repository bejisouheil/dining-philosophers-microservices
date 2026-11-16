# Guide de démonstration

Le déroulé pas à pas pour la soutenance du 9 décembre 2026 à 11 h 15. Durée visée : 8 à 10 minutes de démonstration dans une présentation de 20 minutes.

## La veille

1. Vérifier que Docker Desktop démarre et affiche « Engine running ».
2. Construire les images une fois, pour ne pas attendre le jour même :

```powershell
docker compose build
```

3. Répéter le déroulé complet ci-dessous au moins une fois.
4. Prévoir le plan B : les scripts locaux fonctionnent sans Docker (voir la fin de ce guide).

## Le jour même, avant de passer

```powershell
docker compose up -d
```

Ouvrir <http://localhost:8090> dans le navigateur et laisser l'onglet prêt. Ouvrir un terminal PowerShell dans le dossier du projet.

## Déroulé

### 1. Philosophes gâtés (1 minute)

```powershell
$env:MODE = "spoiled"; docker compose up -d
```

**À montrer.** Les cinq ronds changent de couleur sans contrainte : aucun ne partage de fourchette. En bas, l'historique est trié par horloge.

**À dire.** « Chaque philosophe est un microservice indépendant. Il envoie trois événements au moniteur, datés par son horloge logique. Le moniteur les remet en ordre. »

Cliquer sur **Remettre à zéro** avant chaque changement de mode.

### 2. Superviseur naïf : l'interblocage (1 minute 30)

```powershell
$env:MODE = "supervised-naive"; $env:THINK_MIN_MS = "0"; $env:THINK_MAX_MS = "0"; docker compose up -d
```

Les temps de réflexion sont mis à zéro : les philosophes ont faim en permanence, ce qui provoque l'interblocage en moins d'une minute (répétition : environ 40 secondes). Avec les temps par défaut il peut mettre plusieurs minutes à survenir.

**À montrer.** Tous les ronds passent à l'orange et y restent. Le bandeau rouge « Interblocage suspecté » apparaît 8 secondes plus tard. Le nombre de repas ne bouge plus.

Preuve côté superviseur :

```powershell
Invoke-RestMethod http://localhost:8080/forks | ConvertTo-Json
```

Chaque fourchette `i` est détenue par le philosophe `i`.

**À dire.** « Chacun tient sa première fourchette et attend la seconde. Personne ne lâche. C'est l'interblocage, la faille n° 4 de notre jeu de tests. »

Si l'interblocage tarde, relancer les philosophes ensemble : `docker compose restart philosopher-0 philosopher-1 philosopher-2 philosopher-3 philosopher-4`. Pendant l'attente, expliquer les cinq failles du superviseur naïf.

### 3. Deux fourchettes sans verrou : la violation (1 minute)

```powershell
$env:MODE = "supervised-unsafe"; $env:RACE_WINDOW_MS = "40"; docker compose up -d
```

**À montrer.** Le bandeau rouge « Exclusion mutuelle violée » avec la fourchette et les deux philosophes concernés.

**À dire.** « Demander les deux fourchettes d'un coup supprime l'interblocage. Mais le superviseur traite les requêtes sur plusieurs threads : entre vérifier et réserver, un autre thread passe. Nous avons élargi cet instant à 40 millisecondes pour le rendre visible. »

### 4. Avec verrou (30 secondes)

```powershell
$env:MODE = "supervised-locked"; docker compose up -d
```

**À montrer.** Même fenêtre de 40 ms, plus aucune violation.

**À dire.** « La section critique est protégée par un verrou. Même fenêtre de course, zéro violation : c'est le verrou qui protège, pas la chance. Mais tout dépend encore d'un seul service. »

### 5. Protocole par jetons : plus de superviseur (2 minutes 30)

```powershell
$env:MODE = "token"; $env:RACE_WINDOW_MS = "0"; $env:THINK_MIN_MS = "500"; $env:THINK_MAX_MS = "1500"; docker compose up -d
```

Les temps de réflexion reviennent à leur valeur normale, pour que l'animation soit lisible.

**À montrer.**

- Tout le monde mange, zéro violation, repas répartis de façon presque égale dans le tableau.
- Arrêter le superviseur pour prouver qu'il ne sert plus :

```powershell
docker compose stop supervisor
```

- L'état interne d'un philosophe, avec ses jetons et la file des demandes :

```powershell
Invoke-RestMethod http://localhost:8083/token/state | ConvertTo-Json -Depth 5
```

- Tuer un philosophe en pleine activité, puis le relancer :

```powershell
docker compose kill philosopher-2
```

```powershell
docker compose start philosopher-2
```

Pendant la panne ses voisins attendent. Au retour il relit son état sur disque, tout repart, toujours zéro violation.

**À dire.** « Chaque fourchette a un jeton unique. Un philosophe qui a faim diffuse une demande datée. Celui qui détient un jeton sans manger le donne au demandeur le plus prioritaire, c'est-à-dire le plus ancien. Un philosophe qui attend cède à plus prioritaire que lui : c'est ce qui rend l'interblocage impossible. »

### 6. ZooKeeper (30 secondes)

```powershell
docker compose start supervisor; $env:MODE = "zookeeper"; docker compose up -d
```

**À montrer.** Même résultat, avec un outil du marché.

**À dire.** « Même garantie en 150 lignes au lieu de 1 000. ZooKeeper libère en plus les verrous d'un philosophe qui tombe. En échange nous dépendons d'un service de plus. Sur notre mesure : 57 repas par seconde pour nos jetons, 51 pour ZooKeeper, 87 pour le verrou central, qui est le plus rapide mais le moins équitable. »

### 7. Master-Worker sur un graphe (1 minute 30)

Le philosophe 0 joue le Master, les quatre autres les Workers.

```powershell
Invoke-RestMethod -Method Post http://localhost:8081/graph/master/degrees -ContentType "application/json" -Body '{"nodes":100000,"edgeCount":1000000,"seed":42}'
```

**À montrer.** `consistent: True`, `matchesSequential: True`, `sumOfDegrees: 2000000` (deux fois le nombre d'arêtes), et les deux durées.

Avec un Worker en panne :

```powershell
Invoke-RestMethod -Method Post "http://localhost:8083/graph/worker/chaos?failures=1"
```

Relancer le calcul : `retries` vaut 1, le résultat reste exact.

Accessibilité :

```powershell
Invoke-RestMethod -Method Post http://localhost:8081/graph/master/reachability -ContentType "application/json" -Body '{"nodes":50000,"edgeCount":150000,"seed":7,"source":0,"target":49999}'
```

**À dire.** « Le graphe est coupé par arêtes : chaque arête est dans une seule tranche, donc jamais comptée deux fois. Si un Worker tombe, sa tâche est relancée ailleurs. Et nous le disons franchement : pour ce calcul, distribuer est plus lent que calculer sur place, parce que le transport coûte plus cher que le calcul. »

### 8. DevOps (1 minute)

**À montrer**, sans rien lancer :

- la page GitHub Actions du dépôt, avec les trois étapes vertes : tests, images, déploiement Kubernetes ;
- Prometheus : <http://localhost:9090>, requête `rate(philosopher_meals_total[1m])` ;
- un journal JSON : `docker compose logs --tail 3 philosopher-0`.

**À dire.** « À chaque modification, la chaîne rejoue les 179 tests, construit les images, déploie sur un cluster Kubernetes jetable, supprime un pod et vérifie que le système continue sans violation. »

## Après la démonstration

```powershell
docker compose down -v
```

## Plan B, sans Docker

Si Docker ne démarre pas, les scripts locaux font la même démonstration pour les sections 1 à 3.3 et 5 (pas ZooKeeper, ni Kafka, ni Prometheus).

```powershell
.\scripts\start-local.ps1 -Mode supervised-naive -ThinkMaxMs 0
```

```powershell
.\scripts\start-local.ps1 -Mode supervised-unsafe -RaceWindowMs 40
```

```powershell
.\scripts\start-local.ps1 -Mode token
```

```powershell
.\scripts\stop-local.ps1
```

Le script arrête le système précédent avant de relancer. Les adresses sont les mêmes : tableau de bord sur le port 8090, philosophes sur 8081 à 8085.

## Plan C, sans rien lancer

Montrer les tests, qui rejouent tous ces scénarios automatiquement :

```powershell
.\mvnw.cmd verify
```

Puis ouvrir dans VS Code `SystemScenariosTest`, où chaque méthode correspond à une étape de la démonstration.

## Si quelque chose se passe mal

| Symptôme | Cause probable | Geste |
| --- | --- | --- |
| Le tableau de bord est vide | Les philosophes démarrent encore | Attendre 20 secondes. |
| Le mode affiché n'est pas le bon | Ancien historique | Cliquer sur **Remettre à zéro**. |
| Pas d'interblocage en mode naïf | Les philosophes n'ont pas démarré ensemble | `docker compose restart` des cinq philosophes. |
| Un port est déjà pris | Un ancien système tourne | `docker compose down`, ou `.\scripts\stop-local.ps1`. |
