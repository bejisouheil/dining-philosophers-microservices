package fr.cytech.dining.philosopher.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fr.cytech.dining.philosopher.graph.GraphMaster.DegreeOutcome;
import fr.cytech.dining.philosopher.graph.GraphMaster.ReachabilityOutcome;
import fr.cytech.dining.philosopher.graph.Tasks.DegreeResult;
import fr.cytech.dining.philosopher.graph.Tasks.DegreeTask;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandResult;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandTask;
import fr.cytech.dining.philosopher.graph.Tasks.LoadTask;

/**
 * Tests du Master avec de faux Workers en mémoire : on vérifie les résultats contre les
 * algorithmes séquentiels, et le comportement quand un Worker tombe en panne ou répond en retard.
 */
@Timeout(60)
class GraphMasterTest {

    private static final Duration PATIENT = Duration.ofSeconds(10);

    /** Workers en mémoire : chaque identifiant a son propre {@link GraphWorker}. */
    private static final class LocalWorkers implements WorkerClient {
        final Map<Integer, GraphWorker> workers = new HashMap<>();
        final AtomicInteger degreeCalls = new AtomicInteger();

        LocalWorkers(int... ids) {
            for (int id : ids) {
                workers.put(id, new GraphWorker());
            }
        }

        @Override
        public DegreeResult degrees(int worker, DegreeTask task) {
            degreeCalls.incrementAndGet();
            return workers.get(worker).degrees(task);
        }

        @Override
        public void load(int worker, LoadTask task) {
            workers.get(worker).load(task);
        }

        @Override
        public ExpandResult expand(int worker, ExpandTask task) {
            return workers.get(worker).expand(task);
        }

        @Override
        public void forget(int worker, String jobId) {
            workers.get(worker).forget(jobId);
        }
    }

    @Nested
    @DisplayName("Calcul des degrés")
    class Degres {

        @Test
        void leResultatDistribueEstIdentiqueAuCalculSequentiel() {
            int nodes = 500;
            int[] edges = Graphs.randomEdges(nodes, 20_000, 3);
            LocalWorkers workers = new LocalWorkers(1, 2, 3, 4);

            DegreeOutcome outcome = new GraphMaster(workers, List.of(1, 2, 3, 4)).degrees(nodes, edges, 4, PATIENT);

            assertThat(outcome.degrees()).isEqualTo(Graphs.degrees(nodes, edges));
            assertThat(outcome.consistent()).isTrue();
            assertThat(outcome.sumOfDegrees()).isEqualTo(40_000);
            assertThat(outcome.retries()).isZero();
            assertThat(workers.degreeCalls.get()).isEqualTo(4);
        }

        @Test
        @DisplayName("Pas de double comptage : une arête entre deux « régions » du graphe compte une seule fois")
        void pasDeDoubleComptageDesAretesEntrePartitions() {
            // Deux arêtes seulement, une par tranche ; les nœuds 0 et 3 apparaissent dans les deux.
            int[] edges = {0, 3, 3, 0};

            DegreeOutcome outcome = new GraphMaster(new LocalWorkers(1, 2), List.of(1, 2)).degrees(4, edges, 2,
                    PATIENT);

            assertThat(outcome.degrees()).containsExactly(2, 0, 0, 2);
            assertThat(outcome.consistent()).isTrue();
        }

