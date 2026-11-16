package fr.cytech.dining.philosopher.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import fr.cytech.dining.common.EventType;
import fr.cytech.dining.common.LamportClock;
import fr.cytech.dining.common.PhilosopherEvent;
import fr.cytech.dining.philosopher.events.EventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PhilosopherLoopTest {

    private static final PhilosopherLoop.Timing FAST = new PhilosopherLoop.Timing(0, 0, 0, 0);

    private DiningStrategy strategy;
    private EventPublisher publisher;
    private LamportClock clock;
    private PhilosopherLoop loop;

    @BeforeEach
    void setUp() {
        strategy = mock(DiningStrategy.class);
        when(strategy.forks()).thenReturn(List.of(3, 4));
        publisher = mock(EventPublisher.class);
        clock = new LamportClock();
        loop = new PhilosopherLoop(3, "test", strategy, publisher, clock, FAST, new SimpleMeterRegistry());
    }

    @Test
    void unCycleEmetHungryEatingDoneDansLOrdre() throws InterruptedException {
        loop.runOneCycle();

        ArgumentCaptor<PhilosopherEvent> events = ArgumentCaptor.forClass(PhilosopherEvent.class);
        verify(publisher, org.mockito.Mockito.times(3)).publish(events.capture());
        assertThat(events.getAllValues()).extracting(PhilosopherEvent::type)
                .containsExactly(EventType.HUNGRY, EventType.EATING, EventType.DONE);
        assertThat(events.getAllValues()).allSatisfy(event -> {
            assertThat(event.philosopherId()).isEqualTo(3);
            assertThat(event.forks()).containsExactly(3, 4);
        });
    }

    @Test
    void lesFourchettesSontPrisesAvantDeMangerEtRenduesApresDone() throws InterruptedException {
        loop.runOneCycle();

        InOrder order = inOrder(strategy, publisher);
        order.verify(publisher).publish(eventOfType(EventType.HUNGRY));
        order.verify(strategy).acquire();
        order.verify(publisher).publish(eventOfType(EventType.EATING));
        order.verify(publisher).publish(eventOfType(EventType.DONE));
        order.verify(strategy).release();
    }

    @Test
    void lHorlogeDeLamportEtLaSequenceCroissentStrictement() throws InterruptedException {
        loop.runOneCycle();
        loop.runOneCycle();

        ArgumentCaptor<PhilosopherEvent> events = ArgumentCaptor.forClass(PhilosopherEvent.class);
        verify(publisher, org.mockito.Mockito.times(6)).publish(events.capture());
        List<PhilosopherEvent> all = events.getAllValues();
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).lamportTime()).isGreaterThan(all.get(i - 1).lamportTime());
            assertThat(all.get(i).sequence()).isEqualTo(all.get(i - 1).sequence() + 1);
        }
        assertThat(loop.status().meals()).isEqualTo(2);
    }

    @Test
    void siLAcquisitionEchoueOnNeMangePasEtOnNeRendRien() throws InterruptedException {
        doThrow(new IllegalStateException("superviseur en panne")).when(strategy).acquire();

        assertThatThrownBy(() -> loop.runOneCycle()).isInstanceOf(IllegalStateException.class);

        verify(publisher, never()).publish(eventOfType(EventType.EATING));
        verify(strategy, never()).release();
        assertThat(loop.status().meals()).isZero();
    }

    @Test
    void unRepasInterrompuRendQuandMemeLesFourchettes() {
        PhilosopherLoop slowEater = new PhilosopherLoop(1, "test", strategy, publisher, clock,
                new PhilosopherLoop.Timing(0, 0, 10_000, 10_000), new SimpleMeterRegistry());
        Thread.currentThread().interrupt();

        assertThatThrownBy(slowEater::runOneCycle).isInstanceOf(InterruptedException.class);

        verify(strategy).release();
        verify(publisher).publish(eventOfType(EventType.DONE));
    }

    @Test
    void demarrerPuisArreterLaBoucle() throws InterruptedException {
        loop.start();
        loop.start(); // sans effet : déjà démarrée
        assertThat(loop.isRunning()).isTrue();
        Thread.sleep(50);
        loop.stop();

        assertThat(loop.isRunning()).isFalse();
        assertThat(loop.status().meals()).isGreaterThan(0);
        verify(publisher, org.mockito.Mockito.atLeast(3)).publish(any());
    }

    private static PhilosopherEvent eventOfType(EventType type) {
        return org.mockito.ArgumentMatchers.argThat(event -> event != null && event.type() == type);
    }
}
