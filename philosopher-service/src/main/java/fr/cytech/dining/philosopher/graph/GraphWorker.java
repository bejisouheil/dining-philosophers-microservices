package fr.cytech.dining.philosopher.graph;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import fr.cytech.dining.philosopher.graph.Tasks.DegreeResult;
import fr.cytech.dining.philosopher.graph.Tasks.DegreeTask;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandResult;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandTask;
import fr.cytech.dining.philosopher.graph.Tasks.LoadTask;
import fr.cytech.dining.philosopher.graph.Tasks.PartitionNotLoadedException;

/**
 * Rôle Worker : exécute les tâches que le Master lui confie.
 *
 * <p>Le calcul des degrés est <strong>sans état</strong> : la même tâche donne toujours le même
 * résultat, on peut donc la relancer sur n'importe quel Worker sans risque. Pour l'accessibilité
 * le Worker garde ses partitions en mémoire ; s'il redémarre, il répond qu'il ne les a plus et le
 * Master les renvoie.
 *
 * <p>Deux réglages servent uniquement aux tests de pannes : un délai artificiel et un nombre
 * d'échecs à simuler.
 */
public class GraphWorker {

    private final Map<String, Map<Integer, int[]>> partitions = new ConcurrentHashMap<>();
    private volatile long delayMs;
    private final AtomicInteger failuresToInject = new AtomicInteger();

    public DegreeResult degrees(DegreeTask task) {
        simulateTrouble();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int node : task.edges()) {
            counts.merge(node, 1, Integer::sum);
        }
        int[] nodes = new int[counts.size()];
        int[] degrees = new int[counts.size()];
        int index = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            nodes[index] = entry.getKey();
            degrees[index++] = entry.getValue();
        }
        return new DegreeResult(task.jobId(), task.partition(), nodes, degrees);
    }

    public void load(LoadTask task) {
        simulateTrouble();
        Map<Integer, IntStream.Builder> builders = new HashMap<>();
        int[] edges = task.edges();
        for (int i = 0; i < edges.length; i += 2) {
            builders.computeIfAbsent(edges[i], node -> IntStream.builder()).add(edges[i + 1]);
        }
        Map<Integer, int[]> successors = new HashMap<>();
        builders.forEach((node, builder) -> successors.put(node, builder.build().toArray()));
        partitions.put(key(task.jobId(), task.partition()), successors);
    }

    public ExpandResult expand(ExpandTask task) {
        simulateTrouble();
        Map<Integer, int[]> successors = partitions.get(key(task.jobId(), task.partition()));
        if (successors == null) {
            throw new PartitionNotLoadedException(task.jobId(), task.partition());
        }
        int[] neighbours = Arrays.stream(task.frontier())
                .flatMap(node -> Arrays.stream(successors.getOrDefault(node, new int[0])))
                .distinct()
                .toArray();
        return new ExpandResult(neighbours);
    }

    /** Fin d'un calcul : libère la mémoire de ses partitions. */
    public void forget(String jobId) {
        partitions.keySet().removeIf(key -> key.startsWith(jobId + "/"));
    }

    public int loadedPartitions() {
        return partitions.size();
    }

    /** Tests de pannes : chaque tâche attend {@code delayMs} ; les {@code failures} prochaines échouent. */
    public void chaos(long delayMs, int failures) {
        this.delayMs = delayMs;
        this.failuresToInject.set(failures);
    }

    private void simulateTrouble() {
        if (failuresToInject.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            throw new IllegalStateException("Panne simulée du Worker");
        }
        long delay = delayMs;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String key(String jobId, int partition) {
        return jobId + "/" + partition;
    }
}
