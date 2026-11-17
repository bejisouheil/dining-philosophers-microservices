# Le dîner des philosophes en microservices

[![CI/CD](https://github.com/bejisouheil/dining-philosophers-microservices/actions/workflows/ci.yml/badge.svg)](https://github.com/bejisouheil/dining-philosophers-microservices/actions/workflows/ci.yml)
[![Licence : MIT](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)

Du comportement naïf à l'exclusion mutuelle et à l'interblocage : le problème classique des philosophes, réalisé avec des microservices Spring Boot qui communiquent par messages HTTP.

Projet de groupe du cours de **Conception Système** (CY Tech, ING2).

## Le problème

N philosophes indépendants enchaînent en boucle trois états : penser, avoir faim, manger. Pour manger, un philosophe a besoin de deux fourchettes partagées avec ses voisins. Le projet part d'une version sans partage, passe par un superviseur central, puis arrive à un protocole distribué par jetons sans aucun coordinateur, comparé ensuite à ZooKeeper.

## Les six variantes

Le même `philosopher-service` exécute les six variantes du sujet. On choisit avec `PHILOSOPHER_MODE`.

| Mode | Section | Ce qui se passe | Ce qu'on observe sur le tableau de bord |
| --- | --- | --- | --- |
| `spoiled` | 1 | Aucune fourchette partagée. | Tout le monde mange quand il veut. |
| `supervised-naive` | 2 | Superviseur naïf, une fourchette après l'autre. | **Interblocage** : tous affamés, plus aucun repas. |
| `supervised-unsafe` | 3.1 | Les deux fourchettes ensemble, sans verrou. | **Violations** : une fourchette utilisée deux fois. |
| `supervised-locked` | 3.2 | Les deux fourchettes ensemble, section critique verrouillée. | Aucune violation, tout le monde mange. |
| `token` | 3.3 | Protocole distribué par jetons, **sans superviseur**. | Aucune violation, repas répartis équitablement. |
| `zookeeper` | 3.4 | Verrous distribués ZooKeeper (Apache Curator). | Aucune violation, tout le monde mange. |

## Architecture

```text
                        événements HUNGRY / EATING / DONE
   philosopher-0 ──┐        (HTTP, ou Kafka en section 6)        ┌──────────────────┐
   philosopher-1 ──┼──────────────────────────────────────────►  │ monitor-service  │ ──► tableau de bord
   philosopher-2 ──┤                                             │ historique trié  │     http://localhost:8090
   philosopher-3 ──┤                                             │ par horloge de   │
   philosopher-4 ──┘                                             │ Lamport          │
        │  ▲                                                     └──────────────────┘
        │  │ jetons et demandes horodatées (mode token, HTTP entre philosophes)
        ▼  │
   ┌─────────────────────┐          ┌───────────────┐
   │ supervisor-service  │          │  ZooKeeper    │
   │ modes supervised-*  │          │ mode zookeeper│
   └─────────────────────┘          └───────────────┘
```

| Module | Rôle | Port |
| --- | --- | --- |
| `philosopher-service` | Un philosophe. Porte aussi les rôles Master et Worker du calcul sur graphe (section 5). | `8081` + identifiant |
| `monitor-service` | Reçoit les événements, reconstruit l'historique, détecte violations, famine et interblocage, sert le tableau de bord. | `8090` |
| `supervisor-service` | Attribue et libère les fourchettes : variantes `/naive`, `/unsafe`, `/locked`. | `8080` |
| `common` | Code partagé : horloge de Lamport, événement, client HTTP. | |
| `system-tests` | Tests de bout en bout : de vrais microservices qui se parlent en HTTP. | |

**Technologies :** Java 21, Spring Boot 4, Maven, Resilience4j, Apache Curator et ZooKeeper, Apache Kafka, JUnit 5, Mockito, Awaitility, Testcontainers, Docker, Kubernetes, GitHub Actions, Prometheus et Grafana.

## Démarrer

### Le plus rapide sous Windows : un seul script

Après un `git clone`, double-cliquer sur `setup-and-run.cmd` (ou, dans un terminal du dépôt, `.\scripts\setup-and-run.ps1`). Le script vérifie Git et Java 21, les installe via `winget` s'ils manquent, compile, démarre le système et ouvre le tableau de bord. Options : `-Mode <variante>`, `-Test` (lance aussi les 179 tests), `-Docker` (utilise Docker Compose).


**Prérequis :** JDK 21 et Git. Maven n'est pas nécessaire, le wrapper `mvnw` l'installe tout seul. Docker est nécessaire pour Docker Compose et pour les tests ZooKeeper et Kafka.

### Avec Docker Compose (le plus simple)

```bash
docker compose up --build
```

Puis ouvrir le tableau de bord : <http://localhost:8090>. Tout démarre : ZooKeeper, Kafka, le moniteur, le superviseur, cinq philosophes et Prometheus.

Changer de variante (les conteneurs concernés sont recréés) :

```bash
MODE=supervised-naive THINK_MIN_MS=0 THINK_MAX_MS=0 docker compose up -d
```

Avec un temps de réflexion nul les philosophes ont faim en permanence : l'interblocage du superviseur naïf apparaît en moins d'une minute.

Rendre visibles les failles du superviseur en élargissant sa fenêtre de course :

```bash
MODE=supervised-unsafe RACE_WINDOW_MS=40 docker compose up -d
```

Sous PowerShell : `$env:MODE = "supervised-naive"; docker compose up -d`.

Arrêter et tout effacer :

```bash
docker compose down -v
```

### Sans Docker, depuis VS Code ou un terminal (Windows)

Le script compile si besoin, puis lance le moniteur, le superviseur et cinq philosophes en arrière-plan :

```powershell
.\scripts\start-local.ps1 -Mode token
```

```powershell
.\scripts\stop-local.ps1
```

Options : `-Mode` (une des six variantes), `-Count` (nombre de philosophes), `-RaceWindowMs` (fenêtre de course du superviseur), `-ThinkMaxMs` (temps de réflexion maximal ; `0` provoque vite l'interblocage du mode naïf), `-Build` (recompiler). Les journaux sont dans le dossier `.run`. Le mode `zookeeper` a besoin d'un ZooKeeper : `docker run -d -p 2181:2181 zookeeper:3.9`.

### À la main, service par service

```bash
./mvnw -q -DskipTests package
java -jar monitor-service/target/monitor-service-0.1.0-SNAPSHOT-exec.jar
PORT=8082 PHILOSOPHER_MODE=token java -jar philosopher-service/target/philosopher-service-0.1.0-SNAPSHOT-exec.jar
```

L'identifiant d'un philosophe se déduit de son port : `8081` est le philosophe 0, `8082` le philosophe 1, etc. Toutes les options sont décrites dans [`PhilosopherProperties`](philosopher-service/src/main/java/fr/cytech/dining/philosopher/config/PhilosopherProperties.java).

## API utiles

| Appel | Service | Effet |
| --- | --- | --- |
| `GET /api/snapshot` | moniteur | État de chaque philosophe, violations, famine, interblocage suspecté. |
| `GET /api/events` | moniteur | Historique complet, trié par horloge de Lamport. |
| `DELETE /api/events` | moniteur | Remise à zéro entre deux démonstrations. |
| `GET /state` | philosophe | État, horloge, nombre de repas, temps d'attente. |
| `POST /control/stop`, `POST /control/start` | philosophe | Arrêter ou relancer sa boucle. |
| `GET /token/state` | philosophe (mode `token`) | Jetons détenus, jetons en vol, demandes mémorisées. |
| `GET /forks` | superviseur | Qui détient quelle fourchette, par variante. |
| `POST /graph/master/degrees` | philosophe | Calcul distribué des degrés d'un graphe (section 5). |
| `POST /graph/master/reachability` | philosophe | Accessibilité distribuée dans un graphe orienté. |
| `GET /actuator/health`, `GET /actuator/prometheus` | tous | Santé et métriques. |

Exemple, calcul des degrés d'un graphe aléatoire d'un million d'arêtes, le philosophe 0 jouant le Master :

```bash
curl -X POST localhost:8081/graph/master/degrees -H "Content-Type: application/json" -d '{"nodes":100000,"edgeCount":1000000,"seed":42}'
```

## Tests

179 tests automatisés, répartis en tests unitaires (JUnit 5, Mockito), tests d'intégration, tests de concurrence, tests de résilience et tests de bout en bout.

```bash
./mvnw verify
```

Sous Windows avec PowerShell, remplacer `./mvnw` par `.\mvnw.cmd`. Les tests ZooKeeper et Kafka de bout en bout utilisent Testcontainers : ils sont ignorés si Docker n'est pas démarré.

Les mesures de performances ne tournent que sur demande :

```bash
./mvnw -pl system-tests -am verify -DexcludedGroups= -Dgroups=benchmark -Dtest=ProtocolComparisonBenchmark -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
./mvnw -pl system-tests -am verify -DexcludedGroups= -Dgroups=benchmark -Dtest=GraphBenchmark -Dsurefire.failIfNoSpecifiedTests=false
```

La stratégie de test, les résultats et leurs limites sont détaillés dans le [rapport technique](docs/rapport-technique.md#4-tests-et-résultats).

## Kubernetes

```bash
kubectl apply -k k8s
```

```bash
kubectl -n dining port-forward svc/monitor 8090:8090
```

Les philosophes forment un StatefulSet : chaque pod a un nom stable (`philosopher-0` à `philosopher-4`) dont il tire son identifiant, et un disque où il garde l'état de ses jetons. Les images sont publiées par la CI sur `ghcr.io/bejisouheil/dining-philosophers-microservices`. La chaîne CI/CD déploie le tout sur un cluster jetable à chaque exécution et vérifie qu'un pod supprimé est remplacé sans violation.

## Structure du dépôt

```text
.
├── common/                Horloge de Lamport, événement, client HTTP
├── philosopher-service/   Philosophe : six stratégies, protocole par jetons, Master-Worker
├── monitor-service/       Moniteur et tableau de bord
├── supervisor-service/    Superviseur des fourchettes (naïf, sans verrou, avec verrou)
├── system-tests/          Tests de bout en bout et mesures de performances
├── k8s/                   Manifestes Kubernetes
├── ops/                   Prometheus, Grafana, test de fumée
├── scripts/               Lancement local sous Windows
├── docs/                  Rapport, décisions, répartition, guide de démonstration
├── Dockerfile             Image commune aux trois services
├── docker-compose.yml     Tout le système en une commande
└── pom.xml                POM parent Maven
```

## Documentation

- [Rapport technique](docs/rapport-technique.md) : architecture, protocoles, choix, tests, résultats, limites.
- [Journal des décisions](docs/decisions.md) : chaque choix technique, ses alternatives et sa justification.
- [Répartition du travail](docs/repartition-travail.md) : qui est responsable de quoi.
- [Guide de démonstration](docs/guide-demonstration.md) : le déroulé pas à pas pour la soutenance.

## Avancement

- [x] **1. Philosophes gâtés :** instances indépendantes, moniteur, horloges logiques, tableau de bord
- [x] **2. Superviseur :** implémentation naïve et recensement de ses failles par des tests
- [x] **3.1-3.2 Section critique :** demande simultanée des deux fourchettes, puis verrou
- [x] **3.3 Protocole par jetons :** sans superviseur, avec équité, sans interblocage ni famine
- [x] **3.4 Coordination distribuée :** verrous ZooKeeper et comparaison chiffrée
- [x] **4. Tests :** unitaires, intégration, concurrence, résilience, analyse des résultats
- [x] **5. Master-Worker :** degrés d'un grand graphe, pannes de Workers, performances, accessibilité
- [x] **6. DevOps :** Docker, Kafka, Kubernetes, CI/CD, supervision et journalisation
- [x] **7. Livrables :** code, [rapport technique](docs/rapport-technique.md), tests et résultats

## Équipe

| Membre | GitHub |
| --- | --- |
| Souheil BEJI | [@bejisouheil](https://github.com/bejisouheil) |
| Mohamed OUHAB | [@Quirlo](https://github.com/Quirlo) |
| Taimim JELOUALI | [@Taimim](https://github.com/Taimim) |

## Contribuer

Le cycle de travail, le nommage des branches et le format des commits sont décrits dans [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Code sous licence [MIT](LICENSE).
