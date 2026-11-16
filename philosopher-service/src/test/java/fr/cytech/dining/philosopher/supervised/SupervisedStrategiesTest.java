package fr.cytech.dining.philosopher.supervised;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SupervisedStrategiesTest {

    private final SupervisorClient supervisor = mock(SupervisorClient.class);

    @Test
    void naifInterrogePuisPrendLaPremiereFourchettePuisLaSeconde() throws InterruptedException {
        when(supervisor.isAvailable(anyInt())).thenReturn(true);
        when(supervisor.acquire(anyInt())).thenReturn(true);
        NaiveSupervisedStrategy strategy = new NaiveSupervisedStrategy(supervisor, 4, 5, 1);

        strategy.acquire();

        InOrder order = inOrder(supervisor);
        order.verify(supervisor).isAvailable(4);
        order.verify(supervisor).acquire(4);
        order.verify(supervisor).isAvailable(0); // le dernier philosophe boucle sur la fourchette 0
        order.verify(supervisor).acquire(0);
        assertThat(strategy.forks()).containsExactly(4, 0);
    }

    @Test
    void naifAttendTantQueLaFourchetteEstPrise() throws InterruptedException {
        when(supervisor.isAvailable(1)).thenReturn(false, false, true);
        when(supervisor.isAvailable(2)).thenReturn(true);
        when(supervisor.acquire(anyInt())).thenReturn(true);
        NaiveSupervisedStrategy strategy = new NaiveSupervisedStrategy(supervisor, 1, 5, 1);

        strategy.acquire();

        verify(supervisor, times(3)).isAvailable(1);
        verify(supervisor, times(1)).acquire(1);
    }

    @Test
    void naifReessaieSiLaFourchetteEstPriseEntreLaQuestionEtLaDemande() throws InterruptedException {
        when(supervisor.isAvailable(anyInt())).thenReturn(true);
        when(supervisor.acquire(1)).thenReturn(false, true); // disponibilité périmée la première fois
        when(supervisor.acquire(2)).thenReturn(true);
        NaiveSupervisedStrategy strategy = new NaiveSupervisedStrategy(supervisor, 1, 5, 1);

        strategy.acquire();

        verify(supervisor, times(2)).acquire(1);
    }

    @Test
    void naifGardeSaPremiereFourchettePendantQuIlAttendLaSeconde() throws InterruptedException {
        when(supervisor.isAvailable(1)).thenReturn(true);
        when(supervisor.acquire(1)).thenReturn(true);
        when(supervisor.isAvailable(2)).thenReturn(false, false, false, true);
        when(supervisor.acquire(2)).thenReturn(true);
        NaiveSupervisedStrategy strategy = new NaiveSupervisedStrategy(supervisor, 1, 5, 1);

        strategy.acquire();

        verify(supervisor, never()).release(1); // « tenir et attendre » : la condition de l'interblocage
    }

    @Test
    void naifRendLesDeuxFourchettes() {
        NaiveSupervisedStrategy strategy = new NaiveSupervisedStrategy(supervisor, 1, 5, 1);

        strategy.release();

        verify(supervisor).release(1);
        verify(supervisor).release(2);
    }

    @Test
    void paireDemandeLesDeuxFourchettesEnUneFoisEtReessaieEnCasDeRefus() throws InterruptedException {
        when(supervisor.acquirePair("locked", 2, 3)).thenReturn(false, false, true);
        PairSupervisedStrategy strategy = new PairSupervisedStrategy(supervisor, "locked", 2, 5, 1);

        strategy.acquire();
        strategy.release();

        verify(supervisor, times(3)).acquirePair("locked", 2, 3);
        verify(supervisor).releasePair("locked", 2, 3);
        verify(supervisor, never()).acquire(anyInt()); // jamais de fourchette isolée
    }
}
