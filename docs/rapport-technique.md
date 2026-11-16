# Rapport technique

**Le dîner des philosophes en microservices : du comportement naïf à l'exclusion mutuelle et à l'interblocage**

| | |
| --- | --- |
| Cours | Conception Système, CY Tech, ING2 |
| Équipe | Souheil BEJI, Mohamed OUHAB, Taimim JELOUALI |
| Dépôt | <https://github.com/bejisouheil/dining-philosophers-microservices> |
| Soutenance | 9 décembre 2026, 11 h 15 |

## Sommaire

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture retenue](#2-architecture-retenue)
3. [Protocoles conçus](#3-protocoles-conçus)
4. [Tests et résultats](#4-tests-et-résultats)
5. [Architecture Master-Worker](#5-architecture-master-worker)
6. [Approche DevOps](#6-approche-devops)
7. [Difficultés rencontrées et solutions apportées](#7-difficultés-rencontrées-et-solutions-apportées)
8. [Limites et pistes d'amélioration](#8-limites-et-pistes-damélioration)
9. [Reproductibilité des résultats](#9-reproductibilité-des-résultats)

Documents associés : [journal des décisions](decisions.md), [répartition du travail](repartition-travail.md), [guide de démonstration](guide-demonstration.md).

---

## 1. Vue d'ensemble

### 1.1 Le problème, sans technique

Cinq philosophes sont assis autour d'une table ronde. Entre deux voisins se trouve une fourchette. Un philosophe pense, puis a faim ; pour manger il lui faut les deux fourchettes qui l'entourent ; quand il a fini il les repose et retourne penser.

Ce petit scénario résume les trois difficultés de tout système où plusieurs programmes se partagent des ressources :

- **l'exclusion mutuelle** : une fourchette ne doit jamais servir à deux philosophes en même temps ;
- **l'interblocage** : si chacun prend la fourchette de gauche et attend celle de droite, plus personne ne mange, pour toujours ;
- **la famine** : même sans blocage général, un philosophe malchanceux peut ne jamais être servi.

Dans notre projet chaque philosophe est un programme indépendant (un microservice). Les philosophes ne partagent aucune mémoire : ils ne peuvent que s'envoyer des messages par le réseau. C'est la situation réelle des applications réparties, où les mêmes problèmes se posent pour une place de concert, un solde bancaire ou un fichier.

### 1.2 Ce qui a été réalisé

Le sujet demande une progression. Nous l'avons suivie étape par étape, et chaque étape reste exécutable : le même service philosophe sait jouer les six variantes, on choisit par une option.

| Section du sujet | Variante (`PHILOSOPHER_MODE`) | Résultat observable |
| --- | --- | --- |
| 1. Philosophes gâtés | `spoiled` | Tout le monde mange, le moniteur reconstruit l'historique par horloges logiques. |
| 2. Superviseur naïf | `supervised-naive` | Cinq failles recensées par des tests, dont l'interblocage. |
| 3.1 Deux fourchettes, sans verrou | `supervised-unsafe` | Une fourchette est attribuée à deux voisins. |
| 3.2 Deux fourchettes, avec verrou | `supervised-locked` | Plus aucune violation. |
| 3.3 Protocole par jetons | `token` | Ni superviseur, ni violation, ni interblocage ; repas équitables. |
| 3.4 Outil de coordination | `zookeeper` | Même garantie avec ZooKeeper ; comparaison chiffrée. |
| 4. Tests | | 179 tests automatisés, dont concurrence et pannes. |
| 5. Master-Worker | API `/graph` | Degrés et accessibilité d'un graphe, tolérants aux pannes de Workers. |
| 6. DevOps | | Docker, Kafka, Kubernetes, CI/CD, métriques, journaux structurés. |

### 1.3 Chiffres clés

| | |
| --- | --- |
| Tests automatisés | 179 (tous verts dans la CI) |
| Code de production | environ 3 700 lignes de Java |
| Code de test | environ 3 600 lignes de Java |
| Débit du protocole par jetons | 57 repas par seconde, parfaitement répartis (172 à 173 repas par philosophe sur la mesure) |
| Chaîne CI/CD | tests, 3 images Docker, déploiement Kubernetes et test de panne en 5 minutes environ |

---

## 2. Architecture retenue

### 2.1 Les services

```text
                        événements HUNGRY / EATING / DONE
   philosopher-0 ──┐        (HTTP, ou Kafka en section 6)        ┌──────────────────┐
   philosopher-1 ──┼──────────────────────────────────────────►  │ monitor-service  │ ──► tableau de bord
   philosopher-2 ──┤                                             └──────────────────┘
   philosopher-3 ──┤
   philosopher-4 ──┘
        │  ▲
        │  │ demandes horodatées et jetons (mode token, HTTP entre philosophes)
        ▼  │
   ┌─────────────────────┐          ┌───────────────┐
   │ supervisor-service  │          │  ZooKeeper    │
   └─────────────────────┘          └───────────────┘
```

| Module Maven | Rôle |
| --- | --- |
| `philosopher-service` | Un philosophe par instance. Contient les six stratégies, le protocole par jetons, et les rôles Master et Worker de la section 5. |
| `monitor-service` | Reçoit les événements, les remet en ordre, détecte violations, famine et interblocage, sert le tableau de bord web. |
| `supervisor-service` | Gère l'état des fourchettes pour les sections 2 et 3.1-3.2 : trois variantes côte à côte. |
| `common` | Code partagé : horloge de Lamport, format d'événement, client HTTP avec délais d'attente. |
| `system-tests` | Tests de bout en bout : lance de vrais services dans la JVM du test. |

Chaque service est une application Spring Boot 4 (Java 21) autonome, avec son propre port et son propre processus, comme l'exige le sujet.

### 2.2 Une boucle, six stratégies

La boucle d'un philosophe est toujours la même : penser, avoir faim, obtenir le droit de manger, manger, le rendre. Seule la manière d'obtenir ce droit change d'une section à l'autre. Nous avons donc isolé ce point dans une interface, `DiningStrategy`, avec trois méthodes : `forks()`, `acquire()` et `release()`.

| Stratégie | Section | Comment elle obtient les fourchettes |
| --- | --- | --- |
| `SpoiledStrategy` | 1 | Ne fait rien : aucune ressource partagée. |
| `NaiveSupervisedStrategy` | 2 | Demande au superviseur une fourchette, puis l'autre. |
| `PairSupervisedStrategy` | 3.1, 3.2 | Demande les deux en une requête. |
| `TokenStrategy` | 3.3 | Protocole distribué par jetons. |
| `ZooKeeperStrategy` | 3.4 | Deux verrous distribués ZooKeeper. |

Avantage : la boucle (`PhilosopherLoop`), l'envoi des événements, les métriques et l'API REST sont écrits et testés une seule fois, et les variantes se comparent à conditions égales.

### 2.3 Les horloges logiques de Lamport

Les machines n'ont pas la même heure. Pour ordonner des événements produits par des services différents, chaque philosophe tient un compteur, son horloge de Lamport (`LamportClock`), avec deux règles :

1. avant chaque événement local ou envoi de message, l'horloge avance de 1 ;
2. à la réception d'un message daté `t`, l'horloge passe à `max(horloge, t) + 1`.

Conséquence : si un événement A a causé un événement B, alors A est daté avant B. Chaque événement `HUNGRY`, `EATING`, `DONE` envoyé au moniteur porte la date de son émetteur ; le moniteur trie par date, puis par identifiant de philosophe en cas d'égalité, ce qui donne un ordre total compatible avec la causalité.

Une horloge n'a de sens que si elle voyage avec les messages. Nous l'avons donc propagée partout où une fourchette change de main :

| Variante | Comment l'horloge circule |
| --- | --- |
| Superviseur (2, 3.1, 3.2) | En-tête HTTP `X-Lamport-Time` dans chaque requête et chaque réponse ; le superviseur a sa propre horloge. |
| Jetons (3.3) | Dans chaque message du protocole (demande, libération, jeton). |
| ZooKeeper (3.4) | Les philosophes ne se parlent plus : en rendant une fourchette, le philosophe écrit sa date dans un nœud ZooKeeper ; le suivant la lit en la prenant. |
| Redémarrage d'un philosophe | Il se recale sur la plus grande date connue du moniteur (`GET /api/clock`), puis émet un événement `STARTED`. |

Détail qui compte : l'événement `DONE` est daté **avant** de rendre les fourchettes. Le philosophe suivant reçoit donc une horloge postérieure à ce `DONE`, et son `EATING` est forcément daté après.

### 2.4 Le moniteur et son tableau de bord

Le moniteur garde les événements en mémoire (20 000 au plus), sans doublon, et rejoue l'historique trié pour calculer :

- l'état de chaque philosophe, son nombre de repas, sa plus longue attente ;
- les **violations d'exclusion mutuelle** : un `EATING` sur une fourchette dont le précédent utilisateur n'a pas encore émis `DONE` ;
- la **famine** : un philosophe affamé depuis plus de 8 secondes ;
- l'**interblocage suspecté** : tous les philosophes affamés depuis plus de 8 secondes.

Le tableau de bord (page web servie par le moniteur, rafraîchie chaque seconde) montre la table ronde, l'état de chacun, les alertes et l'historique reconstruit.

**Limite assumée de cette détection.** Elle repose sur les horloges logiques, pas sur l'heure réelle. Elle ne donne pas de fausse alerte tant que les horloges sont propagées, mais elle peut manquer une violation : deux repas réellement simultanés peuvent recevoir des dates qui les font paraître successifs. C'est un outil d'observation, pas une preuve. Les tests de concurrence, eux, mesurent les violations directement avec un compteur par fourchette.

### 2.5 Choix techniques transverses

- **Aucun appel réseau sans délai d'attente** (`HttpSupport`) : un service lent ne doit jamais en bloquer un autre indéfiniment.
- **L'observation ne doit jamais gêner le protocole** : les événements partent par une file traitée en arrière-plan ; si le moniteur est en panne, on perd de l'observation, pas de la correction. Resilience4j ajoute nouvelles tentatives et disjoncteur.
- **Configuration par variables d'environnement** : la même image Docker sert en local, sous Docker Compose et sous Kubernetes.

---

## 3. Protocoles conçus

### 3.1 Superviseur naïf et ses failles (section 2)

Le philosophe interroge le superviseur (`GET /naive/forks/{n}/available`), demande la fourchette (`POST .../acquire`), puis recommence pour la seconde. Le superviseur (`NaiveForkTable`) se contente d'attribuer une fourchette libre et d'accepter sa libération, sans contrôle global, comme le demande le sujet.

Le jeu de tests `NaiveForkTableTest` recense cinq failles. Chaque test réussit quand la faille se produit : il documente le comportement fautif.

| # | Faille | Cause | Test |
| --- | --- | --- | --- |
| 1 | Deux philosophes obtiennent la même fourchette. | « Vérifier puis prendre » n'est pas atomique : deux threads passent le test avant que l'un des deux n'écrive. | `doubleAttribution` |
| 2 | La réponse « disponible » est périmée quand la demande arrive. | Deux requêtes HTTP séparées, l'état peut changer entre les deux. | `disponibilitePerimee` |
| 3 | Un philosophe libère la fourchette d'un autre. | Aucune vérification du détenteur. | `liberationParUnTiers` |
| 4 | **Interblocage** : chacun tient une fourchette et attend la suivante. | « Tenir et attendre », sans vision d'ensemble. | `interblocage` |
| 5 | Un philosophe accapare toutes les fourchettes (famine des autres). | Aucune règle sur qui a droit à quoi. | `accaparement` |

Pour la faille 1, le test rend la course déterministe : une barrière fait attendre les deux threads juste après le test « libre ? », ce qui garantit qu'ils l'ont passé tous les deux avant d'écrire. La table du superviseur est une `ConcurrentHashMap` : chaque opération isolée est sûre. La faille vient bien de l'enchaînement de deux opérations, pas de la structure de données.

La faille 4 est aussi vérifiée de bout en bout : avec cinq vrais services lancés en même temps, le système atteint l'état où chaque philosophe `i` détient la fourchette `i`, puis plus aucun repas n'a lieu (`SystemScenariosTest.superviseurNaifInterblocage`).

### 3.2 Section critique et verrou (sections 3.1 et 3.2)

**Sans verrou (`UnsafePairForkTable`).** Le philosophe demande ses deux fourchettes en une seule requête ; le superviseur répond « les deux » ou « aucune ». Cela supprime l'interblocage, puisque plus personne ne garde une fourchette en attendant l'autre. Mais le serveur web traite les requêtes sur plusieurs threads : entre le test « les deux sont-elles libres ? » et la réservation, un autre thread peut passer le même test. Le test `sansVerrouDeuxVoisinsObtiennentLaMemeFourchette` le montre : les philosophes 0 et 1 obtiennent tous deux la fourchette 1.

**Avec verrou (`LockedPairForkTable`).** Le bloc « vérifier puis réserver » est une section critique, protégée par un `ReentrantLock`. Un seul thread à la fois peut lire et modifier l'état des fourchettes. Nous avons gardé au même endroit la pause artificielle qui élargit la fenêtre de course : sous la même charge, la version sans verrou viole l'exclusion mutuelle et la version avec verrou jamais. C'est donc bien le verrou, et non la chance, qui protège. La libération vérifie en plus que l'appelant est le détenteur, ce qui corrige la faille 3.

**Ce que le verrou ne règle pas.** Le superviseur reste un point unique de défaillance et un goulot d'étranglement ; et un philosophe refusé doit réessayer plus tard, sans garantie d'être servi dans l'ordre : nos mesures montrent des écarts de repas entre philosophes et des attentes de plus d'une seconde (voir 3.5). D'où l'étape suivante.

### 3.3 Protocole distribué par jetons (section 3.3)

Plus de superviseur : N microservices philosophes qui ne communiquent que par messages HTTP. Le cœur du protocole est la classe `TokenNode`, volontairement indépendante de Spring et du réseau pour être testable seule.

#### Hypothèses

- Les messages peuvent être retardés, désordonnés, perdus ou dupliqués, mais un message renvoyé assez de fois finit par arriver.
- Un philosophe peut s'arrêter et redémarrer ; il retrouve alors son état sur disque. Un philosophe qui ne redémarre jamais bloque la progression (voir les limites).
- Pas de comportement malveillant : chaque philosophe applique le protocole.

#### Règle d'attribution des fourchettes

Le philosophe `i` gère la fourchette `i`. Quand il a faim, la règle automatique lui attribue la fourchette `i` et la fourchette `(i + 1) mod N` (`ForkAssignment`). Chaque fourchette est ainsi partagée par exactement deux philosophes. Le protocole lui-même ne dépend pas de cette règle : une demande porte la liste des deux fourchettes voulues, quelconques, et un test le vérifie avec des paires tirées au hasard.

#### Les jetons

Chaque fourchette a **un jeton unique**, qui représente le droit exclusif de l'utiliser. Au départ le philosophe `i` détient le jeton `i`. Un philosophe ne mange que s'il détient les deux jetons de ses fourchettes.

#### Les trois messages

| Message | Contenu | Quand |
| --- | --- | --- |
| `Request` | demandeur, numéro de demande, date de Lamport, les deux fourchettes | Diffusé à tous les autres quand on a faim ; rediffusé périodiquement tant qu'on attend. |
| `Release` | demandeur, numéro de la demande terminée | Diffusé à tous à la fin du repas. |
| `Token` | fourchette, numéro de version, émetteur | Envoyé à un seul philosophe : transfert du jeton. |

Chaque message porte aussi l'horloge de l'émetteur et le numéro de sa dernière demande terminée.

#### Les règles

1. **Demander.** Le philosophe avance son horloge, crée une demande datée, la mémorise, la diffuse.
2. **Mémoriser.** Chaque philosophe garde les demandes reçues dans une file triée par **priorité** : la plus petite date d'abord ; à date égale, le plus petit identifiant. Deux demandes n'ont jamais la même paire (date, identifiant), donc tous les philosophes classent les demandes exactement de la même façon.
3. **Transférer.** À chaque changement (demande reçue, jeton reçu, fin de repas), pour chaque jeton qu'il détient, un philosophe qui n'est pas en train de manger avec cette fourchette cherche la demande la plus prioritaire qui la concerne. Si cette demande est celle d'un autre, il lui envoie le jeton. Sinon il le garde.
4. **Manger.** Dès qu'il détient ses deux jetons, le philosophe mange.
5. **Libérer.** À la fin du repas il retire sa demande, diffuse `Release`, et réapplique la règle 3 : ses jetons partent vers ceux qui attendent.

La règle 3 a une conséquence essentielle : un philosophe qui attend **cède** un jeton qu'il détient à plus prioritaire que lui. Personne ne garde une ressource face à quelqu'un qui passe avant.

#### Exemple

Trois philosophes ont faim en même temps, avec les dates 4 (P0), 4 (P1) et 6 (P2). Priorités : P0 (date 4, identifiant 0), puis P1 (date 4, identifiant 1), puis P2.

- P0 veut les fourchettes 0 et 1. Il détient le jeton 0. Le jeton 1 est chez P1, qui attend lui aussi mais est moins prioritaire : P1 cède le jeton 1 à P0. P0 mange.
- P1 veut 1 et 2. Il vient de donner le jeton 1. P2 détient le jeton 2 et est moins prioritaire que P1 : il le lui cède. P1 attend le jeton 1.
- P0 finit, diffuse `Release`. Le jeton 1 part vers P1, désormais le plus prioritaire pour cette fourchette. P1 mange.
- Quand P0 aura de nouveau faim, sa nouvelle demande sera datée après toutes celles qu'il a vues : il passera derrière P1 et P2.

#### Pourquoi le protocole est correct

Ce sont des arguments, pas des preuves formelles (voir 4.4).

**Exclusion mutuelle.** Il n'existe qu'un jeton par fourchette : le philosophe le retire de ses jetons avant de l'envoyer, et le destinataire ne l'ajoute qu'une fois. Comme il faut détenir le jeton pour utiliser la fourchette, deux philosophes ne peuvent pas l'utiliser en même temps.

**Pas d'interblocage.** Prenons, parmi toutes les demandes en attente, la plus prioritaire. Chacune de ses deux fourchettes est soit chez un philosophe qui mange (il finira et la donnera), soit chez un philosophe qui ne mange pas (il la donne tout de suite, puisque personne n'est plus prioritaire), soit en transit (elle arrivera). Et personne ne peut lui reprendre un jeton obtenu, puisqu'il faudrait une demande plus prioritaire. Cette demande finit donc par être servie : le système ne peut pas rester bloqué. Un cycle d'attente est impossible parce que la priorité est un ordre total, identique chez tous.

**Pas de famine, équité.** Recevoir une demande datée `t` fait passer l'horloge du receveur au-delà de `t`. Toutes ses demandes futures seront donc datées après. Une demande donnée ne peut être doublée que par un nombre fini de demandes : celles émises avant que la sienne ne soit connue. Elle finit par devenir la plus prioritaire, donc par être servie.

#### Demandes simultanées, pertes, doublons, retards

| Situation | Mécanisme |
| --- | --- |
| Deux demandes au même instant | Départagées par (date, identifiant) : même décision partout. |
| Demande perdue | Le demandeur la rediffuse à intervalle régulier tant qu'il attend (`retransmit`), avec la même date : sa priorité ne change pas. |
| Demande reçue deux fois | La file est un ensemble : sans effet. |
| Libération perdue | Trois filets : la libération est retentée ; une nouvelle demande du même philosophe remplace l'ancienne ; chaque message annonce la dernière demande terminée de son émetteur. |
| Demande terminée qui arrive en retard | Ignorée grâce au numéro de la dernière demande terminée. |
| Jeton reçu deux fois | Chaque transfert augmente le **numéro de version** du jeton ; une version déjà vue est ignorée. Un doublon ne crée donc pas de second jeton. |
| Jeton dont l'envoi échoue | Retenté sans limite, avec une attente qui double (50 ms à 1 s), jusqu'à l'accusé de réception. Entre-temps le jeton n'est utilisable par personne. |

Le transport (`HttpPeerTransport`) utilise une file et un thread par destinataire : les messages vers un même philosophe partent dans l'ordre, et un destinataire lent ne bloque pas les autres. Le destinataire traite le message avant de répondre : la réponse HTTP 200 vaut accusé de réception.

#### Pannes : la mémoire stable

En testant le redémarrage d'un philosophe, nous avons trouvé un défaut de la première version : un philosophe redémarré croyait détenir de nouveau le jeton de sa fourchette, alors qu'il l'avait donné. Deux jetons pour une fourchette, l'exclusion mutuelle était rompue.

Correction : chaque philosophe écrit son état sur disque (`FileTokenStateStore`) **avant** que le moindre message ne parte : jetons détenus, versions, jetons envoyés mais pas encore acquittés, numéro de demande, horloge. L'écriture passe par un fichier temporaire renommé, pour qu'une panne en pleine écriture laisse l'ancien état intact. Au redémarrage, `recover()` :

1. renvoie les jetons dont l'envoi n'avait pas été acquitté (avec la même version : si le destinataire l'avait déjà reçu, il écarte le doublon) ;
2. annonce l'abandon de la demande interrompue, pour que les autres n'attendent pas derrière elle.

Le destinataire d'un jeton l'écrit sur disque avant de répondre. Dans tous les cas de panne, d'un côté ou de l'autre, le jeton existe donc en un seul exemplaire.

#### Coût en messages

Un repas coûte `N - 1` demandes, `N - 1` libérations et quelques transferts de jeton. Le test `coutEnMessages` vérifie, pour N = 5, que le total reste entre 8 et 14 messages par repas. Le coût croît donc linéairement avec le nombre de philosophes : c'est le prix de la diffusion imposée par le sujet.

### 3.4 Coordination distribuée avec ZooKeeper (section 3.4)

`ZooKeeperStrategy` utilise Apache Curator. Chaque fourchette est un verrou distribué (`InterProcessMutex`, nœud `/dining/forks/N`). Le philosophe prend ses deux verrous **toujours dans l'ordre croissant des numéros** : avec un ordre global sur les ressources, un cycle d'attente est impossible, donc pas d'interblocage. ZooKeeper sert les demandeurs d'un verrou dans leur ordre d'arrivée (nœuds séquentiels) et libère automatiquement les verrous d'un client dont la session expire (nœuds éphémères).

### 3.5 Comparaison des solutions

**Mesure.** Même charge pour tous : 5 philosophes toujours affamés, repas de 20 ms, 15 secondes, vrais services HTTP sur la même machine (Intel Core i5-1155G7, 4 cœurs, 16 Go). Au plus deux philosophes peuvent manger en même temps, donc le maximum théorique est de 100 repas par seconde.

| Solution | Repas par seconde | Attente moyenne | Attente maximale | Repas par philosophe |
| --- | --- | --- | --- | --- |
| Superviseur avec verrou | 87,0 | 34 ms | 1 547 ms | 255, 258, 277, 223, 292 |
| Jetons (notre protocole) | 57,4 | 60 ms | 709 ms | 172, 172, 172, 173, 172 |
| ZooKeeper (Curator) | 51,4 | 79 ms | 389 ms | 108, 151, 175, 229, 108 |

Mesure reproductible avec `ProtocolComparisonBenchmark` (commande dans le README). Une seule exécution par solution : les ordres de grandeur sont fiables, pas la deuxième décimale.

**Lecture.**

- Le **superviseur avec verrou** est le plus rapide : une requête suffit, et dès que deux fourchettes sont libres quelqu'un les prend. Mais il n'ordonne pas les demandeurs : les écarts entre philosophes sont nets (223 à 292 repas) et un philosophe a attendu 1,5 seconde. Et tout repose sur un seul service.
- **Notre protocole** est environ un tiers moins rapide, pour deux raisons : plus de messages par repas (8 à 14 contre 2), et un ordre strict qui fait parfois attendre un philosophe alors que ses fourchettes sont libres mais promises à plus prioritaire. En échange, la répartition est presque parfaite (172 ou 173 repas chacun) et il n'y a aucun service central.
- **ZooKeeper** est un peu plus lent que nos jetons dans cette mesure, et moins équitable : prendre les verrous dans l'ordre des numéros favorise certains philosophes selon leur place autour de la table (108 à 229 repas). Son attente maximale est la plus faible.

**Comparaison qualitative.**

| Critère | Superviseur avec verrou | Jetons (3.3) | ZooKeeper (3.4) |
| --- | --- | --- | --- |
| Composant central | Oui, point unique de défaillance | Aucun | ZooKeeper, mais conçu pour être répliqué |
| Interblocage évité par | Tout ou rien | Priorité totale et cession des jetons | Ordre global de prise des verrous |
| Équité | Non garantie | Ordre des dates de Lamport | Par verrou seulement |
| Panne d'un philosophe | Ses fourchettes restent prises | Bloque ses voisins jusqu'à son redémarrage, puis reprise exacte | Verrous libérés automatiquement à l'expiration de la session |
| Messages par repas | 2 | 2(N-1) plus les jetons | Une dizaine d'opérations ZooKeeper |
| Effort de réalisation | Faible | Élevé (environ 400 lignes pour le cœur, 1 000 avec le transport et la mémoire stable) | Très faible (environ 150 lignes) |
| Ce qu'il faut exploiter | Un service | Rien de plus | Un cluster ZooKeeper |

**Conclusion.** Écrire soi-même un protocole d'exclusion mutuelle distribuée est possible, et instructif : chaque garantie (doublons, pertes, pannes) a demandé un mécanisme précis, découvert par un test. En production, un outil éprouvé comme ZooKeeper apporte surtout ce que nous n'avons pas : la détection des pannes et la libération automatique. Notre protocole garde l'avantage quand on ne veut dépendre d'aucun service tiers et que l'équité compte.

---

## 4. Tests et résultats

### 4.1 Stratégie

Quatre niveaux, du plus rapide au plus réaliste :

1. **Tests unitaires** (JUnit 5, Mockito, AssertJ) : une classe à la fois, ses voisins remplacés par des doublures.
2. **Tests d'intégration** : un service Spring Boot démarré sur un port libre, appelé en HTTP (`@SpringBootTest`), ou plusieurs philosophes reliés par un faux réseau.
3. **Tests de concurrence et de résilience** : plusieurs threads, pannes et défauts de réseau provoqués.
4. **Tests de bout en bout** (`system-tests`) : moniteur, superviseur et philosophes réels, chacun sur son port, qui ne se parlent qu'en HTTP ; ZooKeeper et Kafka réels dans des conteneurs (Testcontainers).

Deux principes nous ont guidés :

- **Rendre les courses déterministes.** Un bug de concurrence qui n'apparaît qu'une fois sur mille ne fait pas un test. Le superviseur accepte une « fenêtre de course » injectable : en test, une barrière y fait attendre deux threads, ce qui provoque la situation à chaque exécution.
- **Séparer la logique du réseau.** Le protocole par jetons et le Master ne connaissent qu'une interface de transport. On peut donc les tester avec Mockito, avec un faux réseau qui perd des messages, ou avec le vrai HTTP.

### 4.2 Inventaire

| Module | Tests | Principales classes de test |
| --- | --- | --- |
| `common` | 4 | `LamportClockTest` |
| `philosopher-service` | 112 | `TokenNodeTest` (29), `TokenProtocolSimulationTest` (10), `TokenRecoveryTest` (9), `GraphMasterTest` (18), `GraphsTest` (8), `ZooKeeperStrategyTest` (6), `PhilosopherLoopTest` (7), stratégies supervisées (10) |
| `supervisor-service` | 30 | `NaiveForkTableTest` (9), `PairForkTableTest` (16), tests de l'API (5) |
| `monitor-service` | 18 | `SnapshotServiceTest` (9), `EventStoreTest` (5), tests de l'API (4) |
| `system-tests` | 15 | `SystemScenariosTest` (5), `GraphSystemTest` (5), `ResilienceSystemTest` (2), `KafkaSystemTest` (2), `ZooKeeperSystemTest` (1) |
| **Total** | **179** | |

### 4.3 Ce que chaque famille de tests vérifie

**Tests unitaires du philosophe (demande du sujet, point 1).** `TokenNodeTest` isole un philosophe et remplace le réseau par un transport Mockito :

- *demandes horodatées* : diffusion à tous, date de Lamport, tri par priorité, départage par identifiant, recalage de l'horloge, rediffusion à date inchangée ;
- *gestion des jetons* : don à un demandeur, conservation face à une demande plus récente, cession à une demande plus ancienne, renvoi d'un jeton reçu sans besoin, version qui augmente ;
- *attribution et libération des fourchettes* : pas de repas avec un seul jeton, repas dès le second, aucune fourchette lâchée pendant le repas, diffusion de la libération, abandon d'une demande ;
- *traitement des erreurs* : double demande, fourchettes invalides, libération sans demande, messages en double, en retard ou périmés.

**Tests d'intégration (point 2).** `TokenProtocolSimulationTest` relie cinq philosophes, chacun dans son thread, par un faux réseau (`InMemoryNetwork`). `SystemScenariosTest.protocoleParJetons` refait la même chose avec cinq vrais microservices en HTTP, puis vérifie qu'il reste **exactement un jeton par fourchette**.

**Tests de concurrence (point 3).** Un compteur par fourchette est incrémenté au début de chaque repas : s'il dépasse 1, deux philosophes utilisent la même fourchette.

| Scénario | Résultat |
| --- | --- |
| Tous les philosophes demandent au même instant (barrière), 20 tours | 0 violation, tout le monde mange |
| 5 philosophes, 200 repas chacun | 0 violation, 5 jetons à la fin |
| 2 philosophes sur les 2 mêmes fourchettes, 100 repas | 0 violation |
| 6 philosophes, paires de fourchettes tirées au hasard | 0 violation, aucun blocage |
| Superviseur sans verrou, 8 threads, 2 s | violations observées (attendu) |
| Superviseur avec verrou, 16 threads, 2 s | 0 violation |

**Tests de résilience (point 4).**

| Défaillance simulée | Où | Comportement vérifié |
| --- | --- | --- |
| Délais de réponse aléatoires jusqu'à 10 ms, messages désordonnés | faux réseau | Tous les repas ont lieu, 0 violation. |
| 20 % des demandes et libérations perdues | faux réseau | Rattrapé par retransmission, 5 jetons à la fin. |
| 30 % des messages livrés en double, jetons compris | faux réseau | Aucun jeton créé. |
| Pertes, doublons et délais ensemble | faux réseau | Idem. |
| Redémarrage d'un philosophe (9 cas) | `TokenRecoveryTest` | Pas de jeton recréé, jeton en vol renvoyé, demande interrompue abandonnée. |
| Service philosophe arrêté puis relancé | vrais services | Tous remangent, 0 violation, un jeton par fourchette. |
| Moniteur arrêté | vrais services | Les philosophes continuent de manger. |
| Moniteur arrêté puis relancé, avec Kafka | vrai Kafka | Il retrouve tous les repas, y compris ceux pris pendant sa panne. |
| Philosophe qui tombe en mangeant | vrai ZooKeeper | Ses verrous sont libérés, le voisin mange. |
| ZooKeeper arrêté | vrai ZooKeeper | Erreur explicite, le philosophe ne mange pas sans verrou. |
| Worker en panne, en retard, redémarré | section 5 | Tâche relancée, résultat exact (voir 5.5). |

**Équité.** Cinq philosophes affamés en permanence pendant 3 secondes : le moins bien servi obtient plus de 80 % des repas du mieux servi (`equite`).

### 4.4 Analyse : ce que les tests prouvent, et ce qu'ils ne prouvent pas

Un test vérifie une propriété **sur les exécutions qu'il a produites**. Il peut montrer la présence d'un défaut, jamais son absence.

| Propriété | Ce que les tests établissent | Ce qui demanderait une vérification formelle |
| --- | --- | --- |
| Exclusion mutuelle | Aucune violation sur des dizaines de milliers de repas, avec pertes, doublons et retards. | Que c'est vrai pour **tous** les entrelacements possibles : invariant « un seul jeton par fourchette » à prouver, par exemple en TLA+ ou avec SPIN. |
| Absence d'interblocage | Toutes les exécutions testées se terminent dans le délai imparti. | Que c'est vrai pour toute exécution. |
| Absence de famine | Chaque philosophe mange dans les exécutions observées. | C'est une propriété de **vivacité** (« finit toujours par arriver ») : aucun test fini ne peut l'établir. Un test ne voit qu'une attente longue, pas une attente infinie. |
| Équité | Écart mesuré entre philosophes sur quelques secondes. | Une borne garantie sur le nombre de fois où un philosophe peut être doublé. |
| Tolérance aux pannes | Les scénarios de panne que nous avons imaginés. | Tous les instants de panne possibles, y compris entre deux écritures. |

Nous donnons en 3.3 des arguments pour chaque propriété. Ce sont des raisonnements sur le papier, pas des preuves vérifiées par un outil.

### 4.5 Limites de la stratégie de test

- Les tests de concurrence dépendent de l'ordonnancement des threads : ils explorent beaucoup d'entrelacements, pas tous.
- Les tests de bout en bout lancent tous les services dans une même JVM : vrais ports et vrai HTTP, mais pas de vraie latence ni de coupure réseau entre machines. Le faux réseau compense en partie.
- Les seuils de temps (délais d'attente des tests) peuvent rendre un test fragile sur une machine très lente. Nous en avons corrigé un pendant le projet (voir 7).
- La détection de violations par le moniteur peut en manquer (voir 2.4). Les tests de concurrence ne s'appuient pas sur elle.
- Aucun test de charge prolongé ni de panne simultanée de plusieurs philosophes.

---

## 5. Architecture Master-Worker

Tout philosophe expose les deux rôles (`GraphController`). Celui qui reçoit un appel `/graph/master/...` devient le Master du calcul et distribue le travail aux autres philosophes, les Workers, par HTTP.

### 5.1 Conception

```text
              ┌────────────────────────── Master (un philosophe) ──────────────────────────┐
  graphe ───► │ 1. découper   2. distribuer   3. relancer si panne   4. assembler          │ ───► résultat
              └───────┬───────────────────────┬───────────────────────┬────────────────────┘
                      │ tranche 0             │ tranche 1             │ tranche 2     (HTTP, JSON)
                      ▼                       ▼                       ▼
                 Worker (phil. 1)        Worker (phil. 2)        Worker (phil. 3)
```

| Classe | Rôle |
| --- | --- |
| `GraphMaster` | Découpe, distribue, assemble. |
| `Dispatcher` | Envoie une tâche à un Worker ; en cas d'échec ou de retard, la relance sur le suivant. |
| `GraphWorker` | Exécute les tâches. |
| `Graphs` | Partitionnement et algorithmes séquentiels de référence. |
| `WorkerClient` | Interface d'appel d'un Worker : HTTP en production, mémoire en test. |

Un graphe est une liste d'arêtes rangée à plat dans un tableau d'entiers `[u0, v0, u1, v1, ...]` : compact en mémoire et en JSON.

### 5.2 Partitionnement et données transmises pour les degrés

**Stratégie : partitionnement par arêtes.** La liste d'arêtes est coupée en tranches de taille égale, une par Worker. Chaque Worker reçoit **uniquement sa tranche** (identifiant du calcul, numéro de tranche, tableau d'arêtes). Il n'a besoin de rien connaître du reste du graphe.

### 5.3 Calcul et résultats partiels

Pour chaque arête `(u, v)` de sa tranche, le Worker ajoute 1 au compteur de `u` et 1 à celui de `v`. Il renvoie deux tableaux, les nœuds touchés et leur contribution, ce qui évite d'envoyer un tableau de la taille du graphe.

### 5.4 Assemblage et double comptage

Le Master additionne les contributions des tranches.

**Pourquoi il n'y a pas de double comptage.** Le risque existe avec un partitionnement par nœuds : une arête qui relie deux partitions serait connue des deux Workers et comptée deux fois si l'on n'y prend pas garde. Avec notre partitionnement par arêtes, **chaque arête appartient à exactement une tranche**, quelles que soient ses extrémités. Elle est donc comptée une seule fois, par un seul Worker, qui crédite ses deux extrémités. Le test `lePartitionnementParAretesMetChaqueAreteDansExactementUneTranche` reconstitue la liste d'origine à partir des tranches.

**Contrôle.** Dans tout graphe la somme des degrés vaut deux fois le nombre d'arêtes. Le Master vérifie cette égalité sur le résultat (`consistent`) : une arête perdue ou comptée deux fois la romprait.

### 5.5 Pannes et retards des Workers

Le `Dispatcher` attend la réponse d'un Worker pendant un délai réglable. En cas d'erreur ou de dépassement, il relance la même tâche sur le Worker suivant ; chaque Worker est essayé au plus deux fois.

**Pourquoi la correction est préservée.**

- Une tâche est une **fonction pure** de ses données : la relancer ailleurs donne le même résultat.
- Le Master ne lit **qu'une seule réponse par tranche** : la première obtenue. Si le Worker lent finit par répondre, sa réponse tardive n'est lue par personne. Rien n'est additionné deux fois.

| Test | Scénario | Résultat |
| --- | --- | --- |
| `workerEnPanne` | Un Worker échoue à chaque appel. | Tâche relancée, degrés exacts. |
| `workerEnRetard` | Un Worker répond en 600 ms, le Master attend 150 ms. | Tâche relancée, la réponse tardive n'est pas comptée, somme exacte. |
| `siTousLesWorkersSontEnPanne...` | Tous en panne. | Échec explicite, pas de résultat faux. |
| `GraphSystemTest` | Les mêmes pannes injectées sur de vrais services en HTTP. | Résultat identique au calcul séquentiel. |

### 5.6 Performances

Calcul des degrés, 1 Master et jusqu'à 8 Workers, **tous sur la même machine** (4 cœurs), graphe aléatoire avec 10 arêtes par nœud, meilleur temps de 3 essais.

| Arêtes | 1 Worker | 2 Workers | 4 Workers | 8 Workers | Séquentiel local |
| --- | --- | --- | --- | --- | --- |
| 100 000 | 19 ms | 12 ms | 14 ms | 14 ms | moins de 1 ms |
| 500 000 | 68 ms | 60 ms | 42 ms | 52 ms | moins de 1 ms |
| 2 000 000 | 345 ms | 273 ms | 210 ms | 188 ms | 3 ms |

**Discussion des limites du parallélisme.**

- **Ajouter des Workers aide, mais peu.** Sur 2 millions d'arêtes, 8 Workers ne vont que 1,8 fois plus vite qu'un seul. Sur 100 000 arêtes le gain disparaît dès 2 Workers.
- **Le calcul distribué est ici 60 à 115 fois plus lent que le calcul local.** Compter des degrés, c'est parcourir un tableau une fois : la machine le fait en 3 ms. Le temps distribué est presque entièrement du transport : transformer 4 millions d'entiers en JSON, les envoyer en HTTP, les relire, puis faire le chemin inverse.
- **La partie non parallélisable domine** (loi d'Amdahl) : le Master découpe, sérialise et assemble seul. Même avec une infinité de Workers, ce travail reste.
- **Tous les services partagent 4 cœurs** : au-delà de 4 Workers ils se gênent. Sur des machines séparées la tendance serait meilleure, avec en plus la latence du réseau.

Conclusion honnête : pour ce calcul et cette taille, distribuer n'est pas rentable. Le Master-Worker devient intéressant quand le travail par tâche est coûteux devant son transport, ou quand le graphe ne tient pas sur une seule machine. L'architecture, elle, est validée : résultat exact, y compris avec des pannes.

### 5.7 Parallélisation de l'accessibilité

**Algorithme : parcours en largeur distribué, tour par tour.**

- **Partitionnement par nœuds.** Le nœud `v` appartient à la partition `v mod P`. Chaque Worker reçoit les arcs qui **partent** de ses nœuds : il sait répondre seul à « quels sont les successeurs de ces nœuds ? ».
- **Un tour.** Le Master envoie à chaque Worker la partie de la frontière qui lui appartient. Les Workers répondent en parallèle par les successeurs. Le Master écarte les nœuds déjà vus et forme la frontière suivante.
- **Arrêt.** Dès que la cible est atteinte (accessible, et le nombre de tours est la longueur du plus court chemin) ou que la frontière est vide (inaccessible).

Seuls les Workers concernés par la frontière sont sollicités. Le Master garde l'ensemble des nœuds visités : c'est lui qui évite de revisiter un nœud atteint par deux partitions.

**Pannes.** Ici le Worker a un état, sa partition. Si un Worker échoue, le Master envoie la partition au Worker suivant et y relance la tâche. Si un Worker a redémarré et perdu sa partition, il répond par un code 409 et le Master la lui renvoie. Le Master conserve le graphe : aucune panne de Worker ne perd de données.

**Validation.** Le résultat distribué est comparé au parcours en largeur séquentiel sur 40 graphes aléatoires, plus les cas particuliers : graphe orienté, autre composante, source égale à la cible.

---

## 6. Approche DevOps

### 6.1 Conteneurisation avec Docker

Un seul `Dockerfile` pour les trois services, choisi par `--build-arg SERVICE=...`, en deux étapes : compilation avec le JDK, puis image d'exécution réduite au JRE. Le conteneur tourne sous un utilisateur non privilégié et la JVM se cale sur la limite mémoire du conteneur.

`docker compose up --build` lance tout le système : ZooKeeper, Kafka, moniteur, superviseur, cinq philosophes et Prometheus. Chaque philosophe a un volume pour l'état de ses jetons, et tire son identifiant de son nom d'hôte (`philosopher-3` donne 3). La variante se choisit par la variable `MODE`.

### 6.2 Communication asynchrone avec Kafka

Le protocole par jetons reste en HTTP, comme l'impose le sujet. Kafka porte ce qui s'y prête : le flux d'événements vers le moniteur.

- Le philosophe publie dans le topic `philosopher-events` (`KafkaEventPublisher`). La clé du message est son identifiant : tous ses événements vont dans la même partition, donc dans l'ordre.
- Le moniteur consomme le topic (`KafkaEventListener`). À chaque démarrage il utilise un nouveau groupe de consommateurs et relit le topic depuis le début : son historique, qui n'existe qu'en mémoire, est **reconstruit à partir de Kafka**.

Ce que cela change : le philosophe n'a plus besoin que le moniteur soit en marche. En HTTP, un moniteur arrêté fait perdre des événements. Avec Kafka, le test `leMoniteurRattrapeSonRetard` montre qu'un moniteur relancé retrouve tout, y compris les repas pris pendant sa panne. Les messages relus deux fois sont écartés par le moniteur.

### 6.3 Orchestration avec Kubernetes

Les manifestes sont dans `k8s/` et se déploient avec `kubectl apply -k k8s`.

| Ressource | Choix | Raison |
| --- | --- | --- |
| Philosophes | **StatefulSet** de 5 réplicas, service sans adresse unique | Chaque philosophe a besoin d'une identité stable (`philosopher-0` à `philosopher-4`, d'où il tire son identifiant, et un nom DNS que les autres peuvent joindre) et d'un disque qui lui reste attaché : l'état de ses jetons survit au remplacement du pod. |
| Moniteur, superviseur, Kafka, ZooKeeper | Deployments | Pas d'identité à conserver. |
| Configuration | ConfigMap unique, injectée en variables d'environnement | Changer de variante sans reconstruire d'image. |
| Sondes | `startupProbe`, `readinessProbe`, `livenessProbe` sur Spring Boot Actuator | Kubernetes ne route que vers un pod prêt et redémarre un pod qui ne répond plus. |
| Ressources | Demandes et limites de mémoire et de processeur | Un service ne peut pas affamer les autres. |

Ce choix répond à la principale limite du protocole par jetons : il suppose qu'un philosophe en panne finit par redémarrer avec son état. Kubernetes le redémarre, le StatefulSet lui rend son nom et son disque.

### 6.4 Chaîne CI/CD (GitHub Actions)

À chaque push et à chaque pull request, `.github/workflows/ci.yml` exécute trois travaux :

1. **Build et tests** : compilation et les 179 tests, y compris ceux qui lancent ZooKeeper et Kafka dans des conteneurs. Les rapports de tests sont conservés.
2. **Images** : construction des trois images Docker ; sur la branche `master` elles sont publiées dans le registre GitHub (`ghcr.io`), étiquetées `latest` et par identifiant de commit.
3. **Déploiement et test de fumée** : création d'un cluster Kubernetes jetable (kind), déploiement des manifestes, puis `ops/smoke-test.sh` vérifie que les cinq philosophes mangent, que le nombre de repas augmente et qu'il n'y a aucune violation. Enfin un pod philosophe est **supprimé** : le test vérifie que Kubernetes le recrée et que le système continue sans violation.

Le déploiement est donc reproductible : il est rejoué de zéro à chaque exécution, à partir du seul contenu du dépôt.

### 6.5 Supervision, journalisation et gestion des pannes

| Besoin | Réponse |
| --- | --- |
| Santé | Spring Boot Actuator : `/actuator/health`, avec les sondes de vivacité et de disponibilité. |
| Métriques | `/actuator/prometheus` sur chaque service. Métriques propres au projet : `philosopher_meals_total` (repas) et `philosopher_wait_seconds` (attente des fourchettes). Prometheus les collecte ; un tableau de bord Grafana est fourni. |
| Supervision métier | Le tableau de bord du moniteur : violations, famine, interblocage suspecté. |
| Journaux | Format JSON structuré (Elastic Common Schema) en conteneur, exploitable par un agrégateur. |
| Appel réseau lent | Délai d'attente sur tous les appels HTTP. |
| Moniteur en panne | File en arrière-plan, nouvelles tentatives et disjoncteur Resilience4j ; ou Kafka, qui conserve les événements. |
| Philosophe en panne | Redémarrage par Docker ou Kubernetes, état des jetons relu sur disque, reprise du protocole. |
| Worker en panne | Tâche relancée ailleurs par le Master. |
| Arrêt d'un service | Arrêt progressif (`server.shutdown=graceful`). |

---

## 7. Difficultés rencontrées et solutions apportées

| Difficulté | Comment elle s'est manifestée | Solution |
| --- | --- | --- |
| Un philosophe redémarré recréait son jeton. | En raisonnant sur le scénario de redémarrage avant d'écrire le test de bout en bout. | Mémoire stable écrite avant tout envoi, reprise au démarrage (3.3). |
| Le test d'équité échouait : un philosophe mangeait 1 123 fois, un autre 124. | Dans le test, un repas durait moins longtemps qu'un message ne voyageait : un philosophe remangeait plusieurs fois avant que la demande de son voisin n'arrive. Ce n'est pas une injustice, personne de connu n'attendait, mais la mesure était faussée. | Dans le test, un repas dure plus longtemps qu'un message, comme en vrai. Le comportement est documenté. |
| En mode ZooKeeper le moniteur signalait de fausses violations. | Les philosophes ne s'échangeant plus de messages, leurs horloges n'avaient plus de lien. | L'horloge transite par un nœud ZooKeeper par fourchette (2.3). |
| Un philosophe tué en plein repas restait « en train de manger » pour le moniteur. | En tuant un conteneur sous Docker Compose : deux fausses violations, alors qu'il y avait bien un seul jeton par fourchette. | Événement `STARTED` au démarrage, et horloge recalée sur le moniteur. |
| Le test d'interblocage du superviseur naïf passait ou échouait selon les exécutions. | Il concluait dès que tous les philosophes étaient affamés, ce qui arrive aussi sans interblocage. | Il attend l'état exact de l'interblocage : chaque philosophe `i` détient la fourchette `i`. |
| Rendre visible une course entre threads. | Sans aide, la double attribution n'arrive presque jamais en test. | Fenêtre de course injectable et barrière. |
| Tester plusieurs microservices ensemble. | Un jar Spring Boot exécutable ne peut pas servir de dépendance Maven. | Le jar exécutable porte le suffixe `-exec` ; le module `system-tests` dépend des jars simples et lance les services dans sa JVM. |
| Lire du JSON partiel avec Spring Boot 4. | Erreur 400 sur l'API du graphe : la nouvelle version de la bibliothèque JSON refuse un champ numérique absent. | Champs facultatifs avec valeur par défaut explicite. |
| Kafka sous Kubernetes. | Un service nommé `kafka` fait injecter une variable `KAFKA_PORT` que l'image Kafka prend pour un réglage. | `enableServiceLinks: false` sur les pods. |

---

## 8. Limites et pistes d'amélioration

- **Panne durable d'un philosophe.** Le protocole par jetons privilégie la sûreté : si un philosophe ne redémarre jamais, les jetons qu'il détient sont perdus, ses voisins sont bloqués, et le blocage se propage de proche en proche parce qu'un philosophe en attente très prioritaire garde ses jetons. Il n'y a pas de détecteur de pannes. En pratique Kubernetes redémarre le pod. Aller plus loin demanderait un détecteur de pannes et un accord entre les survivants pour recréer un jeton, c'est-à-dire un algorithme de consensus : c'est précisément ce que fournit ZooKeeper.
- **Ordre strict.** Un philosophe peut attendre alors que ses fourchettes sont libres mais promises à plus prioritaire. C'est le prix de l'équité, visible dans le débit (57 contre 87 repas par seconde).
- **Coût de la diffusion.** `2(N-1)` messages par repas : acceptable pour 5 philosophes, lourd pour 500.
- **Nombre de philosophes fixe.** Il est donné à la configuration ; ajouter un philosophe demande de relancer le système.
- **Moniteur.** Détection par horloges logiques qui peut manquer des violations ; un seul moniteur ; historique en mémoire, reconstruit depuis Kafka seulement si Kafka est utilisé.
- **Sécurité.** Aucune authentification entre services, pas de chiffrement : hors du périmètre du sujet, indispensable en production.
- **Vérification formelle.** Modéliser le protocole en TLA+ pour vérifier l'exclusion mutuelle et l'absence d'interblocage sur tous les entrelacements d'un petit nombre de philosophes.
- **Master-Worker.** Format binaire au lieu de JSON, et tâches par lots, pour réduire le coût de transport qui domine aujourd'hui.

---

## 9. Reproductibilité des résultats

- **Les mesures.** Les chiffres de ce rapport (débits, temps, nombres de tests) proviennent d'exécutions réelles sur la machine de développement et dans la CI. Ils sont reproductibles avec les commandes du README.
- **Le déploiement.** La chaîne CI/CD reconstruit, teste et redéploie tout le système de zéro à chaque modification, à partir du seul contenu du dépôt.
- **L'équipe.** Chaque membre est référent d'une partie (voir la [répartition du travail](repartition-travail.md)). Le [journal des décisions](decisions.md) et le [guide de démonstration](guide-demonstration.md) rassemblent les choix et le déroulé de la soutenance.
