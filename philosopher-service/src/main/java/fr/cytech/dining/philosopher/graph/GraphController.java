package fr.cytech.dining.philosopher.graph;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.cytech.dining.philosopher.config.PhilosopherIdentity;
import fr.cytech.dining.philosopher.config.PhilosopherProperties;
import fr.cytech.dining.philosopher.graph.Tasks.DegreeResult;
import fr.cytech.dining.philosopher.graph.Tasks.DegreeTask;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandResult;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandTask;
import fr.cytech.dining.philosopher.graph.Tasks.LoadTask;
import fr.cytech.dining.philosopher.graph.Tasks.PartitionNotLoadedException;

/**
 * API Master-Worker (section 5). Tout philosophe expose les deux rôles : celui qui reçoit un appel
 * {@code /graph/master/...} devient le Master du calcul et distribue le travail aux autres
 * philosophes, qui jouent les Workers.
 */
@RestController
public class GraphController {

    /**
     * Demande de calcul. Le graphe est soit fourni ({@code edges}), soit généré au hasard à partir
     * de {@code edgeCount} et {@code seed}.
     *
     * @param nodes          nombre de nœuds
     * @param edges          arêtes à plat [u0, v0, u1, v1...] (facultatif)
     * @param edgeCount      nombre d'arêtes à générer si {@code edges} est absent
     * @param seed           graine du générateur
     * @param partitions     nombre de tranches (0 = une par Worker)
     * @param workers        nombre de Workers à utiliser (0 = tous les autres philosophes)
     * @param taskTimeoutMs  délai accordé à un Worker avant de relancer sa tâche ailleurs (0 = 10 s)
     * @param source         accessibilité : nœud de départ
     * @param target         accessibilité : nœud à atteindre
     * @param includeDegrees renvoyer aussi le degré de chaque nœud (peut être volumineux)
     */
    public record JobRequest(Integer nodes, int[] edges, Integer edgeCount, Long seed, Integer partitions,
                             Integer workers, Long taskTimeoutMs, Integer source, Integer target,
                             Boolean includeDegrees) {

        /** Tout champ absent du JSON prend sa valeur par défaut. */
        public JobRequest {
            nodes = nodes == null ? 0 : nodes;
            edgeCount = edgeCount == null ? 0 : edgeCount;
            seed = seed == null ? 0L : seed;
            partitions = partitions == null ? 0 : partitions;
            workers = workers == null ? 0 : workers;
            taskTimeoutMs = taskTimeoutMs == null ? 0L : taskTimeoutMs;
            source = source == null ? 0 : source;
            target = target == null ? 0 : target;
            includeDegrees = includeDegrees != null && includeDegrees;
        }
    }

    /** Réponse au calcul des degrés, avec la durée séquentielle de référence pour comparer. */
    public record DegreeResponse(int nodes, int edges, int partitions, int workers, int retries,
                                 long distributedMs, long sequentialMs, long sumOfDegrees, int maxDegree,
                                 boolean consistent, boolean matchesSequential, int[] degrees) {
    }

    /** Réponse à l'accessibilité, avec la durée séquentielle de référence pour comparer. */
    public record ReachabilityResponse(int nodes, int edges, int source, int target, boolean reachable,
                                       int distance, int rounds, int visited, int partitions, int workers,
                                       int retries, long distributedMs, long sequentialMs,
                                       boolean matchesSequential) {
    }

    private final GraphWorker worker;
    private final WorkerClient workerClient;
    private final PhilosopherIdentity identity;

    public GraphController(GraphWorker worker, PhilosopherProperties properties, PhilosopherIdentity identity) {
        this.worker = worker;
        this.identity = identity;
        // Délai HTTP large : c'est le Dispatcher qui décide quand une tâche est trop lente.
        this.workerClient = new HttpWorkerClient(properties::peerUrl, Duration.ofSeconds(120));
    }

    // --- Rôle Master ---

