package fr.cytech.dining.supervisor.forks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Jeu de tests qui recense les failles du superviseur naïf (section 2). Les tests du bloc
 * « Failles » réussissent quand la faille se produit : ils documentent le comportement fautif.
 */
class NaiveForkTableTest {

    private static final int FORKS = 5;

    /** Fenêtre de course qui attend que {@code parties} threads aient tous passé le test « libre ? ». */
    private static RaceWindow meetingPoint(int parties) {
        CyclicBarrier barrier = new CyclicBarrier(parties);
        return () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @Nested
    @DisplayName("Fonctionnement nominal (un seul philosophe à la fois)")
    class Nominal {

        private final NaiveForkTable table = new NaiveForkTable(FORKS, RaceWindow.NONE);

        @Test
        void uneFourchetteLibreEstAttribuee() {
            assertThat(table.isAvailable(2)).isTrue();
            assertThat(table.acquire(2, 1)).isTrue();
            assertThat(table.isAvailable(2)).isFalse();
            assertThat(table.owners()).containsEntry(2, 1);
        }

        @Test
        void uneFourchettePriseEstRefusee() {
            table.acquire(2, 1);
            assertThat(table.acquire(2, 3)).isFalse();
        }

        @Test
        void uneFourchetteLibereeRedevientDisponible() {
            table.acquire(2, 1);
            table.release(2, 1);
            assertThat(table.isAvailable(2)).isTrue();
        }

        @Test
        void uneFourchetteInconnueEstRefusee() {
            assertThatThrownBy(() -> table.acquire(FORKS, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> table.isAvailable(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Failles recensées")
    class Failles {

        @Test
        @DisplayName("Faille 1 : deux philosophes obtiennent la même fourchette (test puis prise non atomiques)")
        void doubleAttribution() throws Exception {
            NaiveForkTable table = new NaiveForkTable(FORKS, meetingPoint(2));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> first = pool.submit(() -> table.acquire(1, 0));
                Future<Boolean> second = pool.submit(() -> table.acquire(1, 1));

                assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(5, TimeUnit.SECONDS)).isTrue(); // les deux croient l'avoir
                assertThat(table.owners()).hasSize(1); // alors qu'un seul détenteur est enregistré
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("Faille 2 : la réponse « disponible » est déjà périmée quand la demande arrive")
        void disponibilitePerimee() {
            NaiveForkTable table = new NaiveForkTable(FORKS, RaceWindow.NONE);

            boolean seenAvailable = table.isAvailable(3); // le philosophe 0 interroge
            table.acquire(3, 4);                          // entre-temps le philosophe 4 la prend
            boolean granted = table.acquire(3, 0);        // la demande de 0 arrive trop tard

            assertThat(seenAvailable).isTrue();
            assertThat(granted).isFalse();
        }

        @Test
        @DisplayName("Faille 3 : un philosophe peut libérer la fourchette d'un autre")
        void liberationParUnTiers() {
            NaiveForkTable table = new NaiveForkTable(FORKS, RaceWindow.NONE);
            table.acquire(2, 1);

            table.release(2, 4); // le philosophe 4 n'est pas le détenteur

            assertThat(table.isAvailable(2)).isTrue();
            assertThat(table.acquire(2, 3)).isTrue(); // 1 et 3 pensent tous deux tenir la fourchette 2
        }

        @Test
        @DisplayName("Faille 4 : interblocage, chacun tient une fourchette et attend la suivante")
        void interblocage() {
            NaiveForkTable table = new NaiveForkTable(FORKS, RaceWindow.NONE);

            for (int philosopher = 0; philosopher < FORKS; philosopher++) {
                assertThat(table.acquire(philosopher, philosopher)).isTrue(); // sa première fourchette
            }

            // Plus aucune fourchette libre : personne n'obtiendra jamais la seconde.
            for (int philosopher = 0; philosopher < FORKS; philosopher++) {
                int second = (philosopher + 1) % FORKS;
                assertThat(table.isAvailable(second)).isFalse();
                assertThat(table.acquire(second, philosopher)).isFalse();
            }
            assertThat(table.owners()).hasSize(FORKS);
        }

        @Test
        @DisplayName("Faille 5 : un philosophe peut accaparer toutes les fourchettes (famine des autres)")
        void accaparement() {
            NaiveForkTable table = new NaiveForkTable(FORKS, RaceWindow.NONE);

            for (int fork = 0; fork < FORKS; fork++) {
                assertThat(table.acquire(fork, 0)).isTrue(); // aucune règle « deux fourchettes, les siennes »
            }

            assertThat(table.owners().values()).containsOnly(0);
            assertThat(table.acquire(1, 1)).isFalse();
        }
    }
}
