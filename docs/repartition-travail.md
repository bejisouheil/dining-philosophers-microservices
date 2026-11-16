# Répartition du travail

> **Comment lire ce document.** Pour chaque partie, un membre en est **référent** : c'est lui qui en maîtrise le code, rejoue ses tests, sait justifier ses choix et la présente en soutenance.

## Vue d'ensemble

| Membre | Partie dont il est référent | Sections du sujet |
| --- | --- | --- |
| **Souheil BEJI** | Architecture d'ensemble, philosophe et moniteur, **protocole distribué par jetons** | 1 et 3.3 |
| **Mohamed OUHAB** | **Superviseur** et section critique, **ZooKeeper** et comparaison, stratégie de tests | 2, 3.1, 3.2, 3.4 et 4 |
| **Taimim JELOUALI** | **Master-Worker** sur graphe, **DevOps** | 5 et 6 |

Chaque partie représente à peu près un tiers du code et un tiers du temps de soutenance.

## Souheil BEJI : architecture, moniteur, protocole par jetons

**Périmètre.**

- Architecture générale : un service philosophe et six stratégies (`DiningStrategy`, `PhilosopherLoop`).
- Horloges de Lamport et leur propagation (`LamportClock`).
- Moniteur : historique reconstruit, détection des violations, de la famine et de l'interblocage, tableau de bord (`EventStore`, `SnapshotService`).
- Protocole par jetons : règles, transport, mémoire stable (`TokenNode`, `HttpPeerTransport`, `FileTokenStateStore`).

**Tests à savoir rejouer et expliquer.** `LamportClockTest`, `PhilosopherLoopTest`, `SnapshotServiceTest`, `TokenNodeTest`, `TokenProtocolSimulationTest`, `TokenRecoveryTest`.

**Ce qu'il faut savoir expliquer sans notes.**

1. Les deux règles de l'horloge de Lamport, et pourquoi `DONE` est daté avant de rendre les fourchettes.
2. Les cinq règles du protocole, sur l'exemple à trois philosophes du rapport.
3. Pourquoi il n'y a pas d'interblocage : priorité totale, et un philosophe qui attend cède ses jetons.
4. Pourquoi il n'y a pas de famine : recevoir une demande recale l'horloge, donc on repasse derrière.
5. À quoi servent le numéro de version d'un jeton et la mémoire stable.
6. La limite : une panne durable bloque de proche en proche.

**Questions probables.**

- « Que se passe-t-il si deux philosophes demandent exactement en même temps ? » Même date possible, l'identifiant départage ; tout le monde prend la même décision.
- « Et si un message se perd ? » Demande rediffusée, jeton retenté jusqu'à l'accusé de réception, libération compensée.
- « Pourquoi ne pas avoir pris Chandy et Misra ? » Le sujet impose la diffusion de demandes horodatées et leur mémorisation.
- « Votre moniteur peut-il se tromper ? » Oui, il peut manquer une violation : il raisonne sur des horloges logiques. Les tests de concurrence mesurent directement.

## Mohamed OUHAB : superviseur, ZooKeeper, stratégie de tests

**Périmètre.**

- Superviseur naïf et ses cinq failles (`NaiveForkTable`).
- Section critique sans verrou, puis avec verrou (`UnsafePairForkTable`, `LockedPairForkTable`, `RaceWindow`).
- Solution ZooKeeper avec Apache Curator (`ZooKeeperStrategy`).
- Comparaison chiffrée des trois solutions (`ProtocolComparisonBenchmark`).
- Stratégie de tests du projet et analyse « ce que les tests prouvent ou non ».

**Tests à savoir rejouer et expliquer.** `NaiveForkTableTest`, `PairForkTableTest`, `SupervisedStrategiesTest`, `ZooKeeperStrategyTest`, `SystemScenariosTest`, `ZooKeeperSystemTest`.

**Ce qu'il faut savoir expliquer sans notes.**

