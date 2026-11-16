package fr.cytech.dining.philosopher.graph;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Confie une tâche à un Worker et la relance ailleurs s'il ne répond pas à temps ou échoue.
 *
 * <p>Pourquoi la correction est préservée : une tâche est une fonction pure de ses données, donc
 * la relancer donne le même résultat ; et l'appelant ne reçoit <strong>qu'une seule</strong>
 * réponse par tâche, la première obtenue. Si le Worker lent finit par répondre après coup, sa
 * réponse tardive n'est lue par personne : rien n'est compté deux fois.
 */
class Dispatcher implements AutoCloseable {

    private final List<Integer> workers;
    private final Duration taskTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger retries = new AtomicInteger();

    Dispatcher(List<Integer> workers, Duration taskTimeout) {
        if (workers.isEmpty()) {
            throw new IllegalArgumentException("Il faut au moins un Worker");
        }
        this.workers = List.copyOf(workers);
        this.taskTimeout = taskTimeout;
    }

    /**
     * Exécute {@code call} sur le Worker de rang {@code preferred}, puis sur les suivants en cas
     * d'échec. Chaque Worker est essayé au plus deux fois.
     *
     * @return le rang du Worker qui a répondu et son résultat
     */
    <T> Answer<T> run(int preferred, IntFunction<T> call) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < workers.size() * 2; attempt++) {
            int rank = (preferred + attempt) % workers.size();
            int worker = workers.get(rank);
            Future<T> future = executor.submit(() -> call.apply(worker));
            try {
                return new Answer<>(rank, future.get(taskTimeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                // On n'attend plus ce Worker ; s'il répond plus tard, sa réponse sera ignorée.
                lastFailure = new IllegalStateException("Worker " + worker + " trop lent");
            } catch (ExecutionException e) {
                lastFailure = e.getCause() instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Calcul interrompu", e);
            }
            retries.incrementAndGet();
        }
        throw new IllegalStateException("Aucun Worker n'a pu traiter la tâche", lastFailure);
    }

    /** Lance une action en parallèle des autres. */
    <T> Future<T> async(java.util.concurrent.Callable<T> action) {
        return executor.submit(action);
    }

    int retries() {
        return retries.get();
    }

    int workerCount() {
        return workers.size();
    }

    List<Integer> workers() {
        return workers;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    record Answer<T>(int workerRank, T value) {
    }
}
