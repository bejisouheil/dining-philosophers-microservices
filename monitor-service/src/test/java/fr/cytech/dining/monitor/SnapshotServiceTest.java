package fr.cytech.dining.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import fr.cytech.dining.common.EventType;
import fr.cytech.dining.common.PhilosopherEvent;
import fr.cytech.dining.monitor.Snapshot.PhilosopherView;

class SnapshotServiceTest {

    private final SnapshotService service = new SnapshotService();
    private final List<PhilosopherEvent> history = new ArrayList<>();
    private long lamport;

    private void emit(int philosopher, EventType type, long wallClock, Integer... forks) {
        lamport++;
        history.add(new PhilosopherEvent(philosopher, type, lamport, lamport, wallClock, List.of(forks), "test"));
    }

    @Test
    void lEtatDeChaquePhilosopheSuitSonDernierEvenement() {
        emit(0, EventType.HUNGRY, 1_000, 0, 1);
        emit(0, EventType.EATING, 1_200, 0, 1);
        emit(1, EventType.HUNGRY, 1_300, 1, 2);
        emit(0, EventType.DONE, 1_500, 0, 1);

        Snapshot snapshot = service.compute(history, 2_000);

        assertThat(snapshot.philosophers()).extracting(PhilosopherView::state).containsExactly("THINKING", "HUNGRY");
        assertThat(snapshot.philosophers().get(0).meals()).isEqualTo(1);
        assertThat(snapshot.philosophers().get(0).maxWaitMs()).isEqualTo(200);
        assertThat(snapshot.philosophers().get(1).hungryForMs()).isEqualTo(700);
        assertThat(snapshot.totalMeals()).isEqualTo(1);
        assertThat(snapshot.totalEvents()).isEqualTo(4);
    }

    @Test
    void deuxPhilosophesQuiMangentAvecLaMemeFourchetteSontSignales() {
        emit(0, EventType.EATING, 1_000, 0, 1);
        emit(1, EventType.EATING, 1_100, 1, 2); // la fourchette 1 est déjà prise par 0

        Snapshot snapshot = service.compute(history, 2_000);

        assertThat(snapshot.violationCount()).isEqualTo(1);
        assertThat(snapshot.violations().get(0).fork()).isEqualTo(1);
        assertThat(snapshot.violations().get(0).holder()).isZero();
        assertThat(snapshot.violations().get(0).intruder()).isEqualTo(1);
    }

    @Test
    void uneFourchetteRendueePuisReprisNEstPasUneViolation() {
        emit(0, EventType.EATING, 1_000, 0, 1);
        emit(0, EventType.DONE, 1_100, 0, 1);
        emit(1, EventType.EATING, 1_200, 1, 2);
        emit(1, EventType.DONE, 1_300, 1, 2);
        emit(0, EventType.EATING, 1_400, 0, 1);

        assertThat(service.compute(history, 2_000).violationCount()).isZero();
    }

    @Test
    void deuxPhilosophesNonVoisinsPeuventMangerEnMemeTemps() {
        emit(0, EventType.EATING, 1_000, 0, 1);
        emit(2, EventType.EATING, 1_000, 2, 3);

        assertThat(service.compute(history, 2_000).violationCount()).isZero();
    }

    @Test
    void siToutLeMondeAFaimDepuisLongtempsUnInterblocageEstSuspecte() {
        emit(0, EventType.HUNGRY, 1_000, 0, 1);
        emit(1, EventType.HUNGRY, 1_000, 1, 2);
        emit(2, EventType.HUNGRY, 1_000, 0, 2);

        Snapshot later = service.compute(history, 1_000 + SnapshotService.HUNGER_THRESHOLD_MS + 1);
        Snapshot soon = service.compute(history, 1_500);

        assertThat(later.suspectedDeadlock()).isTrue();
        assertThat(later.starving()).containsExactly(0, 1, 2);
        assertThat(soon.suspectedDeadlock()).isFalse();
        assertThat(soon.starving()).isEmpty();
    }

    @Test
    void unSeulPhilosopheAffameEstUneFamineMaisPasUnInterblocage() {
        emit(0, EventType.HUNGRY, 1_000, 0, 1);
        emit(1, EventType.EATING, 1_000, 1, 2);

        Snapshot snapshot = service.compute(history, 1_000 + SnapshotService.HUNGER_THRESHOLD_MS + 1);

        assertThat(snapshot.suspectedDeadlock()).isFalse();
        assertThat(snapshot.starving()).containsExactly(0);
    }

    @Test
    void lesEvenementsRecentsSontListesDuPlusRecentAuPlusAncien() {
        emit(0, EventType.HUNGRY, 1_000);
        emit(0, EventType.EATING, 1_100);
        emit(0, EventType.DONE, 1_200);

        Snapshot snapshot = service.compute(history, 2_000);

        assertThat(snapshot.recentEvents()).extracting(PhilosopherEvent::type)
                .containsExactly(EventType.DONE, EventType.EATING, EventType.HUNGRY);
    }

    @Test
    void sansEvenementLaPhotographieEstVide() {
        Snapshot snapshot = service.compute(List.of(), 1_000);

        assertThat(snapshot.philosophers()).isEmpty();
        assertThat(snapshot.suspectedDeadlock()).isFalse();
    }
}
