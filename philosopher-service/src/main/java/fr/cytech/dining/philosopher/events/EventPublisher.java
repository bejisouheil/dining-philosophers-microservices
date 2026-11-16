package fr.cytech.dining.philosopher.events;

import fr.cytech.dining.common.PhilosopherEvent;

/** Envoi des événements d'un philosophe vers le moniteur. Ne doit jamais bloquer le philosophe. */
public interface EventPublisher {

    void publish(PhilosopherEvent event);

    /** Publication désactivée (tests, mode sans moniteur). */
    EventPublisher NONE = event -> { };
}
