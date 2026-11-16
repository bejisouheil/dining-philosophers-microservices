package fr.cytech.dining.philosopher.graph;

import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicIntegerArray;

import fr.cytech.dining.philosopher.graph.Tasks.DegreeResult;
import fr.cytech.dining.philosopher.graph.Tasks.DegreeTask;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandTask;
import fr.cytech.dining.philosopher.graph.Tasks.LoadTask;
import fr.cytech.dining.philosopher.graph.Tasks.PartitionNotLoadedException;

/**
 * Rôle Master (section 5) : découpe le graphe, distribue les tâches aux Workers, relance celles
 * qui échouent et assemble les résultats partiels.
 */
public class GraphMaster {

    /**
     * Résultat du calcul des degrés.
     *
     * @param degrees      degré de chaque nœud
     * @param sumOfDegrees somme des degrés ; doit valoir deux fois le nombre d'arêtes
     * @param consistent   vrai si cette égalité est vérifiée (aucune arête perdue ni comptée deux fois)
     * @param partitions   nombre de tranches
     * @param workers      nombre de Workers utilisés
     * @param retries      nombre de tâches relancées à la suite d'une panne ou d'un retard
     * @param durationMs   durée du calcul distribué
     */
    public record DegreeOutcome(int[] degrees, long sumOfDegrees, boolean consistent, int partitions,
                                int workers, int retries, long durationMs) {
    }

    /**
     * Résultat de l'accessibilité.
     *
     * @param reachable  vrai s'il existe un chemin de la source à la cible
     * @param distance   longueur du plus court chemin, -1 s'il n'y en a pas
     * @param rounds     nombre de tours de parcours en largeur
     * @param visited    nombre de nœuds atteints depuis la source
     * @param retries    nombre de tâches relancées
     * @param durationMs durée du calcul distribué
     */
    public record ReachabilityOutcome(boolean reachable, int distance, int rounds, int visited, int retries,
                                      long durationMs) {
    }

    private final WorkerClient client;
    private final List<Integer> workers;

    public GraphMaster(WorkerClient client, List<Integer> workers) {
        this.client = client;
        this.workers = List.copyOf(workers);
    }

    /**
     * Degré de chaque nœud d'un graphe non orienté.
     *
     * <p>Le graphe est découpé <strong>par arêtes</strong> : chaque arête va dans une seule tranche.
     * Un Worker compte, pour chaque arête de sa tranche, +1 sur chacune des deux extrémités, que le
     * nœud « appartienne » ou non à une autre tranche. Le Master additionne les contributions. Une
     * arête n'étant jamais dans deux tranches, il n'y a pas de double comptage à corriger.
     */
    public DegreeOutcome degrees(int nodes, int[] edges, int partitions, Duration taskTimeout) {
        Graphs.validate(nodes, edges);
        requirePartitions(partitions);
        long start = System.nanoTime();
        String jobId = UUID.randomUUID().toString();
        List<int[]> slices = Graphs.splitEdges(edges, partitions);
        try (Dispatcher dispatcher = new Dispatcher(workers, taskTimeout)) {
            List<Future<DegreeResult>> pending = new ArrayList<>();
            for (int partition = 0; partition < partitions; partition++) {
                DegreeTask task = new DegreeTask(jobId, partition, slices.get(partition));
                int preferred = partition % dispatcher.workerCount();
                pending.add(dispatcher.async(() -> dispatcher.run(preferred, worker -> client.degrees(worker, task))
                        .value()));
            }
            // Une case par tranche : chaque résultat partiel est rangé puis additionné une seule fois.
            int[] degrees = new int[nodes];
            long sum = 0;
            for (Future<DegreeResult> future : pending) {
                DegreeResult partial = await(future);
                for (int i = 0; i < partial.nodes().length; i++) {
                    degrees[partial.nodes()[i]] += partial.degrees()[i];
                    sum += partial.degrees()[i];
                }
            }
            return new DegreeOutcome(degrees, sum, sum == edges.length, partitions, dispatcher.workerCount(),
                    dispatcher.retries(), elapsedMs(start));
        }
    }

