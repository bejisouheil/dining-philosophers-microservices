package fr.cytech.dining.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class LamportClockTest {

    @Test
    void tickAvanceDeUn() {
        LamportClock clock = new LamportClock();
        assertThat(clock.tick()).isEqualTo(1);
        assertThat(clock.tick()).isEqualTo(2);
        assertThat(clock.current()).isEqualTo(2);
    }

    @Test
    void updatePrendLeMaxPlusUn() {
        LamportClock clock = new LamportClock();
        clock.tick();
        assertThat(clock.update(10)).isEqualTo(11);
        assertThat(clock.update(3)).isEqualTo(12);
    }

    @Test
    void laReceptionEstToujoursDateeApresLEnvoi() {
        LamportClock sender = new LamportClock();
        LamportClock receiver = new LamportClock();
        for (int i = 0; i < 5; i++) {
            receiver.tick();
        }
        long sent = sender.tick();
        long received = receiver.update(sent);
        assertThat(received).isGreaterThan(sent);
    }

    @Test
    void deuxThreadsNObtiennentJamaisLaMemeDate() throws InterruptedException {
        LamportClock clock = new LamportClock();
        Set<Long> dates = ConcurrentHashMap.newKeySet();
        int threads = 8;
        int ticksPerThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < ticksPerThread; i++) {
                    dates.add(clock.tick());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(dates).hasSize(threads * ticksPerThread);
        assertThat(clock.current()).isEqualTo((long) threads * ticksPerThread);
    }
}
