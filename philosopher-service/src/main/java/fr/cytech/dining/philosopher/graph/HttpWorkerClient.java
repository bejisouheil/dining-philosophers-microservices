package fr.cytech.dining.philosopher.graph;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.common.HttpSupport;
import fr.cytech.dining.philosopher.graph.Tasks.DegreeResult;
import fr.cytech.dining.philosopher.graph.Tasks.DegreeTask;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandResult;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandTask;
import fr.cytech.dining.philosopher.graph.Tasks.LoadTask;
import fr.cytech.dining.philosopher.graph.Tasks.PartitionNotLoadedException;

/** Appelle les Workers par HTTP : les tâches et les résultats voyagent en JSON entre microservices. */
public class HttpWorkerClient implements WorkerClient {

    private final IntFunction<String> urlOf;
    private final Duration timeout;
    private final Map<Integer, RestClient> clients = new ConcurrentHashMap<>();

    public HttpWorkerClient(IntFunction<String> urlOf, Duration timeout) {
        this.urlOf = urlOf;
        this.timeout = timeout;
    }

    private RestClient client(int worker) {
        return clients.computeIfAbsent(worker, id -> HttpSupport.restClient(urlOf.apply(id), timeout));
    }

    @Override
    public DegreeResult degrees(int worker, DegreeTask task) {
        return client(worker).post().uri("/graph/worker/degrees").contentType(MediaType.APPLICATION_JSON).body(task)
                .retrieve().body(DegreeResult.class);
    }

    @Override
    public void load(int worker, LoadTask task) {
        client(worker).post().uri("/graph/worker/partitions").contentType(MediaType.APPLICATION_JSON).body(task)
                .retrieve().toBodilessEntity();
    }

    @Override
    public ExpandResult expand(int worker, ExpandTask task) {
        try {
            return client(worker).post().uri("/graph/worker/expand").contentType(MediaType.APPLICATION_JSON)
                    .body(task).retrieve().body(ExpandResult.class);
        } catch (HttpClientErrorException.Conflict e) {
            throw new PartitionNotLoadedException(task.jobId(), task.partition());
        }
    }

    @Override
    public void forget(int worker, String jobId) {
        client(worker).delete().uri("/graph/worker/jobs/{jobId}", jobId).retrieve().toBodilessEntity();
    }
}
