package fr.cytech.dining.monitor;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.cytech.dining.common.PhilosopherEvent;

/** API du moniteur : réception des événements et données du tableau de bord. */
@RestController
public class MonitorController {

    private final EventStore store;
    private final SnapshotService snapshots;

    public MonitorController(EventStore store, SnapshotService snapshots) {
        this.store = store;
        this.snapshots = snapshots;
    }

    /** Reçoit un événement HUNGRY, EATING ou DONE d'un philosophe. */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@RequestBody PhilosopherEvent event) {
        store.add(event);
    }

    /** État du système pour le tableau de bord. */
    @GetMapping("/api/snapshot")
    public Snapshot snapshot() {
        return snapshots.compute(store.history(), System.currentTimeMillis());
    }

    /** Historique complet reconstruit dans l'ordre des horloges de Lamport. */
    @GetMapping("/api/events")
    public List<PhilosopherEvent> events(@RequestParam(required = false) Integer philosopher) {
        List<PhilosopherEvent> history = store.history();
        if (philosopher == null) {
            return history;
        }
        return history.stream().filter(event -> event.philosopherId() == philosopher).toList();
    }

    /** Horloge du moniteur : la plus grande date de Lamport qu'il a vue passer. */
    public record ClockView(long lamportTime) {
    }

    @GetMapping("/api/clock")
    public ClockView clock() {
        return new ClockView(store.maxLamportTime());
    }

    /** Remet le moniteur à zéro (pratique entre deux démonstrations). */
    @DeleteMapping("/api/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        store.clear();
    }
}