1. Les cinq failles du superviseur naïf, chacune avec sa cause.
2. Pourquoi « vérifier puis prendre » n'est pas atomique même avec une `ConcurrentHashMap`.
3. Ce qu'est une section critique et ce que le verrou garantit.
4. Comment un test rend une course déterministe (fenêtre de course et barrière).
5. Comment ZooKeeper évite l'interblocage (ordre global des verrous) et ce qu'il fait quand un client tombe.
6. Le tableau de comparaison : 87, 57 et 51 repas par seconde, et pourquoi le plus rapide est le moins équitable.
7. La différence entre tester et prouver : sûreté, vivacité, vérification formelle.

**Questions probables.**

- « Demander les deux fourchettes d'un coup suffit-il ? » Cela supprime l'interblocage, pas la course entre threads : d'où le verrou.
- « Le verrou règle-t-il tout ? » Non : point unique de défaillance, pas d'équité garantie (attente de 1,5 s mesurée).
- « Pourquoi vos tests de failles sont-ils verts ? » Ils réussissent quand la faille se produit : ils la documentent.
- « ZooKeeper ou votre protocole ? » ZooKeeper pour la tolérance aux pannes, notre protocole pour l'équité et l'absence de dépendance.

## Taimim JELOUALI : Master-Worker, DevOps

**Périmètre.**

- Calcul distribué des degrés et de l'accessibilité (`GraphMaster`, `Dispatcher`, `GraphWorker`, `Graphs`).
- Tolérance aux pannes des Workers et mesures de performances (`GraphBenchmark`).
- Docker et Docker Compose, Kafka pour les événements, manifestes Kubernetes.
- Chaîne CI/CD GitHub Actions, métriques Prometheus, journaux structurés.

**Tests à savoir rejouer et expliquer.** `GraphsTest`, `GraphMasterTest`, `GraphSystemTest`, `KafkaSystemTest`, `ResilienceSystemTest`, et le test de fumée `ops/smoke-test.sh`.

**Ce qu'il faut savoir expliquer sans notes.**

1. Partitionnement par arêtes, et pourquoi il n'y a pas de double comptage.
2. Le contrôle « somme des degrés = deux fois le nombre d'arêtes ».
3. Pourquoi relancer une tâche ne fausse pas le résultat : fonction pure, une seule réponse lue par tranche.
4. Le parcours en largeur distribué, tour par tour.
5. Le tableau de performances et la loi d'Amdahl : pourquoi distribuer est ici plus lent que calculer sur place.
6. Pourquoi un StatefulSet pour les philosophes.
7. Les trois étapes de la chaîne CI/CD et ce que vérifie le test de fumée.
8. Ce que Kafka apporte par rapport à HTTP pour le moniteur.

**Questions probables.**

- « À quoi sert de distribuer si c'est plus lent ? » Ici, à rien pour la vitesse : le transport domine. Cela devient utile quand le calcul par tâche est lourd ou que le graphe ne tient pas sur une machine.
- « Que se passe-t-il si le Master tombe ? » Le calcul est perdu et doit être relancé : le Master est un point unique de défaillance, non traité.
- « Pourquoi Kafka n'est-il pas utilisé entre philosophes ? » Le sujet impose HTTP pour le protocole.
- « Comment savez-vous que le déploiement fonctionne ? » Il est rejoué sur un cluster jetable à chaque exécution de la CI, pod supprimé compris.

## Travail commun

| Activité | Qui |
| --- | --- |
| Relecture croisée : chacun relit la partie d'un autre et lui pose des questions | Tous |
| Rapport technique : relecture de sa partie, correction de ce qui n'est pas clair | Chacun pour sa partie |
| Répétition de la soutenance et de la démonstration | Tous |
| Vérification que tout se lance sur l'ordinateur de démonstration | Souheil BEJI |

## Avant la soutenance : liste de contrôle par membre

- [ ] J'ai lancé le système dans les six modes et j'ai vu de mes yeux l'interblocage et la violation.
- [ ] J'ai rejoué les tests de ma partie (`.\mvnw.cmd verify`) et je sais ce que vérifie chacun des principaux.
- [ ] J'ai lu le code des classes de ma partie et je peux en expliquer chaque méthode publique.
- [ ] Je peux répondre aux questions probables de ma partie sans regarder mes notes.
- [ ] J'ai relu les décisions de ma partie dans le [journal des décisions](decisions.md).
- [ ] Je connais les limites de ma partie (rapport, section 8).
