package fr.cytech.dining.monitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import fr.cytech.dining.common.PhilosopherEvent;

/**
 * Mémoire du moniteur : tous les événements reçus, sans doublon.
 *
 * <p>Les événements arrivent dans un ordre quelconque (réseau, Kafka, plusieurs philosophes).
 * {@link #history()} les remet dans l'ordre des horloges de Lamport ; à date égale, l'identifiant
 * du philosophe départage. C'est un ordre total compatible avec la causalité.
 */
@Component
public class EventStore {

    static final int MAX_EVENTS = 20_000;

    static final Comparator<PhilosopherEvent> LAMPORT_ORDER = Comparator
            .comparingLong(PhilosopherEvent::lamportTime)
            .thenComparingInt(PhilosopherEvent::philosopherId)
            .thenComparingLong(PhilosopherEvent::sequence);

    private final List<PhilosopherEvent> events = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    /** Ajoute un événement ; renvoie {@code false} si c'est un doublon (retransmission). */
    public synchronized boolean add(PhilosopherEvent event) {
        if (!seen.add(key(event))) {
            return false;
        }
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            PhilosopherEvent oldest = events.remove(0);
            seen.remove(key(oldest));
        }
        return true;
    }

    /** Historique reconstruit, trié par horloge de Lamport. */
    public synchronized List<PhilosopherEvent> history() {
        List<PhilosopherEvent> sorted = new ArrayList<>(events);
        sorted.sort(LAMPORT_ORDER);
        return sorted;
    }

    /** Plus grande date de Lamport reçue : un philosophe qui redémarre s'y recale. */
    public synchronized long maxLamportTime() {
        long max = 0;
        for (PhilosopherEvent event : events) {
            max = Math.max(max, event.lamportTime());
        }
        return max;
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized void clear() {
        events.clear();
        seen.clear();
    }

    // L'heure réelle fait partie de la clé : un philosophe redémarré repart de la séquence 1.
    private static String key(PhilosopherEvent event) {
        return event.philosopherId() + ":" + event.sequence() + ":" + event.wallClockMillis();
    }
}
