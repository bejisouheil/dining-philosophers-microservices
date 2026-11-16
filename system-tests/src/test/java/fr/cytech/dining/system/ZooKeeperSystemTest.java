package fr.cytech.dining.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Section 3.4 de bout en bout : cinq microservices philosophes coordonnés par un vrai serveur
 * ZooKeeper lancé dans un conteneur Docker (Testcontainers). Ignoré si Docker n'est pas disponible.
 */
@Testcontainers(disabledWithoutDocker = true)
class ZooKeeperSystemTest {

    @Container
    static final GenericContainer<?> ZOOKEEPER = new GenericContainer<>(DockerImageName.parse("zookeeper:3.9"))
            .withExposedPorts(2181);

    @Test
    @DisplayName("Section 3.4 : verrous ZooKeeper, aucune violation et tout le monde mange")
    void verrousZooKeeper() {
        String connect = ZOOKEEPER.getHost() + ":" + ZOOKEEPER.getMappedPort(2181);
        try (Cluster cluster = Cluster.with(5, "zookeeper").philosopher("zookeeper-connect", connect).start()) {
            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(() -> assertThat(cluster.minMeals()).isGreaterThanOrEqualTo(5));

            assertThat(cluster.snapshot().violationCount()).isZero();
        }
    }
}
