package fr.cytech.dining.philosopher.graph;

/** Messages échangés entre le Master et les Workers. */
public final class Tasks {

    private Tasks() {
    }

    /** « Compte les degrés dans cette tranche d'arêtes. » */
    public record DegreeTask(String jobId, int partition, int[] edges) {
    }

    /** Degrés partiels : {@code degrees[i]} est la contribution de la tranche au nœud {@code nodes[i]}. */
    public record DegreeResult(String jobId, int partition, int[] nodes, int[] degrees) {
    }

    /** « Garde en mémoire les arcs sortants de cette partition. » */
    public record LoadTask(String jobId, int partition, int[] edges) {
    }

    /** « Donne les successeurs de ces nœuds de ta partition. » */
    public record ExpandTask(String jobId, int partition, int[] frontier) {
    }

    public record ExpandResult(int[] neighbours) {
    }

    /** Le Worker n'a pas (ou plus) la partition demandée : il a redémarré, il faut la lui renvoyer. */
    public static class PartitionNotLoadedException extends RuntimeException {
        public PartitionNotLoadedException(String jobId, int partition) {
            super("Partition " + partition + " du calcul " + jobId + " non chargée");
        }
    }
}
