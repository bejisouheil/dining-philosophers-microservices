# Journal des décisions

Chaque choix technique du projet, les alternatives envisagées et la raison du choix. À relire avant la soutenance : ce sont les questions les plus probables.

## Architecture générale

| # | Décision | Alternatives écartées | Pourquoi |
| --- | --- | --- | --- |
| A1 | **Un seul service philosophe, six stratégies** choisies par `PHILOSOPHER_MODE` (interface `DiningStrategy`). | Un service par section ; des branches Git par section. | La boucle, les événements, les métriques et l'API sont écrits et testés une seule fois. Les variantes se comparent à conditions égales et restent toutes exécutables pour la démonstration. |
| A2 | **Un module `common`** pour l'horloge de Lamport, l'événement et le client HTTP. | Recopier ces classes dans chaque service. | Un format d'événement différent entre émetteur et récepteur serait un bug silencieux. |
| A3 | **Identifiant du philosophe déduit** du nom d'hôte (`philosopher-3`) ou du port (`8084`). | Le configurer à la main pour chaque instance. | Une seule image et une seule configuration pour tous les philosophes : indispensable pour un StatefulSet Kubernetes. |
| A4 | **Délai d'attente sur tous les appels HTTP.** | Valeurs par défaut (attente illimitée). | Un service lent ne doit jamais en bloquer un autre pour toujours. |
| A5 | **Envoi des événements par une file en arrière-plan**, avec Resilience4j (nouvelles tentatives, disjoncteur). | Appel direct depuis la boucle du philosophe. | L'observation ne doit jamais ralentir ni faire échouer le protocole. Moniteur en panne : on perd des événements, pas la correction. |
| A6 | **Resilience4j utilisé directement**, sans son intégration Spring. | Annotations `@Retry`, `@CircuitBreaker`. | L'usage direct est explicite et évite de dépendre de la compatibilité de l'intégration Spring de Resilience4j avec Spring Boot 4, que nous n'avons pas vérifiée. |

## Horloges et moniteur

| # | Décision | Alternatives écartées | Pourquoi |
| --- | --- | --- | --- |
| H1 | **Horloge de Lamport** pour dater les événements. | Heure de la machine ; horloges vectorielles. | L'heure réelle diffère entre machines. Les horloges vectorielles détectent la concurrence mais coûtent un compteur par philosophe dans chaque message ; le sujet demande une horloge logique locale, Lamport suffit pour reconstruire un ordre compatible avec la causalité. |
| H2 | **L'horloge voyage partout où une fourchette change de main** : en-tête HTTP avec le superviseur, messages du protocole par jetons, nœud ZooKeeper. | Ne la propager qu'entre philosophes. | Sans propagation, deux philosophes qui ne se parlent pas ont des horloges sans lien et l'historique reconstruit n'a plus de sens (constaté en mode ZooKeeper). |
| H3 | **`DONE` est daté avant de rendre les fourchettes.** | Rendre puis émettre `DONE`. | Le suivant reçoit ainsi une horloge postérieure au `DONE` : son `EATING` est forcément daté après. |
| H4 | **Le moniteur rejoue l'historique trié** pour calculer l'état et les violations. | Mettre à jour un état au fil des arrivées. | Les événements arrivent dans le désordre (réseau, Kafka). Rejouer l'historique trié donne le même résultat quel que soit l'ordre d'arrivée. |
| H5 | **Événement `STARTED` et recalage de l'horloge sur le moniteur au démarrage.** | Ne rien faire au redémarrage. | Un philosophe tué en plein repas n'émet jamais `DONE` : sans `STARTED` le moniteur le croit encore à table et signale de fausses violations. |
| H6 | **Tableau de bord en HTML et JavaScript simples**, servi par le moniteur. | Application React séparée ; Grafana seul. | Aucun outil de plus à installer ni à construire ; Grafana est fourni en complément pour les métriques. |

## Superviseur (sections 2, 3.1, 3.2)

| # | Décision | Alternatives écartées | Pourquoi |
| --- | --- | --- | --- |
| S1 | **Les trois variantes cohabitent** dans le même service (`/naive`, `/unsafe`, `/locked`). | Trois services. | Même code autour, seule la section critique change : la comparaison est directe. |
| S2 | **Fenêtre de course injectable** (`RaceWindow`), réglable aussi en production par `SUPERVISOR_RACE_WINDOW_MS`. | Compter sur le hasard ; boucles de millions d'essais. | Un test doit échouer ou réussir à chaque fois. Une barrière dans la fenêtre provoque la course à coup sûr ; en démonstration, 40 ms la rendent visible à l'écran. |
| S3 | **`ConcurrentHashMap` dans les variantes fautives.** | `HashMap` simple. | Montrer que la faille vient de l'enchaînement « vérifier puis prendre » et non de la structure de données : chaque opération isolée est sûre, la séquence ne l'est pas. |
| S4 | **`ReentrantLock` équitable** pour la section critique. | `synchronized` ; verrou par fourchette. | Un seul verrou global rend la preuve triviale ; l'équité donne un ordre d'entrée premier arrivé, premier servi. Un verrou par fourchette réintroduirait un risque d'interblocage entre verrous. |
| S5 | **Tests qui réussissent quand la faille se produit**, regroupés sous « Failles recensées ». | Tests désactivés ou en échec. | Le sujet demande de recenser les failles par un jeu de tests : chaque test documente un comportement fautif reproductible. |

