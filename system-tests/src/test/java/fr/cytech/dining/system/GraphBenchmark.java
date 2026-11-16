package fr.cytech.dining.system;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Section 5.7 : mesure des performances du calcul distribué des degrés en faisant varier la taille
 * du graphe et le nombre de Workers. Tous les services tournent sur la même machine.
 *
 * <p>Lancer avec {@code ./mvnw -pl system-tests -am verify -DexcludedGroups= -Dgroups=benchmark
 * -Dtest=GraphBenchmark -Dsurefire.failIfNoSpecifiedTests=false}.
 */
@Tag("benchmark")
class GraphBenchmark {

    private static final int MAX_WORKERS = 8;
    private static final RestClient HTTP = RestClient.create();

    @Test
    @DisplayName("Temps d'exécution selon la taille du graphe et le nombre de Workers")
    void measure() {
        try (Cluster cluster = Cluster.with(MAX_WORKERS + 1, "spoiled").philosopher("auto-start", false)
                .philosopher("events-transport", "none").start()) {
            StringBuilder table = new StringBuilder();
            table.append(String.format("%n| %10s | %10s | %8s | %14s | %14s | %8s |%n", "Arêtes", "Nœuds", "Workers",
                    "Distribué (ms)", "Séquentiel (ms)", "Rapport"));
            table.append("|---|---|---|---|---|---|\n");
            for (int edges : new int[] {100_000, 500_000, 2_000_000}) {
                int nodes = edges / 10;
                for (int workers : new int[] {1, 2, 4, 8}) {
                    long distributed = Long.MAX_VALUE;
                    long sequential = Long.MAX_VALUE;
                    for (int run = 0; run < 3; run++) { // on garde le meilleur de 3 essais
                        Map<?, ?> response = HTTP.post().uri(cluster.url(0) + "/graph/master/degrees")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("nodes", nodes, "edgeCount", edges, "seed", 42, "workers", workers,
                                        "taskTimeoutMs", 120_000))
                                .retrieve().body(Map.class);
                        if (!Boolean.TRUE.equals(response.get("matchesSequential"))) {
                            throw new IllegalStateException("Résultat distribué faux : " + response);
                        }
                        distributed = Math.min(distributed, ((Number) response.get("distributedMs")).longValue());
                        sequential = Math.min(sequential, ((Number) response.get("sequentialMs")).longValue());
                    }
                    table.append(String.format(Locale.ROOT, "| %,10d | %,10d | %8d | %14d | %14d | %7.1fx |%n", edges,
                            nodes, workers, distributed, sequential,
                            (double) distributed / Math.max(1, sequential)));
                }
            }
            System.out.println(table);
        }
    }
}
