package fr.cytech.dining.system;

import java.util.Arrays;
import java.util.Locale;

import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import fr.cytech.dining.philosopher.core.PhilosopherLoop;

/**
 * Section 3.4, comparaison chiffrée des solutions sur la même charge : 5 philosophes toujours
 * affamés, repas de 20 ms, pendant 15 secondes.
 *
 * <p>Mesure, pas test : exclu de la compilation normale. Lancer avec
 * {@code ./mvnw -pl system-tests -am verify -DexcludedGroups= -Dgroups=benchmark
 * -Dtest=ProtocolComparisonBenchmark -Dsurefire.failIfNoSpecifiedTests=false}.
 */
@Tag("benchmark")
class ProtocolComparisonBenchmark {

    private static final int PHILOSOPHERS = 5;
    private static final int SECONDS = 15;

    @Test
    @DisplayName("Débit et temps d'attente : verrou centralisé, jetons, ZooKeeper")
    void compare() throws Exception {
        StringBuilder table = new StringBuilder();
        table.append(String.format("%n| %-22s | %9s | %12s | %12s | %-22s |%n", "Solution", "Repas/s",
                "Attente moy.", "Attente max", "Repas par philosophe"));
        table.append("|---|---|---|---|---|\n");

        table.append(measure("Superviseur + verrou", "supervised-locked", null));
        table.append(measure("Jetons (notre protocole)", "token", null));
        try (TestingServer zookeeper = new TestingServer(true)) {
            table.append(measure("ZooKeeper (Curator)", "zookeeper", zookeeper.getConnectString()));
        }
        System.out.println(table);
    }

    private static String measure(String label, String mode, String zookeeperConnect) throws InterruptedException {
        Cluster.Builder builder = Cluster.with(PHILOSOPHERS, mode)
                .philosopher("events-transport", "none")
                .philosopher("think-min-ms", 0).philosopher("think-max-ms", 0)
                .philosopher("eat-min-ms", 20).philosopher("eat-max-ms", 20);
        if (zookeeperConnect != null) {
            builder.philosopher("zookeeper-connect", zookeeperConnect);
        }
        try (Cluster cluster = builder.start()) {
            Thread.sleep(3000); // échauffement
            long[] before = cluster.meals();
            Thread.sleep(SECONDS * 1000L);
            long[] after = cluster.meals();

            long[] meals = new long[PHILOSOPHERS];
            long total = 0;
            long averageWait = 0;
            long maxWait = 0;
            for (int id = 0; id < PHILOSOPHERS; id++) {
                meals[id] = after[id] - before[id];
                total += meals[id];
                PhilosopherLoop.Status status = cluster.state(id);
                averageWait += status.averageWaitMs();
                maxWait = Math.max(maxWait, status.maxWaitMs());
            }
            return String.format(Locale.ROOT, "| %-22s | %9.1f | %9d ms | %9d ms | %-22s |%n", label,
                    (double) total / SECONDS, averageWait / PHILOSOPHERS, maxWait, Arrays.toString(meals));
        }
    }
}