        @Test
        void plusDeTranchesQueDeWorkersEtInversement() {
            int nodes = 50;
            int[] edges = Graphs.randomEdges(nodes, 999, 5);
            int[] expected = Graphs.degrees(nodes, edges);

            assertThat(new GraphMaster(new LocalWorkers(1, 2), List.of(1, 2)).degrees(nodes, edges, 7, PATIENT)
                    .degrees()).isEqualTo(expected);
            assertThat(new GraphMaster(new LocalWorkers(1, 2, 3, 4), List.of(1, 2, 3, 4))
                    .degrees(nodes, edges, 1, PATIENT).degrees()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Worker en panne : sa tâche est relancée sur un autre et le résultat reste exact")
        void workerEnPanne() {
            int nodes = 200;
            int[] edges = Graphs.randomEdges(nodes, 5_000, 9);
            LocalWorkers workers = new LocalWorkers(1, 2, 3);
            workers.workers.get(2).chaos(0, 100); // le Worker 2 échoue à chaque appel

            DegreeOutcome outcome = new GraphMaster(workers, List.of(1, 2, 3)).degrees(nodes, edges, 3, PATIENT);

            assertThat(outcome.degrees()).isEqualTo(Graphs.degrees(nodes, edges));
            assertThat(outcome.consistent()).isTrue();
            assertThat(outcome.retries()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Worker en retard : la tâche est relancée et la réponse tardive n'est pas comptée deux fois")
        void workerEnRetard() throws InterruptedException {
            int nodes = 200;
            int[] edges = Graphs.randomEdges(nodes, 5_000, 11);
            LocalWorkers workers = new LocalWorkers(1, 2, 3);
            workers.workers.get(1).chaos(600, 0); // le Worker 1 répond en 600 ms

            DegreeOutcome outcome = new GraphMaster(workers, List.of(1, 2, 3)).degrees(nodes, edges, 3,
                    Duration.ofMillis(150)); // le Master n'attend que 150 ms
            Thread.sleep(800); // on laisse la réponse tardive du Worker 1 arriver

            assertThat(outcome.retries()).isGreaterThanOrEqualTo(1);
            assertThat(outcome.degrees()).as("exact malgré le retard").isEqualTo(Graphs.degrees(nodes, edges));
            assertThat(outcome.sumOfDegrees()).as("rien n'est compté deux fois").isEqualTo(10_000);
        }

        @Test
        void siTousLesWorkersSontEnPanneLeCalculEchoueProprement() {
            LocalWorkers workers = new LocalWorkers(1, 2);
            workers.workers.values().forEach(worker -> worker.chaos(0, 1_000));

            assertThatThrownBy(() -> new GraphMaster(workers, List.of(1, 2)).degrees(10, new int[] {0, 1}, 2, PATIENT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Aucun Worker");
        }

        @Test
        void leMasterEnvoieAChaqueWorkerUniquementSaTranche() {
            WorkerClient client = mock(WorkerClient.class);
            when(client.degrees(anyInt(), any())).thenAnswer(call -> new GraphWorker().degrees(call.getArgument(1)));
            int[] edges = {0, 1, 1, 2, 2, 3, 3, 0};

            new GraphMaster(client, List.of(5, 6)).degrees(4, edges, 2, PATIENT);

            verify(client).degrees(eq(5), org.mockito.ArgumentMatchers.argThat(
                    task -> task.partition() == 0 && java.util.Arrays.equals(task.edges(), new int[] {0, 1, 1, 2})));
            verify(client).degrees(eq(6), org.mockito.ArgumentMatchers.argThat(
                    task -> task.partition() == 1 && java.util.Arrays.equals(task.edges(), new int[] {2, 3, 3, 0})));
        }

        @Test
        void sansWorkerLeCalculEstRefuse() {
            assertThatThrownBy(() -> new GraphMaster(new LocalWorkers(), List.of()).degrees(2, new int[] {0, 1}, 1,
                    PATIENT)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Accessibilité")
    class Accessibilite {

        private final int[] chain = {0, 1, 1, 2, 2, 3, 3, 4, 6, 7}; // 0→1→2→3→4, et 6→7 à part

        private ReachabilityOutcome reach(int[] edges, int nodes, int source, int target) {
            return new GraphMaster(new LocalWorkers(1, 2, 3), List.of(1, 2, 3)).reachability(nodes, edges, source,
                    target, 3, PATIENT);
        }

        @Test
        void unCheminExiste() {
            ReachabilityOutcome outcome = reach(chain, 8, 0, 4);

            assertThat(outcome.reachable()).isTrue();
            assertThat(outcome.distance()).isEqualTo(4);
            assertThat(outcome.rounds()).isEqualTo(4);
        }

        @Test
        void aucunCheminVersUneAutreComposante() {
            ReachabilityOutcome outcome = reach(chain, 8, 0, 7);

            assertThat(outcome.reachable()).isFalse();
            assertThat(outcome.distance()).isEqualTo(-1);
            assertThat(outcome.visited()).isEqualTo(5); // 0, 1, 2, 3, 4
        }

        @Test
        void leGrapheEstOriente() {
            assertThat(reach(chain, 8, 4, 0).reachable()).isFalse();
        }

        @Test
        void unNoeudEstAccessibleDepuisLuiMeme() {
            ReachabilityOutcome outcome = reach(chain, 8, 5, 5);

            assertThat(outcome.reachable()).isTrue();
            assertThat(outcome.distance()).isZero();
            assertThat(outcome.rounds()).isZero();
        }

        @Test
        void leResultatDistribueEstIdentiqueAuParcoursSequentielSurDesGraphesAleatoires() {
            Random random = new Random(17);
            for (int round = 0; round < 40; round++) {
                int nodes = 20 + random.nextInt(200);
                int[] edges = Graphs.randomEdges(nodes, nodes + random.nextInt(nodes * 2), round);
                int source = random.nextInt(nodes);
                int target = random.nextInt(nodes);

                ReachabilityOutcome outcome = reach(edges, nodes, source, target);

                assertThat(outcome.distance()).as("graphe %d, %d vers %d", round, source, target)
                        .isEqualTo(Graphs.distance(nodes, edges, source, target));
            }
        }

        @Test
        @DisplayName("Worker en panne pendant le parcours : sa partition est rechargée sur un autre")
        void workerEnPannePendantLeParcours() {
            int nodes = 300;
            int[] edges = Graphs.randomEdges(nodes, 900, 23);
            LocalWorkers workers = new LocalWorkers(1, 2, 3);
            workers.workers.get(3).chaos(0, 1_000); // en panne dès le début et pour de bon

            ReachabilityOutcome outcome = new GraphMaster(workers, List.of(1, 2, 3)).reachability(nodes, edges, 0, 150,
                    3, PATIENT);

            assertThat(outcome.distance()).isEqualTo(Graphs.distance(nodes, edges, 0, 150));
            assertThat(outcome.retries()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Worker redémarré : il a perdu sa partition, le Master la lui renvoie")
        void workerRedemarre() {
            WorkerClient client = mock(WorkerClient.class);
            GraphWorker real = new GraphWorker();
            AtomicInteger expands = new AtomicInteger();
            org.mockito.Mockito.doAnswer(call -> {
                real.load(call.getArgument(1));
                return null;
            }).when(client).load(anyInt(), any());
            when(client.expand(anyInt(), any())).thenAnswer(call -> {
                ExpandTask task = call.getArgument(1);
                if (expands.incrementAndGet() == 2) {
                    real.forget(task.jobId()); // redémarrage : la mémoire du Worker est vide
                }
                return real.expand(task);
            });

            ReachabilityOutcome outcome = new GraphMaster(client, List.of(1)).reachability(8, chain, 0, 4, 1, PATIENT);

            assertThat(outcome.distance()).isEqualTo(4);
            verify(client, org.mockito.Mockito.times(2)).load(eq(1), any()); // chargée, perdue, rechargée
        }

        @Test
        void aLaFinLesWorkersLiberentLaMemoireDuCalcul() {
            LocalWorkers workers = new LocalWorkers(1, 2, 3);

            new GraphMaster(workers, List.of(1, 2, 3)).reachability(8, chain, 0, 4, 3, PATIENT);

            assertThat(workers.workers.values()).allSatisfy(worker -> assertThat(worker.loadedPartitions()).isZero());
        }

        @Test
        void seulsLesWorkersConcernesParLaFrontiereSontSollicites() {
            WorkerClient client = mock(WorkerClient.class);
            when(client.expand(anyInt(), any())).thenReturn(new ExpandResult(new int[0]));

            new GraphMaster(client, List.of(1, 2, 3)).reachability(9, new int[] {0, 3}, 0, 8, 3, PATIENT);

            verify(client, atLeastOnce()).expand(eq(1), any()); // la source 0 appartient à la partition 0
            verify(client, never()).expand(eq(2), any());
            verify(client, never()).expand(eq(3), any());
        }

        @Test
        void uneSourceOuUneCibleHorsDuGrapheEstRefusee() {
            assertThatThrownBy(() -> reach(chain, 8, 0, 8)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> reach(chain, 8, -1, 2)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
