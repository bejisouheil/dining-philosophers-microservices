package fr.cytech.dining.supervisor.forks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Sections 3.1 et 3.2 : mêmes scénarios sans verrou puis avec verrou. */
class PairForkTableTest {

    private static final int FORKS = 5;

    static Stream<Function<RaceWindow, PairForkTable>> variants() {
        return Stream.of(
                window -> new UnsafePairForkTable(FORKS, window),
                window -> new LockedPairForkTable(FORKS, window));
    }

    // --- Comportement commun aux deux variantes, en séquentiel ---

    @ParameterizedTest
    @MethodSource("variants")
    void lesDeuxFourchettesSontReserveesEnsemble(Function<RaceWindow, PairForkTable> factory) {
        PairForkTable table = factory.apply(RaceWindow.NONE);

        assertThat(table.acquirePair(0, 1, 0)).isTrue();
        assertThat(table.owners()).containsEntry(0, 0).containsEntry(1, 0);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void siUneSeuleEstPriseRienNEstReserve(Function<RaceWindow, PairForkTable> factory) {
        PairForkTable table = factory.apply(RaceWindow.NONE);
        table.acquirePair(0, 1, 0);

        assertThat(table.acquirePair(1, 2, 1)).isFalse(); // la 1 est prise
        assertThat(table.owners()).doesNotContainKey(2);  // donc la 2 n'est pas réservée non plus
    }

    @ParameterizedTest
    @MethodSource("variants")
    void deuxPhilosophesNonVoisinsMangentEnMemeTemps(Function<RaceWindow, PairForkTable> factory) {
        PairForkTable table = factory.apply(RaceWindow.NONE);

        assertThat(table.acquirePair(0, 1, 0)).isTrue();
        assertThat(table.acquirePair(2, 3, 2)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("variants")
    void seulLeDetenteurLibereSesFourchettes(Function<RaceWindow, PairForkTable> factory) {
        PairForkTable table = factory.apply(RaceWindow.NONE);
        table.acquirePair(0, 1, 0);

        table.releasePair(0, 1, 3); // un tiers : sans effet
        assertThat(table.owners()).hasSize(2);

        table.releasePair(0, 1, 0);
        assertThat(table.owners()).isEmpty();
        assertThat(table.acquirePair(1, 2, 1)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("variants")
    void lesDemandesInvalidesSontRefusees(Function<RaceWindow, PairForkTable> factory) {
        PairForkTable table = factory.apply(RaceWindow.NONE);

        assertThatThrownBy(() -> table.acquirePair(0, FORKS, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> table.acquirePair(2, 2, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("variants")
    @DisplayName("Pas d'interblocage : tout ou rien, donc personne ne garde une fourchette en attendant")
    void pasDInterblocage(Function<RaceWindow, PairForkTable> factory) throws Exception {
        PairForkTable table = factory.apply(RaceWindow.NONE);
        int mealsEach = 200;
        ExecutorService pool = Executors.newFixedThreadPool(FORKS);
        CountDownLatch done = new CountDownLatch(FORKS);
        try {
            for (int id = 0; id < FORKS; id++) {
                int philosopher = id;
                pool.execute(() -> {
                    int first = philosopher;
                    int second = (philosopher + 1) % FORKS;
                    for (int meal = 0; meal < mealsEach; meal++) {
                        while (!table.acquirePair(first, second, philosopher)) {
                            Thread.onSpinWait();
                        }
                        table.releasePair(first, second, philosopher);
                    }
                    done.countDown();
                });
            }
            assertThat(done.await(20, TimeUnit.SECONDS)).as("tous les philosophes ont fini leurs repas").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Ce qui distingue les deux variantes : la concurrence ---

    @Test
    @DisplayName("3.1 sans verrou : deux voisins obtiennent la fourchette qu'ils partagent")
    void sansVerrouDeuxVoisinsObtiennentLaMemeFourchette() throws Exception {
        CyclicBarrier bothPassedTheCheck = new CyclicBarrier(2);
        PairForkTable table = new UnsafePairForkTable(FORKS, () -> {
            try {
                bothPassedTheCheck.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> philosopher0 = pool.submit(() -> table.acquirePair(0, 1, 0));
            Future<Boolean> philosopher1 = pool.submit(() -> table.acquirePair(1, 2, 1));

            assertThat(philosopher0.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(philosopher1.get(5, TimeUnit.SECONDS)).isTrue(); // la fourchette 1 est attribuée deux fois
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("3.1 sans verrou : sous charge, une fourchette est utilisée par deux philosophes à la fois")
    void sansVerrouLExclusionMutuelleEstVioleeSousCharge() throws Exception {
        PairForkTable table = new UnsafePairForkTable(FORKS, RaceWindow.ofMillis(1));

        assertThat(countViolations(table, 8, Duration.ofSeconds(2))).isGreaterThan(0);
    }

    @Test
    @DisplayName("3.2 avec verrou : sous la même charge, jamais deux philosophes sur une fourchette")
    void avecVerrouLExclusionMutuelleTientSousCharge() throws Exception {
        PairForkTable table = new LockedPairForkTable(FORKS, RaceWindow.ofMillis(1));

        assertThat(countViolations(table, 8, Duration.ofSeconds(2))).isZero();
    }

    @Test
    @DisplayName("3.2 avec verrou : stress sans pause, 16 threads, aucune violation")
    void avecVerrouStressSansPause() throws Exception {
        PairForkTable table = new LockedPairForkTable(FORKS, RaceWindow.NONE);

        assertThat(countViolations(table, 16, Duration.ofSeconds(2))).isZero();
    }

    /**
     * Lance {@code threads} philosophes qui demandent et rendent leurs fourchettes en boucle.
     * Chaque philosophe servi incrémente un compteur par fourchette : s'il le trouve déjà à 1,
     * c'est que deux philosophes utilisent la même fourchette en même temps.
     */
    private static int countViolations(PairForkTable table, int threads, Duration duration) throws Exception {
        AtomicIntegerArray users = new AtomicIntegerArray(FORKS);
        AtomicInteger violations = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long deadline = System.nanoTime() + duration.toNanos();
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int philosopher = t % FORKS;
            pool.execute(() -> {
                int first = philosopher;
                int second = (philosopher + 1) % FORKS;
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                while (System.nanoTime() < deadline) {
                    // Chaque thread joue un philosophe distinct du point de vue du superviseur.
                    int identity = (int) Thread.currentThread().threadId();
                    if (table.acquirePair(first, second, identity)) {
                        int usersOfFirst = users.incrementAndGet(first);
                        int usersOfSecond = users.incrementAndGet(second);
                        if (usersOfFirst > 1 || usersOfSecond > 1) {
                            violations.incrementAndGet();
                        }
                        Thread.yield(); // le temps d'un « repas »
                        users.decrementAndGet(first);
                        users.decrementAndGet(second);
                        table.releasePair(first, second, identity);
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(duration.toSeconds() + 10, TimeUnit.SECONDS)).isTrue();
        return violations.get();
    }
}
