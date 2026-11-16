package fr.cytech.dining.philosopher.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Outils sur les graphes. Un graphe est une liste d'arêtes stockée à plat dans un tableau
 * d'entiers : {@code [u0, v0, u1, v1, ...]}. C'est compact en mémoire et en JSON.
 *
 * <p>Contient aussi les algorithmes séquentiels de référence (degrés, parcours en largeur) : ils
 * servent à vérifier les résultats distribués et à mesurer le gain du parallélisme.
 */
public final class Graphs {

    private Graphs() {
    }

    /** Graphe aléatoire reproductible : {@code edgeCount} arêtes tirées au hasard entre {@code nodes} nœuds. */
    public static int[] randomEdges(int nodes, int edgeCount, long seed) {
        Random random = new Random(seed);
        int[] edges = new int[edgeCount * 2];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = random.nextInt(nodes);
        }
        return edges;
    }

    /** Vérifie que le tableau est une liste d'arêtes valide pour un graphe de {@code nodes} nœuds. */
    public static void validate(int nodes, int[] edges) {
        if (nodes < 1) {
            throw new IllegalArgumentException("Le graphe doit avoir au moins un nœud");
        }
        if (edges.length % 2 != 0) {
            throw new IllegalArgumentException("La liste d'arêtes doit contenir un nombre pair d'entiers");
        }
        for (int node : edges) {
            if (node < 0 || node >= nodes) {
                throw new IllegalArgumentException("Nœud hors du graphe : " + node);
            }
        }
    }

    // --- Partitionnement ---

    /**
     * Partitionnement par arêtes (calcul des degrés) : le tableau est découpé en {@code parts}
     * tranches de taille égale. Chaque arête appartient à <strong>exactement une</strong> tranche,
     * même si ses deux extrémités sont « loin » l'une de l'autre : aucune arête n'est donc comptée
     * deux fois.
     */
    public static List<int[]> splitEdges(int[] edges, int parts) {
        int edgeCount = edges.length / 2;
        List<int[]> result = new ArrayList<>(parts);
        for (int part = 0; part < parts; part++) {
            int from = (int) ((long) edgeCount * part / parts);
            int to = (int) ((long) edgeCount * (part + 1) / parts);
            result.add(Arrays.copyOfRange(edges, from * 2, to * 2));
        }
        return result;
    }

    /** Propriétaire d'un nœud dans un partitionnement par nœuds. */
    public static int owner(int node, int parts) {
        return node % parts;
    }

    /**
     * Partitionnement par nœuds (accessibilité) : chaque partition reçoit les arcs qui
     * <strong>partent</strong> des nœuds dont elle est propriétaire. Le Worker d'une partition sait
     * donc répondre seul à « quels sont les successeurs de ces nœuds ? ».
     */
    public static List<int[]> splitBySource(int[] edges, int parts) {
        int[] sizes = new int[parts];
        for (int i = 0; i < edges.length; i += 2) {
            sizes[owner(edges[i], parts)] += 2;
        }
        List<int[]> result = new ArrayList<>(parts);
        for (int part = 0; part < parts; part++) {
            result.add(new int[sizes[part]]);
        }
        int[] cursor = new int[parts];
        for (int i = 0; i < edges.length; i += 2) {
            int part = owner(edges[i], parts);
            result.get(part)[cursor[part]++] = edges[i];
            result.get(part)[cursor[part]++] = edges[i + 1];
        }
        return result;
    }

    // --- Références séquentielles ---

    /**
     * Degré de chaque nœud d'un graphe non orienté : chaque arête (u, v) ajoute 1 au degré de u et
     * 1 au degré de v (une boucle u-u compte donc pour 2).
     */
    public static int[] degrees(int nodes, int[] edges) {
        int[] degrees = new int[nodes];
        for (int node : edges) {
            degrees[node]++;
        }
        return degrees;
    }

    /**
     * Accessibilité dans un graphe orienté par parcours en largeur.
     *
     * @return la longueur du plus court chemin de {@code source} à {@code target}, ou -1 s'il
     *         n'existe aucun chemin
     */
    public static int distance(int nodes, int[] edges, int source, int target) {
        int[] outDegree = new int[nodes];
        for (int i = 0; i < edges.length; i += 2) {
            outDegree[edges[i]]++;
        }
        int[][] successors = new int[nodes][];
        for (int node = 0; node < nodes; node++) {
            successors[node] = new int[outDegree[node]];
        }
        int[] cursor = new int[nodes];
        for (int i = 0; i < edges.length; i += 2) {
            successors[edges[i]][cursor[edges[i]]++] = edges[i + 1];
        }
        int[] distance = new int[nodes];
        Arrays.fill(distance, -1);
        distance[source] = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            if (node == target) {
                return distance[node];
            }
            for (int next : successors[node]) {
                if (distance[next] < 0) {
                    distance[next] = distance[node] + 1;
                    queue.add(next);
                }
            }
        }
        return -1;
    }
}
