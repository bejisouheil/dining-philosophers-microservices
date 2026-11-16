package fr.cytech.dining.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import fr.cytech.dining.common.PhilosopherEvent;
import fr.cytech.dining.monitor.Snapshot.PhilosopherView;
import fr.cytech.dining.monitor.Snapshot.Violation;

/**
 * Rejoue l'historique (trié par horloge de Lamport) pour en déduire l'état du système :
 * état de chaque philosophe, violations d'exclusion mutuelle, famine, interblocage suspecté.
 */
@Service
public class SnapshotService {

    /** Au-delà de ce délai sans manger, un philosophe affamé est signalé. */
    static final long HUNGER_THRESHOLD_MS = 8_000;
    private static final int RECENT_EVENTS = 60;
    private static final int RECENT_VIOLATIONS = 50;

    public Snapshot compute(List<PhilosopherEvent> history, long nowMillis) {
        Map<Integer, Progress> progress = new TreeMap<>();
        Map<Integer, Integer> forkHolder = new HashMap<>();
        List<Violation> violations = new ArrayList<>();
        long totalMeals = 0;

        for (PhilosopherEvent event : history) {
            Progress p = progress.computeIfAbsent(event.philosopherId(), id -> new Progress());
            p.mode = event.mode();
            p.lamportTime = event.lamportTime();
            p.forks = event.forks();
            switch (event.type()) {
                case STARTED -> {
                    // (Re)démarrage : le philosophe pense et ne tient plus rien, même s'il a été
                    // interrompu en plein repas sans avoir pu émettre DONE.
                    p.state = "THINKING";
                    p.hungrySince = 0;
                    forkHolder.values().removeIf(holder -> holder == event.philosopherId());
                }
                case HUNGRY -> {
                    p.state = "HUNGRY";
                    p.hungrySince = event.wallClockMillis();
                }
                case EATING -> {
                    p.state = "EATING";
                    if (p.hungrySince > 0) {
                        p.maxWaitMs = Math.max(p.maxWaitMs, event.wallClockMillis() - p.hungrySince);
                    }
                    p.hungrySince = 0;
                    for (int fork : event.forks()) {
                        Integer holder = forkHolder.put(fork, event.philosopherId());
                        if (holder != null && holder != event.philosopherId()) {
                            violations.add(new Violation(fork, holder, event.philosopherId(), event.lamportTime()));
                        }
                    }
                }
                case DONE -> {
                    p.state = "THINKING";
                    p.meals++;
                    totalMeals++;
                    for (int fork : event.forks()) {
                        forkHolder.remove(fork, event.philosopherId());
                    }
                }
            }
        }

        List<PhilosopherView> views = new ArrayList<>();
        List<Integer> starving = new ArrayList<>();
        boolean everyoneHungry = progress.size() >= 2;
        for (Map.Entry<Integer, Progress> entry : progress.entrySet()) {
            Progress p = entry.getValue();
            long hungryFor = p.hungrySince > 0 ? Math.max(0, nowMillis - p.hungrySince) : 0;
            boolean starvingNow = "HUNGRY".equals(p.state) && hungryFor > HUNGER_THRESHOLD_MS;
            if (starvingNow) {
                starving.add(entry.getKey());
            }
            everyoneHungry &= starvingNow;
            views.add(new PhilosopherView(entry.getKey(), p.state, p.mode, p.lamportTime, p.meals, hungryFor,
                    p.maxWaitMs, p.forks));
        }

        List<PhilosopherEvent> recent = new ArrayList<>(
                history.subList(Math.max(0, history.size() - RECENT_EVENTS), history.size()));
        java.util.Collections.reverse(recent);
        List<Violation> recentViolations = violations.subList(
                Math.max(0, violations.size() - RECENT_VIOLATIONS), violations.size());

        return new Snapshot(views, recent, List.copyOf(recentViolations), violations.size(), everyoneHungry,
                starving, history.size(), totalMeals, nowMillis);
    }

    private static final class Progress {
        String state = "THINKING";
        String mode = "";
        long lamportTime;
        long meals;
        long hungrySince;
        long maxWaitMs;
        List<Integer> forks = List.of();
    }
}
