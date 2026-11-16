package fr.cytech.dining.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.cytech.dining.common.PhilosopherEvent;
import fr.cytech.dining.monitor.Snapshot;
import fr.cytech.dining.philosopher.core.PhilosopherLoop;

/**
 * Tests d'intégration de bout en bout, une variante du sujet par test. Chaque test démarre de
 * vrais microservices (moniteur, superviseur, philosophes) qui se parlent en HTTP.
 */
class SystemScenariosTest {

    private static final Duration PATIENCE = Duration.ofSeconds(40);

    @Test
    @DisplayName("Section 1 : les philosophes gâtés mangent librement et le moniteur reconstruit l'historique")
    void philosophesGates() {
        try (Cluster cluster = Cluster.with(3, "spoiled").start()) {
            await().atMost(PATIENCE).untilAsserted(() -> {
                Snapshot snapshot = cluster.snapshot();
                assertThat(snapshot.philosophers()).hasSize(3);
                assertThat(snapshot.totalMeals()).isGreaterThanOrEqualTo(9);
            });

            Snapshot snapshot = cluster.snapshot();
            assertThat(snapshot.violationCount()).isZero();
            List<PhilosopherEvent> recent = snapshot.recentEvents(); // du plus récent au plus ancien
            for (int i = 1; i < recent.size(); i++) {
                assertThat(recent.get(i).lamportTime()).isLessThanOrEqualTo(recent.get(i - 1).lamportTime());
            }
        }
    }

    @Test
    @DisplayName("Section 2 : avec le superviseur naïf, le système finit en interblocage")
    void superviseurNaifInterblocage() throws InterruptedException {
        try (Cluster cluster = Cluster.with(5, "supervised-naive")
                .philosopher("auto-start", false)
                .philosopher("think-min-ms", 0).philosopher("think-max-ms", 0)
                .start()) {
            for (int id = 0; id < cluster.count(); id++) {
                cluster.control(id, "start"); // tout le monde à table en même temps
            }

            await().atMost(PATIENCE).untilAsserted(() -> {
                for (int id = 0; id < cluster.count(); id++) {
                    assertThat(cluster.state(id).state()).isEqualTo(PhilosopherLoop.State.HUNGRY);
                }
            });

            // Chacun tient sa première fourchette et attend la seconde : plus rien ne bouge.
            long[] mealsWhenStuck = cluster.meals();
            Thread.sleep(1500);
            assertThat(cluster.meals()).as("plus personne ne mange").isEqualTo(mealsWhenStuck);
            assertThat(cluster.supervisorForks())
                    .contains("\"naive\":{\"0\":0,\"1\":1,\"2\":2,\"3\":3,\"4\":4}");
        }
    }

    @Test
    @DisplayName("Section 3.1 : sans verrou, le moniteur observe une fourchette utilisée deux fois")
    void paireSansVerrouViolation() {
        try (Cluster cluster = Cluster.with(5, "supervised-unsafe")
                .supervisor("race-window-ms", 40)
                .philosopher("think-min-ms", 0).philosopher("think-max-ms", 3)
                .philosopher("eat-min-ms", 30).philosopher("eat-max-ms", 40)
                .start()) {
            await().atMost(PATIENCE).untilAsserted(
                    () -> assertThat(cluster.snapshot().violationCount()).isGreaterThan(0));
        }
    }

    @Test
    @DisplayName("Section 3.2 : avec verrou, aucune violation et tout le monde mange")
    void paireAvecVerrou() {
        try (Cluster cluster = Cluster.with(5, "supervised-locked")
                .supervisor("race-window-ms", 5) // même fenêtre de course que sans verrou, rendue inoffensive
                .start()) {
            await().atMost(PATIENCE).untilAsserted(() -> assertThat(cluster.minMeals()).isGreaterThanOrEqualTo(5));

            assertThat(cluster.snapshot().violationCount()).isZero();
        }
    }

    @Test
    @DisplayName("Section 3.3 : protocole par jetons en HTTP, ni violation, ni interblocage, ni jeton perdu")
    void protocoleParJetons() {
        try (Cluster cluster = Cluster.with(5, "token").start()) {
            await().atMost(PATIENCE).untilAsserted(() -> assertThat(cluster.minMeals()).isGreaterThanOrEqualTo(8));

            Snapshot snapshot = cluster.snapshot();
            assertThat(snapshot.violationCount()).isZero();
            assertThat(snapshot.suspectedDeadlock()).isFalse();
            long[] meals = cluster.meals();
            long max = Arrays.stream(meals).max().orElseThrow();
            long min = Arrays.stream(meals).min().orElseThrow();
            assertThat((double) min / max).as("équité : repas par philosophe %s", Arrays.toString(meals))
                    .isGreaterThan(0.5);

            assertExactlyOneTokenPerFork(cluster);
        }
    }

    /** Arrête les boucles puis vérifie qu'il reste exactement un jeton par fourchette. */
    static void assertExactlyOneTokenPerFork(Cluster cluster) {
        for (int id = 0; id < cluster.count(); id++) {
            cluster.control(id, "stop");
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            int[] holders = new int[cluster.count()];
            for (int id = 0; id < cluster.count(); id++) {
                assertThat(cluster.tokenState(id).tokensInFlight()).as("jetons encore en vol").isEmpty();
                cluster.tokenState(id).heldTokens().keySet().forEach(fork -> holders[fork]++);
            }
            assertThat(holders).as("nombre de détenteurs par fourchette").containsOnly(1);
        });
    }
}
