package fr.cytech.dining.philosopher.zookeeper;

import java.util.List;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;

import fr.cytech.dining.philosopher.core.DiningStrategy;

/**
 * Section 3.4 : la même exclusion mutuelle, confiée à un outil de coordination distribuée.
 *
 * <p>Chaque fourchette est un verrou distribué ZooKeeper ({@link InterProcessMutex} d'Apache
 * Curator, nœud {@code /dining/forks/N}). Le philosophe prend ses deux verrous <strong>toujours
 * dans l'ordre croissant des numéros</strong> : avec un ordre global sur les ressources, un cycle
 * d'attente est impossible, donc pas d'interblocage.
 *
 * <p>Ce que ZooKeeper apporte par rapport à notre protocole : une file d'attente équitable par
 * verrou (nœuds séquentiels), et surtout la libération automatique des verrous d'un philosophe qui
 * tombe en panne (nœuds éphémères liés à sa session). En échange, tout dépend de la disponibilité
 * du service ZooKeeper.
 */
public class ZooKeeperStrategy implements DiningStrategy {

    static final String FORKS_PATH = "/dining/forks/";

    private final CuratorFramework client;
    private final boolean ownsClient;
    private final List<Integer> forks;
    private final InterProcessMutex lowerFork;
    private final InterProcessMutex higherFork;

    /** Utilise un client Curator existant (tests). {@code forks} doit être trié par ordre croissant. */
    public ZooKeeperStrategy(CuratorFramework client, List<Integer> forks) {
        this(client, forks, false);
    }

    private ZooKeeperStrategy(CuratorFramework client, List<Integer> forks, boolean ownsClient) {
        if (forks.size() != 2 || forks.get(0) >= forks.get(1)) {
            throw new IllegalArgumentException("Deux fourchettes distinctes, triées par ordre croissant : " + forks);
        }
        this.client = client;
        this.ownsClient = ownsClient;
        this.forks = List.copyOf(forks);
        this.lowerFork = new InterProcessMutex(client, FORKS_PATH + forks.get(0));
        this.higherFork = new InterProcessMutex(client, FORKS_PATH + forks.get(1));
    }

    /** Ouvre sa propre connexion à ZooKeeper, avec nouvelles tentatives en cas de coupure. */
    public static ZooKeeperStrategy connect(String connectString, List<Integer> forks) {
        CuratorFramework client = CuratorFrameworkFactory.builder()
                .connectString(connectString)
                .sessionTimeoutMs(10_000)
                .connectionTimeoutMs(5_000)
                .retryPolicy(new ExponentialBackoffRetry(200, 8))
                .build();
        client.start();
        return new ZooKeeperStrategy(client, forks, true);
    }

    @Override
    public List<Integer> forks() {
        return forks;
    }

    @Override
    public void acquire() throws InterruptedException {
        lock(lowerFork);
        try {
            lock(higherFork);
        } catch (InterruptedException | RuntimeException e) {
            unlock(lowerFork); // ne jamais garder une fourchette si l'on renonce à l'autre
            throw e;
        }
    }

    @Override
    public void release() {
        unlock(higherFork);
        unlock(lowerFork);
    }

    private static void lock(InterProcessMutex mutex) throws InterruptedException {
        try {
            mutex.acquire();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ZooKeeper indisponible : verrou non obtenu", e);
        }
    }

    private static void unlock(InterProcessMutex mutex) {
        try {
            if (mutex.isOwnedByCurrentThread()) {
                mutex.release();
            }
        } catch (Exception e) {
            // Si la session est perdue, ZooKeeper supprime de lui-même le nœud éphémère du verrou.
            throw new IllegalStateException("ZooKeeper indisponible : verrou non rendu", e);
        }
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }
}
