# Le dîner des philosophes en microservices

[![CI](https://github.com/bejisouheil/dining-philosophers-microservices/actions/workflows/ci.yml/badge.svg)](https://github.com/bejisouheil/dining-philosophers-microservices/actions/workflows/ci.yml)
[![Licence : MIT](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)

Du comportement naïf à l'exclusion mutuelle et à l'interblocage : le problème classique des philosophes, réalisé avec des microservices Spring Boot qui communiquent par messages HTTP.

Projet de groupe du cours de **Conception Système** (CY Tech, ING2).

> **État du projet :** squelette en place, les étapes du sujet restent à réaliser (voir [Avancement](#avancement)).

## Le problème

N philosophes indépendants enchaînent en boucle trois états : penser, avoir faim, manger. Pour manger, un philosophe a besoin de deux fourchettes partagées. Le projet part d'une version sans partage, passe par un superviseur central, puis arrive à un protocole distribué par jetons sans coordinateur.

## Architecture

| Service | Rôle | Port par défaut |
| --- | --- | --- |
| `philosopher-service` | Un philosophe. Plusieurs instances tournent en parallèle. | `8081` |
| `monitor-service` | Reçoit les événements `HUNGRY`, `EATING` et `DONE`, et fournit le tableau de bord. | `8090` |
| `supervisor-service` | Attribue et libère les fourchettes (étapes 2 et 3.1-3.2). | `8080` |

## Technologies

Déjà en place : Java 21, Spring Boot 4, Maven, JUnit Jupiter, Mockito, GitHub Actions.

Prévu par le sujet : Apache ZooKeeper ou équivalent, Testcontainers, Resilience4j, Docker, Kafka, Kubernetes.

## Démarrer

**Prérequis :** JDK 21 et Git. Maven n'est pas nécessaire, le wrapper `mvnw` l'installe tout seul.

```bash
git clone https://github.com/bejisouheil/dining-philosophers-microservices.git
cd dining-philosophers-microservices
```

Compiler et lancer tous les tests :

```bash
./mvnw verify
```

Lancer un service :

```bash
./mvnw -pl monitor-service spring-boot:run
```

Sous Windows avec PowerShell, remplacer `./mvnw` par `.\mvnw.cmd`.

Lancer un deuxième philosophe dans un autre terminal (Git Bash, Linux ou macOS) :

```bash
PORT=8082 ./mvnw -pl philosopher-service spring-boot:run
```

Équivalent PowerShell :

```powershell
$env:PORT = "8082"; .\mvnw.cmd -pl philosopher-service spring-boot:run
```

Vérifier qu'un service répond : <http://localhost:8090/actuator/health>.

## Structure du dépôt

```text
.
├── philosopher-service/   Microservice philosophe
├── monitor-service/       Moniteur et tableau de bord
├── supervisor-service/    Superviseur des fourchettes
├── docs/                  Rapport technique
├── .github/               CI, modèles d'issue et de pull request
├── CONTRIBUTING.md        Règles de travail de l'équipe
└── pom.xml                POM parent Maven
```

## Avancement

- [ ] **1. Philosophes gâtés :** instances indépendantes, moniteur, horloges logiques, tableau de bord
- [ ] **2. Superviseur :** implémentation naïve et recensement de ses failles par des tests
- [ ] **3.1-3.2 Section critique :** demande simultanée des deux fourchettes, puis verrou
- [ ] **3.3 Protocole par jetons :** sans superviseur, avec équité, sans interblocage ni famine
- [ ] **3.4 Coordination distribuée :** verrou avec ZooKeeper ou équivalent, et comparaison
- [ ] **4. Tests :** unitaires, intégration, concurrence, résilience, analyse des résultats
- [ ] **5. Master-Worker :** degrés d'un grand graphe, pannes de Workers, performances, accessibilité
- [ ] **6. DevOps :** Docker, Kafka, Kubernetes, CI/CD, supervision et journalisation
- [ ] **7. Livrables :** code, [rapport technique](docs/rapport-technique.md), tests et résultats

## Équipe

| Membre | GitHub |
| --- | --- |
| Souheil BEJI | [@bejisouheil](https://github.com/bejisouheil) |
| *À compléter* | |
| *À compléter* | |

## Contribuer

Le cycle de travail, le nommage des branches et le format des commits sont décrits dans [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Distribué sous licence [MIT](LICENSE).
