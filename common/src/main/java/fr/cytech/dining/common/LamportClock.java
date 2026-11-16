package fr.cytech.dining.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Horloge logique de Lamport.
 *
 * <p>Règles : avant chaque événement local ou envoi, l'horloge avance de 1 ({@link #tick()}) ;
 * à la réception d'un message daté {@code t}, elle passe à {@code max(locale, t) + 1}
 * ({@link #update(long)}). Si un événement A a causé un événement B, alors date(A) &lt; date(B).
 */
public final class LamportClock {

    private final AtomicLong time = new AtomicLong();

    /** Événement local ou envoi de message : avance l'horloge et renvoie la nouvelle date. */
    public long tick() {
        return time.incrementAndGet();
    }

    /** Réception d'un message daté {@code received} : recale l'horloge et renvoie la nouvelle date. */
    public long update(long received) {
        return time.updateAndGet(local -> Math.max(local, received) + 1);
    }

    /** Date courante, sans la modifier. */
    public long current() {
        return time.get();
    }
}
