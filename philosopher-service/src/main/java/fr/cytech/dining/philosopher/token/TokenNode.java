package fr.cytech.dining.philosopher.token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import fr.cytech.dining.common.LamportClock;

/**
 * Cœur du protocole distribué par jetons (section 3.3). Aucun superviseur : chaque philosophe
 * exécute cette même logique et ne communique que par messages.
 *
 * <h2>Principe</h2>
 * <ul>
 *   <li>Chaque fourchette a un <strong>jeton unique</strong>. Au départ le philosophe {@code i}
 *       détient le jeton de la fourchette {@code i}. Pour manger il faut détenir les deux jetons de
 *       ses fourchettes : comme un jeton n'est qu'à un seul endroit, une fourchette ne peut pas
 *       servir à deux philosophes en même temps.</li>
 *   <li>Un philosophe qui a faim <strong>diffuse</strong> à tous une demande datée par son horloge
 *       de Lamport. Chacun mémorise les demandes reçues, triées par priorité (date, puis
 *       identifiant).</li>
 *   <li><strong>Règle de transfert</strong> : un philosophe qui détient un jeton et qui n'est pas
 *       en train de manger le donne au demandeur le plus prioritaire pour cette fourchette, sauf si
 *       ce demandeur est lui-même. Un philosophe qui attend cède donc ses jetons à plus prioritaire
 *       que lui.</li>
 * </ul>
 *
 * <h2>Propriétés</h2>
 * <ul>
 *   <li><strong>Exclusion mutuelle</strong> : unicité du jeton (retiré avant envoi, numéro de
 *       version contre les doublons).</li>
 *   <li><strong>Pas d'interblocage</strong> : on ne garde jamais un jeton face à une demande plus
 *       prioritaire, et la priorité est un ordre total identique partout : pas de cycle d'attente
 *       possible.</li>
 *   <li><strong>Pas de famine, équité</strong> : recevoir une demande recale l'horloge locale ;
 *       toute nouvelle demande est donc datée après celles déjà connues. Un philosophe qui vient
 *       de manger repasse derrière ceux qui attendaient.</li>
 * </ul>
 *
 * <p>Classe sans dépendance à Spring ni au réseau : elle se teste seule avec un faux transport.
 * Toutes les méthodes publiques sont sûres en présence de plusieurs threads (un verrou protège
 * l'état) ; les envois sont déclenchés une fois le verrou relâché.
 */
public class TokenNode {

    public enum State { IDLE, WAITING, EATING }

    /** Vue de l'état interne, pour l'API de diagnostic et les tests. */
    public record Snapshot(int id, State state, long clock, Map<Integer, Long> heldTokens,
                           List<ForkRequest> pendingRequests, ForkRequest currentRequest,
                           long messagesSent, long tokensSent) {
    }

    private final int id;
    private final int count;
    private final LamportClock clock;
    private final PeerTransport transport;
    private final ReentrantLock lock = new ReentrantLock();

    /** Jetons détenus : fourchette → version du jeton. */
    private final Map<Integer, Long> held = new HashMap<>();
    /** Plus haute version vue pour chaque fourchette : détecte les jetons reçus en double. */
    private final Map<Integer, Long> lastVersion = new HashMap<>();
    /** Demandes connues et non terminées (la mienne comprise), triées par priorité. */
    private final TreeSet<ForkRequest> pending = new TreeSet<>();
    /** Pour chaque philosophe, numéro de sa dernière demande terminée : filtre les demandes périmées. */
    private final Map<Integer, Long> completed = new HashMap<>();

    private long sequence;
    private ForkRequest current;
    private State state = State.IDLE;
    private CompletableFuture<Void> granted;

    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong tokensSent = new AtomicLong();

    public TokenNode(int id, int count, LamportClock clock, PeerTransport transport) {
        if (count < 2) {
            throw new IllegalArgumentException("Le protocole demande au moins 2 philosophes");
        }
        this.id = id;
        this.count = count;
        this.clock = clock;
        this.transport = transport;
        held.put(id, 0L); // chaque philosophe gère au départ sa propre fourchette
        lastVersion.put(id, 0L);
    }

    /**
     * Le philosophe a faim : diffuse une demande pour ces deux fourchettes.
     *
     * @return un futur terminé quand le philosophe détient les deux jetons et peut manger
     */
    public CompletableFuture<Void> request(List<Integer> forks) {
        if (forks.size() != 2 || forks.get(0).equals(forks.get(1))
                || forks.stream().anyMatch(fork -> fork < 0 || fork >= count)) {
            throw new IllegalArgumentException("Il faut deux fourchettes distinctes entre 0 et " + (count - 1));
        }
        List<Runnable> outbox = new ArrayList<>();
        CompletableFuture<Void> result;
        lock.lock();
        try {
            if (state != State.IDLE) {
                throw new IllegalStateException("Une demande est déjà en cours");
            }
            current = new ForkRequest(id, ++sequence, clock.tick(), forks);
            pending.add(current);
            state = State.WAITING;
            granted = new CompletableFuture<>();
            result = granted;
            broadcastRequest(outbox);
            evaluate(outbox);
        } finally {
            lock.unlock();
        }
        outbox.forEach(Runnable::run);
        return result;
    }

