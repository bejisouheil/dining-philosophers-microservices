package fr.cytech.dining.philosopher.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.IntFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fr.cytech.dining.philosopher.core.ForkAssignment;

/**
 * Tests d'intégration, de concurrence et de résilience du protocole par jetons : plusieurs
 * philosophes, chacun dans son thread, reliés par un faux réseau ({@link InMemoryNetwork}).
 *
 * <p>Propriétés vérifiées à chaque scénario :
 * <ul>
 *   <li><strong>sûreté</strong> : une fourchette n'est jamais utilisée par deux philosophes à la
 *       fois (compteur par fourchette) ;</li>
 *   <li><strong>vivacité</strong> : tous les repas prévus ont lieu avant le délai du test, donc ni
 *       interblocage ni famine sur l'exécution observée.</li>
 * </ul>
 */
@Timeout(60)
class TokenProtocolSimulationTest {

    /** Résultat d'une simulation. */
    private record Outcome(boolean finished, int violations, long[] meals, long messagesDelivered,
                           int tokensInTheSystem) {
    }

    /**
     * Fait manger {@code mealsEach} fois chaque philosophe. {@code forksOf} choisit les deux
     * fourchettes demandées ; {@code simultaneous} aligne le premier départ de tous sur une barrière.
     */
    private static Outcome simulate(InMemoryNetwork network, int mealsEach, IntFunction<List<Integer>> forksOf,
                                    boolean simultaneous, int timeoutSeconds) throws Exception {
        int count = network.nodes().size();
        AtomicIntegerArray users = new AtomicIntegerArray(count);
        AtomicInteger violations = new AtomicInteger();
        AtomicLongArray meals = new AtomicLongArray(count);
        CyclicBarrier start = new CyclicBarrier(count);
        CountDownLatch done = new CountDownLatch(count);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            for (TokenNode node : network.nodes()) {
                pool.execute(() -> {
                    try {
                        if (simultaneous) {
                            start.await();
                        }
                        for (int meal = 0; meal < mealsEach; meal++) {
                            List<Integer> forks = forksOf.apply(node.id());
                            node.request(forks).get();
                            for (int fork : forks) {
                                if (users.incrementAndGet(fork) > 1) {
                                    violations.incrementAndGet();
                                }
                            }
                            Thread.yield(); // le repas
                            for (int fork : forks) {
                                users.decrementAndGet(fork);
                            }
                            meals.incrementAndGet(node.id());
                            node.release();
                        }
                        done.countDown();
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            boolean finished = done.await(timeoutSeconds, TimeUnit.SECONDS);
            long[] mealCounts = new long[count];
            for (int i = 0; i < count; i++) {
                mealCounts[i] = meals.get(i);
            }
            Thread.sleep(100); // laisse arriver les derniers jetons en transit
            int tokens = network.nodes().stream().mapToInt(node -> node.snapshot().heldTokens().size()).sum();
            return new Outcome(finished, violations.get(), mealCounts, network.delivered.get(), tokens);
        } finally {
            pool.shutdownNow();
        }
    }

    private static IntFunction<List<Integer>> neighbours(int count) {
        return id -> ForkAssignment.forksFor(id, count);
    }

    @Test
    @DisplayName("Intégration : 5 philosophes, réseau parfait, 200 repas chacun")
    void cinqPhilosophesReseauParfait() throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork(5, 1, 0, 0, 0)) {
            Outcome outcome = simulate(network, 200, neighbours(5), false, 30);

            assertThat(outcome.finished()).as("aucun interblocage ni famine").isTrue();
            assertThat(outcome.violations()).as("exclusion mutuelle").isZero();
            assertThat(outcome.meals()).containsOnly(200L);
            assertThat(outcome.tokensInTheSystem()).as("un jeton par fourchette, ni perdu ni dupliqué").isEqualTo(5);
        }
    }

    @Test
    @DisplayName("Concurrence : tous les philosophes demandent exactement en même temps")
    void demandesSimultanees() throws Exception {
        for (int round = 0; round < 20; round++) {
            try (InMemoryNetwork network = new InMemoryNetwork(5, round, 2, 0, 0)) {
                Outcome outcome = simulate(network, 3, neighbours(5), true, 20);

                assertThat(outcome.finished()).as("tour %d : tout le monde finit par manger", round).isTrue();
                assertThat(outcome.violations()).isZero();
            }
        }
    }

    @Test
    @DisplayName("Cas limite : 2 philosophes se disputent les 2 mêmes fourchettes")
    void deuxPhilosophes() throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork(2, 7, 1, 0, 0)) {
            Outcome outcome = simulate(network, 100, neighbours(2), true, 30);

            assertThat(outcome.finished()).isTrue();
            assertThat(outcome.violations()).isZero();
        }
    }

    @Test
    @DisplayName("Sans contrainte de voisinage : chaque demande porte sur deux fourchettes quelconques")
    void fourchettesQuelconques() throws Exception {
        int count = 6;
        ThreadLocal<Random> random = ThreadLocal.withInitial(() -> new Random(Thread.currentThread().threadId()));
        IntFunction<List<Integer>> anyTwoForks = id -> {
            int first = random.get().nextInt(count);
            int second = (first + 1 + random.get().nextInt(count - 1)) % count;
            return List.of(first, second);
        };
        try (InMemoryNetwork network = new InMemoryNetwork(count, 3, 2, 0, 0)) {
            Outcome outcome = simulate(network, 100, anyTwoForks, true, 40);

            assertThat(outcome.finished()).as("aucun interblocage même avec des paires arbitraires").isTrue();
            assertThat(outcome.violations()).isZero();
            assertThat(outcome.tokensInTheSystem()).isEqualTo(count);
        }
    }

    @Test
    @DisplayName("Résilience : délais aléatoires jusqu'à 10 ms et messages désordonnés")
    void delaisDeReponse() throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork(5, 11, 10, 0, 0)) {
            Outcome outcome = simulate(network, 30, neighbours(5), true, 40);

            assertThat(outcome.finished()).isTrue();
            assertThat(outcome.violations()).isZero();
        }
    }

    @Test
    @DisplayName("Résilience : 20 % des demandes et libérations perdues, rattrapées par retransmission")
    void messagesPerdus() throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork(5, 21, 3, 0.20, 0)) {
            Outcome outcome = simulate(network, 30, neighbours(5), true, 50);

            assertThat(network.lost.get()).as("des messages ont réellement été perdus").isGreaterThan(0);
            assertThat(outcome.finished()).as("le système progresse malgré les pertes").isTrue();
            assertThat(outcome.violations()).isZero();
            assertThat(outcome.tokensInTheSystem()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("Résilience : 30 % des messages livrés en double, jetons compris")
    void messagesDupliques() throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork(5, 31, 3, 0, 0.30)) {
            Outcome outcome = simulate(network, 50, neighbours(5), true, 40);

            assertThat(network.duplicated.get()).isGreaterThan(0);
            assertThat(outcome.finished()).isTrue();
            assertThat(outcome.violations()).as("un jeton dupliqué ne crée pas de second jeton").isZero();
            assertThat(outcome.tokensInTheSystem()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("Résilience : pertes, doublons et délais en même temps")
    void reseauHostile() throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork(5, 41, 8, 0.15, 0.15)) {
            Outcome outcome = simulate(network, 25, neighbours(5), true, 50);

            assertThat(outcome.finished()).isTrue();
            assertThat(outcome.violations()).isZero();
            assertThat(outcome.tokensInTheSystem()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("Équité : sous forte contention, personne n'est privé de fourchettes")
    void equite() throws Exception {
        int count = 5;
        try (InMemoryNetwork network = new InMemoryNetwork(count, 51, 1, 0, 0)) {
            AtomicLongArray meals = new AtomicLongArray(count);
            AtomicBoolean running = new AtomicBoolean(true);
            ExecutorService pool = Executors.newFixedThreadPool(count);
            for (TokenNode node : network.nodes()) {
                pool.execute(() -> {
                    try {
                        while (running.get()) { // affamé en permanence : aucune pause pour penser
                            node.request(ForkAssignment.forksFor(node.id(), count)).get();
                            // Un repas dure plus longtemps qu'un message ne voyage (1 ms ici), comme en
                            // vrai. Sinon un philosophe remange plusieurs fois avant même que la demande
                            // de son voisin n'arrive : il ne lèse personne de connu, mais fausse la mesure.
                            Thread.sleep(3);
                            meals.incrementAndGet(node.id());
                            node.release();
                        }
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            Thread.sleep(3000);
            running.set(false);
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            long min = Long.MAX_VALUE;
            long max = 0;
            for (int i = 0; i < count; i++) {
                min = Math.min(min, meals.get(i));
                max = Math.max(max, meals.get(i));
            }
            assertThat(min).as("tout le monde mange").isGreaterThan(0);
            assertThat((double) min / max).as("écart entre le moins bien et le mieux servi (min=%d, max=%d)", min, max)
                    .isGreaterThan(0.8);
        }
    }

    @Test
    @DisplayName("Le protocole coûte un nombre de messages borné par repas")
    void coutEnMessages() throws Exception {
        int count = 5;
        int mealsEach = 100;
        try (InMemoryNetwork network = new InMemoryNetwork(count, 61, 0, 0, 0)) {
            Outcome outcome = simulate(network, mealsEach, neighbours(count), true, 30);

            assertThat(outcome.finished()).isTrue();
            double perMeal = (double) outcome.messagesDelivered() / (count * mealsEach);
            // Par repas : (N-1) demandes + (N-1) libérations + au plus quelques transferts de jeton.
            assertThat(perMeal).isBetween(2.0 * (count - 1), 2.0 * (count - 1) + 6);
        }
    }
}