## Protocole par jetons (section 3.3)

| # | Décision | Alternatives écartées | Pourquoi |
| --- | --- | --- | --- |
| J1 | **Un jeton par fourchette, demandes datées diffusées à tous, transfert selon la priorité.** | Chandy et Misra (fourchettes sales et propres) ; Ricart et Agrawala (permissions de tous) ; Suzuki et Kasami (un seul jeton global). | Le sujet impose un jeton par fourchette, la diffusion des demandes horodatées et leur mémorisation. Chandy et Misra ne diffuse pas ; Ricart et Agrawala n'a pas de jeton ; un jeton global interdirait à deux philosophes éloignés de manger en même temps. |
| J2 | **Priorité = (date de Lamport, identifiant)**, ordre total identique partout. | Priorité fixe par identifiant ; ordre d'arrivée local. | Une priorité fixe affame les grands identifiants ; un ordre d'arrivée local diffère d'un philosophe à l'autre et permet des cycles. |
| J3 | **Un philosophe qui attend cède ses jetons à plus prioritaire.** | Garder ce qu'on tient jusqu'à avoir mangé. | Garder en attendant est exactement la condition « tenir et attendre » de l'interblocage. |
| J4 | **On ne lâche jamais une fourchette pendant le repas.** | Préemption du repas. | Un repas est une section critique : l'interrompre casserait l'exclusion mutuelle. |
| J5 | **Règle d'attribution : fourchettes `i` et `(i+1) mod N`**, mais le protocole accepte n'importe quelle paire. | Paire libre choisie par le philosophe. | Règle simple, équilibrée (chaque fourchette a deux utilisateurs), et le sujet demande une règle automatique. La généralité est conservée et testée. |
| J6 | **Numéro de version sur chaque jeton.** | Identifiant unique par message, mémorisé à vie. | Un entier par fourchette suffit à reconnaître un jeton déjà reçu : un doublon ne crée pas de second jeton. |
| J7 | **Jeton retenté sans limite jusqu'à l'accusé de réception** ; demande rediffusée périodiquement ; libération retentée un nombre limité de fois. | Tout retenter sans limite ; ne rien retenter. | Perdre un jeton bloque tout : il ne doit jamais disparaître. Une demande perdue se rattrape seule. Une libération perdue est compensée par deux autres mécanismes. |
| J8 | **Chaque message annonce la dernière demande terminée de son émetteur.** | Compter uniquement sur le message de libération. | Sans cela une libération perdue laisse une demande fantôme qui attire les jetons en boucle entre deux philosophes. |
| J9 | **Mémoire stable écrite avant tout envoi** (journalisation préalable), fichier temporaire puis renommage. | État en mémoire seulement ; base de données. | Sans elle un philosophe redémarré recrée son jeton : deux jetons pour une fourchette. Un fichier par philosophe suffit et s'accorde avec un disque attaché par Kubernetes. |
| J10 | **Transport : une file et un thread par destinataire.** | Un thread par message ; envoi synchrone depuis le protocole. | Ordre conservé vers chaque philosophe ; un destinataire lent ne bloque pas les autres ; le protocole ne fait jamais d'entrée-sortie sous son verrou. |
| J11 | **`TokenNode` sans Spring ni réseau**, derrière une interface de transport. | Tout mettre dans le contrôleur REST. | Le même code est testé avec Mockito, avec un faux réseau qui perd et duplique, et avec le vrai HTTP. |
| J12 | **Sûreté avant vivacité** : aucun jeton recréé sur simple délai dépassé. | Recréer le jeton d'un philosophe qui ne répond plus. | Un philosophe lent n'est pas un philosophe mort : recréer son jeton pourrait en donner deux. La reprise est confiée au redémarrage (Kubernetes). |

## ZooKeeper (section 3.4)