    /**
     * Fin du repas (ou abandon d'une demande en attente) : la demande est retirée, sa fin est
     * diffusée, et les jetons partent vers les prochains demandeurs.
     */
    public void release() {
        List<Runnable> outbox = new ArrayList<>();
        lock.lock();
        try {
            if (state == State.IDLE) {
                throw new IllegalStateException("Aucune demande en cours");
            }
            long finished = current.sequence();
            completed.put(id, finished);
            pending.remove(current);
            current = null;
            state = State.IDLE;
            if (!granted.isDone()) {
                granted.cancel(false);
            }
            long now = clock.tick();
            for (int peer = 0; peer < count; peer++) {
                if (peer != id) {
                    int to = peer;
                    Messages.Release message = new Messages.Release(id, finished, now);
                    outbox.add(() -> transport.sendRelease(to, message));
                    messagesSent.incrementAndGet();
                }
            }
            evaluate(outbox);
        } finally {
            lock.unlock();
        }
        outbox.forEach(Runnable::run);
    }

    /** Rediffuse la demande en cours : rattrape une demande perdue par le réseau. */
    public void retransmit() {
        List<Runnable> outbox = new ArrayList<>();
        lock.lock();
        try {
            if (state == State.WAITING) {
                broadcastRequest(outbox);
            }
        } finally {
            lock.unlock();
        }
        outbox.forEach(Runnable::run);
    }

    /** Réception de la demande d'un autre philosophe. */
    public void onRequest(Messages.Request message) {
        ForkRequest request = message.request();
        List<Runnable> outbox = new ArrayList<>();
        lock.lock();
        try {
            clock.update(message.senderClock());
            int from = request.philosopherId();
            if (from == id) {
                return;
            }
            forget(from, message.senderCompleted());
            if (request.sequence() <= completed.getOrDefault(from, 0L)) {
                return; // demande déjà terminée : retransmission tardive
            }
            pending.removeIf(known -> known.philosopherId() == from && known.sequence() < request.sequence());
            pending.add(request);
            evaluate(outbox);
        } finally {
            lock.unlock();
        }
        outbox.forEach(Runnable::run);
    }

    /** Réception de la fin de repas d'un autre philosophe. */
    public void onRelease(Messages.Release message) {
        List<Runnable> outbox = new ArrayList<>();
        lock.lock();
        try {
            clock.update(message.senderClock());
            forget(message.philosopherId(), message.sequence());
            evaluate(outbox);
        } finally {
            lock.unlock();
        }
        outbox.forEach(Runnable::run);
    }

    /** Réception du jeton d'une fourchette. */
    public void onToken(Messages.Token message) {
        List<Runnable> outbox = new ArrayList<>();
        lock.lock();
        try {
            clock.update(message.senderClock());
            forget(message.from(), message.senderCompleted());
            if (message.version() <= lastVersion.getOrDefault(message.fork(), -1L)) {
                return; // jeton déjà reçu : retransmission
            }
            lastVersion.put(message.fork(), message.version());
            held.put(message.fork(), message.version());
            evaluate(outbox);
        } finally {
            lock.unlock();
        }
        outbox.forEach(Runnable::run);
    }

    /** Oublie les demandes de {@code philosopher} jusqu'au numéro {@code upTo} : elles sont terminées. */
    private void forget(int philosopher, long upTo) {
        if (philosopher == id || upTo <= completed.getOrDefault(philosopher, 0L)) {
            return;
        }
        completed.put(philosopher, upTo);
        pending.removeIf(known -> known.philosopherId() == philosopher && known.sequence() <= upTo);
    }

    private void broadcastRequest(List<Runnable> outbox) {
        long now = clock.tick();
        Messages.Request message = new Messages.Request(current, now, completed.getOrDefault(id, 0L));
        for (int peer = 0; peer < count; peer++) {
            if (peer != id) {
                int to = peer;
                outbox.add(() -> transport.sendRequest(to, message));
                messagesSent.incrementAndGet();
            }
        }
    }

    /**
     * Applique la règle de transfert à chaque jeton détenu, puis vérifie si l'on peut manger.
     * Appelée à chaque changement d'état, toujours sous le verrou.
     */
    private void evaluate(List<Runnable> outbox) {
        for (int fork : new ArrayList<>(held.keySet())) {
            if (state == State.EATING && current.needs(fork)) {
                continue; // on ne lâche pas une fourchette pendant le repas
            }
            ForkRequest best = mostUrgentRequestFor(fork);
            if (best == null || best.philosopherId() == id) {
                continue; // personne n'en veut, ou c'est moi le plus prioritaire : je garde
            }
            long version = held.remove(fork) + 1;
            lastVersion.put(fork, version);
            int to = best.philosopherId();
            Messages.Token message = new Messages.Token(fork, version, id, clock.tick(),
                    completed.getOrDefault(id, 0L));
            outbox.add(() -> transport.sendToken(to, message));
            messagesSent.incrementAndGet();
            tokensSent.incrementAndGet();
        }
        if (state == State.WAITING && held.keySet().containsAll(current.forks())) {
            state = State.EATING;
            CompletableFuture<Void> toComplete = granted;
            outbox.add(() -> toComplete.complete(null));
        }
    }

    private ForkRequest mostUrgentRequestFor(int fork) {
        for (ForkRequest request : pending) {
            if (request.needs(fork)) {
                return request;
            }
        }
        return null;
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(id, state, clock.current(), new TreeMap<>(held), List.copyOf(pending), current,
                    messagesSent.get(), tokensSent.get());
        } finally {
            lock.unlock();
        }
    }

    public int id() {
        return id;
    }
}
