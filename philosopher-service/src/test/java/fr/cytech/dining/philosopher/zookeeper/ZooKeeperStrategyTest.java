package fr.cytech.dining.philosopher.zookeeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fr.cytech.dining.common.LamportClock;
import fr.cytech.dining.philosopher.core.ForkAssignment;

/**
 * Tests de la solution ZooKeeper contre un vrai serveur ZooKeeper lancé dans la JVM du test
 * (curator-test). Chaque philosophe a sa propre connexion, comme autant de microservices.
 */
@Timeout(60)
class ZooKeeperStrategyTest {

    private static final int COUNT = 5;

    private TestingServer zookeeper;
    private final List<CuratorFramework> clients = new ArrayList<>();

    @BeforeEach
    void startZooKeeper() throws Exception {
        zookeeper = new TestingServer(true);
    }

    @AfterEach
    void stopZooKeeper() throws Exception {
        clients.forEach(CuratorFramework::close);
        zookeeper.close();
    }

    private CuratorFramework newClient() {
        CuratorFramework client = CuratorFrameworkFactory.newClient(zookeeper.getConnectString(), 5_000, 5_000,
                new RetryOneTime(100));
        client.start();
        clients.add(client);
        return client;
    }

    private ZooKeeperStrategy philosopher(int id) {
        return new ZooKeeperStrategy(newClient(), ForkAssignment.forksFor(id, COUNT), new LamportClock());
    }

    @Test
    void unPhilosopheSeulPrendEtRendSesDeuxFourchettes() throws Exception {
        ZooKeeperStrategy philosopher = philosopher(0);

        philosopher.acquire();
        philosopher.release();
        philosopher.acquire(); // les verrous ont bien été rendus
        philosopher.release();

        assertThat(philosopher.forks()).containsExactly(0, 1);
    }

    @Test
    @DisplayName("Concurrence : 5 philosophes, jamais deux sur la même fourchette, aucun interblocage")
    void exclusionMutuelleEtAbsenceDInterblocage() throws Exception {
        int mealsEach = 15;
        AtomicIntegerArray users = new AtomicIntegerArray(COUNT);
        AtomicInteger violations = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(COUNT);
        ExecutorService pool = Executors.newFixedThreadPool(COUNT);
        try {
            for (int id = 0; id < COUNT; id++) {
                ZooKeeperStrategy philosopher = philosopher(id);
                pool.execute(() -> {
                    try {
                        for (int meal = 0; meal < mealsEach; meal++) {
                            philosopher.acquire();
                            for (int fork : philosopher.forks()) {
                                if (users.incrementAndGet(fork) > 1) {
                                    violations.incrementAndGet();
                                }
                            }
                            Thread.sleep(2);
                            for (int fork : philosopher.forks()) {
                                users.decrementAndGet(fork);
                            }
                            philosopher.release();
                        }
                        done.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(done.await(45, TimeUnit.SECONDS)).as("tous les repas ont eu lieu").isTrue();
            assertThat(violations.get()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Résilience : si un philosophe tombe en panne en mangeant, ZooKeeper libère ses fourchettes")
    void unePanneLibereLesVerrous() throws Exception {
        CuratorFramework doomedClient = newClient();
        ZooKeeperStrategy doomed = new ZooKeeperStrategy(doomedClient, ForkAssignment.forksFor(0, COUNT),
                new LamportClock());
        ZooKeeperStrategy neighbour = philosopher(1); // partage la fourchette 1
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            doomed.acquire(); // le philosophe 0 mange...
            Future<?> waiting = pool.submit(() -> {
                neighbour.acquire();
                neighbour.release();
                return null;
            });
            Thread.sleep(300);
            assertThat(waiting).as("le voisin attend tant que 0 tient la fourchette 1").isNotDone();

            doomedClient.close(); // ... et tombe en panne sans rien rendre

            waiting.get(15, TimeUnit.SECONDS); // la session expirée, le verrou éphémère disparaît
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("L'horloge de Lamport voyage par ZooKeeper : qui prend une fourchette est daté après qui l'a rendue")
    void lHorlogeTraverseZooKeeper() throws Exception {
        LamportClock clockOfZero = new LamportClock();
        LamportClock clockOfOne = new LamportClock();
        ZooKeeperStrategy zero = new ZooKeeperStrategy(newClient(), ForkAssignment.forksFor(0, COUNT), clockOfZero);
        ZooKeeperStrategy one = new ZooKeeperStrategy(newClient(), ForkAssignment.forksFor(1, COUNT), clockOfOne);
        clockOfZero.update(1_000); // le philosophe 0 a une horloge très en avance

        zero.acquire();
        zero.release();
        long releasedAt = clockOfZero.current();
        one.acquire(); // partage la fourchette 1 avec le philosophe 0

        assertThat(clockOfOne.current()).isGreaterThan(releasedAt);
        one.release();
    }

    @Test
    void lesFourchettesDoiventEtreTrieesPourGarantirLOrdreDePrise() {
        CuratorFramework client = newClient();
        assertThatThrownBy(() -> new ZooKeeperStrategy(client, List.of(4, 0), new LamportClock()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZooKeeperStrategy(client, List.of(1), new LamportClock()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Résilience : ZooKeeper arrêté, le philosophe obtient une erreur au lieu de manger sans verrou")
    void zookeeperIndisponible() throws Exception {
        ZooKeeperStrategy philosopher = philosopher(2);
        zookeeper.stop();

        assertThatThrownBy(philosopher::acquire).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ZooKeeper indisponible");
    }
}
