package fr.cytech.dining.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Section 5 de bout en bout : le philosophe 0 joue le Master, les philosophes 1 à 3 les Workers,
 * et tout passe par HTTP entre de vrais microservices.
 */
class GraphSystemTest {

    private static Cluster cluster;
    private static final RestClient HTTP = RestClient.create();

    @BeforeAll
    static void start() {
        cluster = Cluster.with(4, "spoiled").philosopher("auto-start", false).philosopher("events-transport", "none")
                .start();
    }

    @AfterAll
    static void stop() {
        cluster.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> post(int philosopher, String path, Object body) {
        return HTTP.post().uri(cluster.url(philosopher) + path).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(Map.class);
    }

    @Test
    @DisplayName("Degrés : 3 Workers en HTTP, résultat identique au calcul séquentiel")
    void degresDistribues() {
        Map<String, Object> response = post(0, "/graph/master/degrees",
                Map.of("nodes", 2_000, "edgeCount", 50_000, "seed", 7));

        assertThat(response).containsEntry("workers", 3).containsEntry("partitions", 3)
                .containsEntry("consistent", true).containsEntry("matchesSequential", true)
                .containsEntry("sumOfDegrees", 100_000).containsEntry("retries", 0);
    }

    @Test
    @DisplayName("Degrés : un Worker en panne, la tâche est relancée ailleurs et le résultat reste exact")
    void degresAvecUnWorkerEnPanne() {
        HTTP.post().uri(cluster.url(2) + "/graph/worker/chaos?failures=1").retrieve().toBodilessEntity();

        Map<String, Object> response = post(0, "/graph/master/degrees",
                Map.of("nodes", 500, "edgeCount", 10_000, "seed", 3));

        assertThat(response).containsEntry("matchesSequential", true).containsEntry("consistent", true);
        assertThat((Integer) response.get("retries")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Degrés : un Worker trop lent, le Master n'attend pas et ne compte rien en double")
    void degresAvecUnWorkerLent() {
        HTTP.post().uri(cluster.url(1) + "/graph/worker/chaos?delayMs=1500").retrieve().toBodilessEntity();
        try {
            Map<String, Object> response = post(0, "/graph/master/degrees",
                    Map.of("nodes", 500, "edgeCount", 10_000, "seed", 4, "taskTimeoutMs", 300));

            assertThat(response).containsEntry("matchesSequential", true).containsEntry("sumOfDegrees", 20_000);
            assertThat((Integer) response.get("retries")).isGreaterThanOrEqualTo(1);
        } finally {
            HTTP.post().uri(cluster.url(1) + "/graph/worker/chaos").retrieve().toBodilessEntity();
        }
    }

    @Test
    @DisplayName("Accessibilité : parcours en largeur distribué, même réponse que le parcours séquentiel")
    void accessibiliteDistribuee() {
        Map<String, Object> reachable = post(0, "/graph/master/reachability",
                Map.of("nodes", 6, "edges", new int[] {0, 1, 1, 2, 2, 3, 4, 5}, "source", 0, "target", 3));
        Map<String, Object> unreachable = post(0, "/graph/master/reachability",
                Map.of("nodes", 6, "edges", new int[] {0, 1, 1, 2, 2, 3, 4, 5}, "source", 0, "target", 5));
        Map<String, Object> random = post(0, "/graph/master/reachability",
                Map.of("nodes", 3_000, "edgeCount", 9_000, "seed", 11, "source", 0, "target", 2_999));

        assertThat(reachable).containsEntry("reachable", true).containsEntry("distance", 3);
        assertThat(unreachable).containsEntry("reachable", false).containsEntry("distance", -1);
        assertThat(random).containsEntry("matchesSequential", true);
    }

    @Test
    @DisplayName("N'importe quel philosophe peut jouer le Master")
    void unAutreMaster() {
        Map<String, Object> response = post(2, "/graph/master/degrees",
                Map.of("nodes", 100, "edgeCount", 1_000, "seed", 1));

        assertThat(response).containsEntry("matchesSequential", true).containsEntry("workers", 3);
    }
}
