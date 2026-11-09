# Contribuer au projet

Ces règles gardent `main` stable et rendent le travail à trois lisible.

## Le cycle de travail

1. Prendre une issue, ou en créer une, et se l'assigner.
2. Partir d'un `main` à jour :

   ```bash
   git switch main
   git pull
   git switch -c feature/etape1-monitor
   ```

3. Faire de petits commits, puis lancer les tests avant de pousser :

   ```bash
   ./mvnw verify
   git push -u origin feature/etape1-monitor
   ```

4. Ouvrir une pull request vers `main` et demander une relecture à un camarade.
5. Une fois la CI verte et la relecture faite, fusionner en **Squash and merge** puis supprimer la branche.

On ne pousse jamais directement sur `main`.

## Nommer les branches

| Préfixe | Usage | Exemple |
| --- | --- | --- |
| `feature/` | Nouvelle fonctionnalité | `feature/etape3-jetons` |
| `fix/` | Correction de bug | `fix/superviseur-double-attribution` |
| `test/` | Ajout de tests seuls | `test/concurrence-fourchettes` |
| `docs/` | Documentation et rapport | `docs/rapport-architecture` |
| `chore/` | Outillage, CI, dépendances | `chore/docker-compose` |

## Écrire les messages de commit

Format [Conventional Commits](https://www.conventionalcommits.org/fr/) : `type(service): description courte`.

```text
feat(monitor): reconstruire l'historique avec les horloges logiques
fix(supervisor): rendre la réservation des deux fourchettes atomique
test(philosopher): vérifier le transfert de jeton
docs(rapport): décrire le protocole par jetons
```

## Rester à jour avec `main`

```bash
git fetch origin
git rebase origin/main
git push --force-with-lease
```

Ne réécrire l'historique que sur sa propre branche.

## Relire une pull request

Le relecteur vérifie que le code correspond à l'objectif, que les tests couvrent les cas concurrents ou d'erreur, et qu'il comprend le code qu'il valide.

## Utiliser l'IA

Le sujet autorise l'IA comme assistant, pas comme substitut. Tout code proposé par une IA doit être compris, justifié et testé par la personne qui le pousse, et chacun doit pouvoir défendre son implémentation sans l'outil.