| # | Décision | Alternatives écartées | Pourquoi |
| --- | --- | --- | --- |
| Z1 | **Apache Curator, `InterProcessMutex`, un verrou par fourchette.** | Écrire la recette de verrou à la main ; etcd ; Redis. | Le sujet cite ZooKeeper et Curator ; la recette de Curator gère les nœuds séquentiels éphémères et les cas d'erreur. |
| Z2 | **Verrous pris dans l'ordre croissant des numéros.** | `InterProcessMultiLock` ; ordre gauche puis droite. | Un ordre global sur les ressources rend le cycle d'attente impossible. L'ordre gauche puis droite recrée l'interblocage du superviseur naïf. |
| Z3 | **L'horloge de Lamport passe par un nœud ZooKeeper par fourchette.** | Renoncer à l'historique dans ce mode. | Garder un moniteur cohérent dans toutes les variantes ; coût : quatre opérations de plus par repas. |
| Z4 | **Tests avec un ZooKeeper embarqué** (curator-test) et un test de bout en bout avec un conteneur (Testcontainers). | Bouchonner Curator. | Un verrou distribué bouchonné ne teste rien. Le serveur embarqué est rapide ; le conteneur valide la vraie image. |

## Tests (section 4)

| # | Décision | Alternatives écartées | Pourquoi |
| --- | --- | --- | --- |
| T1 | **Module `system-tests` qui lance les vrais services dans sa JVM.** | Scripts shell avec Docker Compose. | Démarrage en quelques secondes, assertions en Java, exécutable par `mvnw verify` et dans la CI sans rien installer. |
| T2 | **Faux réseau en mémoire** capable de retarder, perdre et dupliquer. | Outil de perturbation réseau au niveau du système. | Reproductible (graine aléatoire fixe) et sans dépendance. |
| T3 | **Compteur par fourchette** pour mesurer les violations dans les tests de concurrence. | S'appuyer sur le moniteur. | Mesure directe et exacte ; la détection du moniteur peut manquer des cas. |
| T4 | **Mesures de performances séparées des tests** (étiquette `benchmark`). | Les inclure dans la compilation. | Une mesure n'a pas à faire échouer une compilation, et elle dure. |

## Master-Worker (section 5)

| # | Décision | Alternatives écartées | Pourquoi |
| --- | --- | --- | --- |
| G1 | **Degrés : partitionnement par arêtes.** | Partitionnement par nœuds. | Chaque arête est dans une seule tranche : aucun double comptage à corriger, tranches équilibrées. Par nœuds, les arêtes entre partitions apparaîtraient deux fois. |
| G2 | **Le Master ne lit que la première réponse par tranche.** | Annuler la tâche lente ; dédupliquer par identifiant côté Worker. | Simple et suffisant : la tâche est une fonction pure, une réponse tardive n'est lue par personne. |
| G3 | **Contrôle « somme des degrés = 2 × arêtes ».** | Aucun contrôle. | Détecte à coût nul une arête perdue ou comptée deux fois. |
| G4 | **Accessibilité : parcours en largeur tour par tour, partitionnement par nœuds, visités chez le Master.** | Parcours en profondeur ; parcours bidirectionnel ; ensemble des visités réparti. | La largeur se parallélise par niveau ; garder les visités au Master évite toute coordination entre Workers ; le nombre de tours donne la distance. |
| G5 | **Le Master conserve le graphe.** | Le confier aux Workers. | Aucune panne de Worker ne perd de données : il suffit de renvoyer la partition. |
| G6 | **Rôles Master et Worker portés par le service philosophe.** | Deux services dédiés. | Le sujet dit qu'un philosophe joue le Master et que les autres jouent les Workers. |

## DevOps (section 6)

| # | Décision | Alternatives écartées | Pourquoi |
| --- | --- | --- | --- |
| O1 | **Kafka pour les événements seulement** ; le protocole par jetons reste en HTTP. | Tout passer par Kafka. | Contrainte de la section 3.3 : messages HTTP entre philosophes. Kafka convient au flux d'observation, à sens unique et tolérant au retard. |
| O2 | **Le moniteur relit tout le topic à chaque démarrage.** | Groupe de consommateurs fixe. | Son historique n'existe qu'en mémoire : Kafka devient sa source de vérité, et une panne du moniteur ne perd rien. |
| O3 | **Clé Kafka = identifiant du philosophe.** | Pas de clé. | Les événements d'un philosophe restent dans l'ordre. |
| O4 | **StatefulSet pour les philosophes.** | Deployment. | Identité stable et disque attaché : les deux hypothèses du protocole par jetons. |
| O5 | **Un seul Dockerfile en deux étapes.** | Un Dockerfile par service ; image construite par un greffon Maven. | Une seule recette à maintenir ; image finale sans JDK ni sources ; utilisateur non privilégié. |
| O6 | **Déploiement sur un cluster kind dans la CI**, avec suppression d'un pod. | Déployer sur un cluster payant ; ne pas tester les manifestes. | Gratuit, reproductible, et prouve à chaque exécution que les manifestes fonctionnent et que la reprise après panne est réelle. |
| O7 | **Configuration par variables d'environnement.** | Fichiers de configuration par environnement. | La même image tourne en local, sous Docker Compose et sous Kubernetes. |
