package fr.cytech.dining.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import fr.cytech.dining.common.EventType;
import fr.cytech.dining.common.PhilosopherEvent;

class EventStoreTest {

    private final EventStore store = new EventStore();

    private static PhilosopherEvent event(int philosopher, EventType type, long lamport, long sequence) {
        return new PhilosopherEvent(philosopher, type, lamport, sequence, 1_000 + lamport, List.of(), "test");
    }

    @Test
    void lHistoriqueEstReconstruitDansLOrdreDesHorlogesMemeSiLesEvenementsArriventEnDesordre() {
        store.add(event(1, EventType.DONE, 9, 3));
        store.add(event(0, EventType.HUNGRY, 2, 1));
        store.add(event(1, EventType.HUNGRY, 4, 1));
        store.add(event(1, EventType.EATING, 5, 2));

        assertThat(store.history()).extracting(PhilosopherEvent::lamportTime).containsExactly(2L, 4L, 5L, 9L);
    }

    @Test
    void aDateEgaleLIdentifiantDuPhilosopheDepartage() {
        store.add(event(2, EventType.HUNGRY, 5, 1));
        store.add(event(0, EventType.HUNGRY, 5, 1));
        store.add(event(1, EventType.HUNGRY, 5, 1));

        assertThat(store.history()).extracting(PhilosopherEvent::philosopherId).containsExactly(0, 1, 2);
    }

    @Test
    void unEvenementRetransmisNEstComptéQuUneFois() {
        PhilosopherEvent event = event(0, EventType.HUNGRY, 1, 1);

        assertThat(store.add(event)).isTrue();
        assertThat(store.add(event)).isFalse();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void laMemoireEstBornee() {
        for (int i = 1; i <= EventStore.MAX_EVENTS + 10; i++) {
            store.add(event(0, EventType.HUNGRY, i, i));
        }
        assertThat(store.size()).isEqualTo(EventStore.MAX_EVENTS);
    }

    @Test
    void clearVideLaMemoire() {
        store.add(event(0, EventType.HUNGRY, 1, 1));
        store.clear();
        assertThat(store.history()).isEmpty();
    }
}
