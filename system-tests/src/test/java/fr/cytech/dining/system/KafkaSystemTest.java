package fr.cytech.dining.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import fr.cytech.dining.monitor.Snapshot;

/**
 * Section 6 de bout en bout : les événements passent par un vrai Kafka lancé dans un conteneur
 * Docker (Testcontainers). Ignoré si Docker n'est pas disponible.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaSystemTest {

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    private static Cluster.Builder tokenClusterOverKafka() {
        return Cluster.with(4, "token")
                .philosopher("events-transport", "kafka")
                .argument("--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers())
                .argument("--monitor.kafka.enabled=true");
    }

    @Test
    @DisplayName("Les événements publiés dans Kafka arrivent au moniteur")
    void evenementsParKafka() {
        try (Cluster cluster = tokenClusterOverKafka().start()) {
            await().atMost(PATIENCE).untilAsserted(() -> {
                Snapshot snapshot = cluster.snapshot();
                assertThat(snapshot.philosophers()).hasSize(4);
                assertThat(snapshot.totalMeals()).isGreaterThanOrEqualTo(12);
            });
            assertThat(cluster.snapshot().violationCount()).isZero();
        }
    }

    @Test
    @DisplayName("Moniteur redémarré : il reconstruit tout son historique depuis Kafka, panne comprise")
    void leMoniteurRattrapeSonRetard() throws InterruptedException {
        try (Cluster cluster = tokenClusterOverKafka().start()) {
            await().atMost(PATIENCE).until(() -> cluster.snapshot().totalMeals() >= 8);

            cluster.killMonitor();
            Thread.sleep(2000); // les philosophes continuent de manger et de publier dans Kafka
            long mealsDuringOutage = cluster.meals()[0]; // repas réellement pris par le philosophe 0
            cluster.startMonitor(); // nouveau moniteur, mémoire vide

            await().atMost(PATIENCE).untilAsserted(() -> {
                Snapshot snapshot = cluster.snapshot();
                assertThat(snapshot.philosophers()).hasSize(4);
                assertThat(snapshot.philosophers().get(0).meals())
                        .as("les repas d'avant et de pendant la panne sont retrouvés")
                        .isGreaterThanOrEqualTo(mealsDuringOutage);
            });
        }
    }
}