    @PostMapping("/graph/master/degrees")
    public DegreeResponse degrees(@RequestBody JobRequest request) {
        int[] edges = edgesOf(request);
        List<Integer> workers = workersFor(request);
        int partitions = request.partitions() > 0 ? request.partitions() : workers.size();
        GraphMaster.DegreeOutcome outcome = new GraphMaster(workerClient, workers)
                .degrees(request.nodes(), edges, partitions, timeoutOf(request));

        long start = System.nanoTime();
        int[] reference = Graphs.degrees(request.nodes(), edges);
        long sequentialMs = (System.nanoTime() - start) / 1_000_000;
        int maxDegree = 0;
        for (int degree : outcome.degrees()) {
            maxDegree = Math.max(maxDegree, degree);
        }
        return new DegreeResponse(request.nodes(), edges.length / 2, partitions, workers.size(), outcome.retries(),
                outcome.durationMs(), sequentialMs, outcome.sumOfDegrees(), maxDegree, outcome.consistent(),
                java.util.Arrays.equals(reference, outcome.degrees()),
                request.includeDegrees() ? outcome.degrees() : null);
    }

    @PostMapping("/graph/master/reachability")
    public ReachabilityResponse reachability(@RequestBody JobRequest request) {
        int[] edges = edgesOf(request);
        List<Integer> workers = workersFor(request);
        int partitions = request.partitions() > 0 ? request.partitions() : workers.size();
        GraphMaster.ReachabilityOutcome outcome = new GraphMaster(workerClient, workers)
                .reachability(request.nodes(), edges, request.source(), request.target(), partitions,
                        timeoutOf(request));

        long start = System.nanoTime();
        int reference = Graphs.distance(request.nodes(), edges, request.source(), request.target());
        long sequentialMs = (System.nanoTime() - start) / 1_000_000;
        return new ReachabilityResponse(request.nodes(), edges.length / 2, request.source(), request.target(),
                outcome.reachable(), outcome.distance(), outcome.rounds(), outcome.visited(), partitions,
                workers.size(), outcome.retries(), outcome.durationMs(), sequentialMs,
                reference == outcome.distance());
    }

    private static int[] edgesOf(JobRequest request) {
        if (request.edges() != null && request.edges().length > 0) {
            return request.edges();
        }
        if (request.nodes() < 1 || request.edgeCount() < 0) {
            throw new IllegalArgumentException("Indiquer nodes >= 1 et edgeCount >= 0, ou fournir edges");
        }
        return Graphs.randomEdges(request.nodes(), request.edgeCount(), request.seed());
    }

    /** Les Workers sont les autres philosophes ; un philosophe seul travaille pour lui-même. */
    private List<Integer> workersFor(JobRequest request) {
        List<Integer> workers = new ArrayList<>();
        for (int id = 0; id < identity.count(); id++) {
            if (id != identity.id()) {
                workers.add(id);
            }
        }
        if (workers.isEmpty()) {
            workers.add(identity.id());
        }
        if (request.workers() > 0 && request.workers() < workers.size()) {
            return workers.subList(0, request.workers());
        }
        return workers;
    }

    private static Duration timeoutOf(JobRequest request) {
        return Duration.ofMillis(request.taskTimeoutMs() > 0 ? request.taskTimeoutMs() : 10_000);
    }

    // --- Rôle Worker ---

    @PostMapping("/graph/worker/degrees")
    public DegreeResult workerDegrees(@RequestBody DegreeTask task) {
        return worker.degrees(task);
    }

    @PostMapping("/graph/worker/partitions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void workerLoad(@RequestBody LoadTask task) {
        worker.load(task);
    }

    @PostMapping("/graph/worker/expand")
    public ExpandResult workerExpand(@RequestBody ExpandTask task) {
        return worker.expand(task);
    }

    @DeleteMapping("/graph/worker/jobs/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void workerForget(@PathVariable String jobId) {
        worker.forget(jobId);
    }

    /** Tests de pannes : ralentit ce Worker ou fait échouer ses prochaines tâches. */
    @PostMapping("/graph/worker/chaos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void chaos(@RequestParam(defaultValue = "0") long delayMs, @RequestParam(defaultValue = "0") int failures) {
        worker.chaos(delayMs, failures);
    }

    @ExceptionHandler(PartitionNotLoadedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> partitionNotLoaded(PartitionNotLoadedException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
