package fr.cytech.dining.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de résilience de bout en bout : on arrête de vrais services pendant que le protocole par
 * jetons tourne, et on observe le comportement du reste du système.
 */
class ResilienceSystemTest {

    private static final Duration PATIENCE = Duration.ofSeconds(40);

    @Test
    @DisplayName("Moniteur en panne : les philosophes continuent de manger (l'observation n'est pas vitale)")
    void moniteurIndisponible() {
        try (Cluster cluster = Cluster.with(4, "token").start()) {
            await().atMost(PATIENCE).until(() -> cluster.minMeals() >= 2);

            cluster.killMonitor();
            long mealsBefore = cluster.minMeals();

            await().atMost(PATIENCE).untilAsserted(
                    () -> assertThat(cluster.minMeals()).isGreaterThanOrEqualTo(mealsBefore + 5));
        }
    }

    @Test
    @DisplayName("Philosophe arrêté puis redémarré : aucun jeton créé ni perdu, tout le monde remange")
    void panneEtRedemarrageDUnPhilosophe(@TempDir Path stateDirectory) throws InterruptedException {
        try (Cluster cluster = Cluster.with(5, "token").philosopher("state-dir", stateDirectory).start()) {
            await().atMost(PATIENCE).until(() -> cluster.minMeals() >= 3);

            cluster.killPhilosopher(2); // panne franche : le service ne répond plus
            Thread.sleep(1500);         // les autres continuent d'essayer de lui parler

            cluster.startPhilosopher(2); // redémarrage : il retrouve son état sur disque
            long[] mealsAtRestart = cluster.meals();

            await().atMost(PATIENCE).untilAsserted(() -> {
                long[] meals = cluster.meals();
                for (int id = 0; id < cluster.count(); id++) {
                    assertThat(meals[id]).as("le philosophe %d remange après la reprise", id)
                            .isGreaterThanOrEqualTo(mealsAtRestart[id] + 3);
                }
            });
            assertThat(cluster.snapshot().violationCount()).isZero();
            SystemScenariosTest.assertExactlyOneTokenPerFork(cluster);
        }
    }
}