    /**
     * Accessibilité de {@code target} depuis {@code source} dans un graphe orienté, par parcours en
     * largeur distribué.
     *
     * <p>Le graphe est découpé <strong>par nœuds</strong> : chaque Worker garde les arcs sortants
     * de ses nœuds. À chaque tour le Master envoie à chaque Worker la partie de la frontière qui lui
     * appartient ; les Workers répondent en parallèle par les successeurs ; le Master écarte les
     * nœuds déjà vus et forme la frontière suivante. On s'arrête dès que la cible est atteinte ou
     * que la frontière est vide.
     */
    public ReachabilityOutcome reachability(int nodes, int[] edges, int source, int target, int partitions,
                                            Duration taskTimeout) {
        Graphs.validate(nodes, edges);
        requirePartitions(partitions);
        if (source < 0 || source >= nodes || target < 0 || target >= nodes) {
            throw new IllegalArgumentException("La source et la cible doivent être des nœuds du graphe");
        }
        long start = System.nanoTime();
        String jobId = UUID.randomUUID().toString();
        List<int[]> slices = Graphs.splitBySource(edges, partitions);
        Set<String> loaded = ConcurrentHashMap.newKeySet();
        AtomicIntegerArray preferred = new AtomicIntegerArray(partitions);
        for (int partition = 0; partition < partitions; partition++) {
            preferred.set(partition, partition % workers.size());
        }
        BitSet visited = new BitSet(nodes);
        visited.set(source);
        int[] frontier = {source};
        int rounds = 0;
        try (Dispatcher dispatcher = new Dispatcher(workers, taskTimeout)) {
            while (frontier.length > 0 && !visited.get(target)) {
                rounds++;
                List<Future<int[]>> pending = new ArrayList<>();
                List<int[]> frontierByPartition = splitFrontier(frontier, partitions);
                for (int partition = 0; partition < partitions; partition++) {
                    int[] part = frontierByPartition.get(partition);
                    if (part.length == 0) {
                        continue;
                    }
                    int index = partition;
                    ExpandTask task = new ExpandTask(jobId, partition, part);
                    LoadTask load = new LoadTask(jobId, partition, slices.get(partition));
                    pending.add(dispatcher.async(() -> {
                        Dispatcher.Answer<int[]> answer = dispatcher.run(preferred.get(index),
                                worker -> expandOn(worker, load, task, loaded));
                        preferred.set(index, answer.workerRank()); // on reste sur le Worker qui a la partition
                        return answer.value();
                    }));
                }
                BitSet next = new BitSet(nodes);
                for (Future<int[]> future : pending) {
                    for (int neighbour : await(future)) {
                        if (!visited.get(neighbour)) {
                            visited.set(neighbour);
                            next.set(neighbour);
                        }
                    }
                }
                frontier = next.stream().toArray();
            }
            boolean reachable = visited.get(target);
            int distance = !reachable ? -1 : source == target ? 0 : rounds;
            for (int worker : workers) {
                try {
                    client.forget(worker, jobId);
                } catch (RuntimeException e) {
                    // Worker en panne : il n'a de toute façon plus rien en mémoire
                }
            }
            return new ReachabilityOutcome(reachable, distance, rounds, visited.cardinality(), dispatcher.retries(),
                    elapsedMs(start));
        }
    }

    /** Envoie d'abord la partition au Worker s'il ne l'a pas encore (ou plus), puis la tâche. */
    private int[] expandOn(int worker, LoadTask load, ExpandTask task, Set<String> loaded) {
        String key = worker + "/" + load.partition();
        if (loaded.add(key)) {
            try {
                client.load(worker, load);
            } catch (RuntimeException e) {
                loaded.remove(key);
                throw e;
            }
        }
        try {
            return client.expand(worker, task).neighbours();
        } catch (PartitionNotLoadedException e) {
            client.load(worker, load); // le Worker a redémarré entre-temps
            return client.expand(worker, task).neighbours();
        } catch (RuntimeException e) {
            loaded.remove(key);
            throw e;
        }
    }

    private static List<int[]> splitFrontier(int[] frontier, int partitions) {
        int[] sizes = new int[partitions];
        for (int node : frontier) {
            sizes[Graphs.owner(node, partitions)]++;
        }
        List<int[]> result = new ArrayList<>(partitions);
        for (int partition = 0; partition < partitions; partition++) {
            result.add(new int[sizes[partition]]);
        }
        int[] cursor = new int[partitions];
        for (int node : frontier) {
            int partition = Graphs.owner(node, partitions);
            result.get(partition)[cursor[partition]++] = node;
        }
        return result;
    }

    private static void requirePartitions(int partitions) {
        if (partitions < 1) {
            throw new IllegalArgumentException("Il faut au moins une partition");
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Calcul interrompu", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException(e.getCause());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
