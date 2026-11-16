package fr.cytech.dining.philosopher.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class GraphsTest {

    // 0-1, 0-2, 1-2, 3-3 (boucle), 1-2 (arête en double)
    private static final int[] EDGES = {0, 1, 0, 2, 1, 2, 3, 3, 1, 2};

    @Test
    void degresDUnPetitGraphe() {
        assertThat(Graphs.degrees(5, EDGES)).containsExactly(2, 3, 3, 2, 0);
    }

    @Test
    void laSommeDesDegresVautDeuxFoisLeNombreDAretes() {
        int[] edges = Graphs.randomEdges(100, 1_000, 42);
        long sum = 0;
        for (int degree : Graphs.degrees(100, edges)) {
            sum += degree;
        }
        assertThat(sum).isEqualTo(2_000);
    }

    @Test
    void leGraphAleatoireEstReproductible() {
        assertThat(Graphs.randomEdges(50, 200, 7)).isEqualTo(Graphs.randomEdges(50, 200, 7));
        assertThat(Graphs.randomEdges(50, 200, 7)).isNotEqualTo(Graphs.randomEdges(50, 200, 8));
    }

    @Test
    void lePartitionnementParAretesMetChaqueAreteDansExactementUneTranche() {
        int[] edges = Graphs.randomEdges(100, 1_001, 1);

        List<int[]> slices = Graphs.splitEdges(edges, 4);

        assertThat(slices).hasSize(4);
        int[] rebuilt = slices.stream().flatMapToInt(java.util.Arrays::stream).toArray();
        assertThat(rebuilt).as("ni arête perdue, ni arête dupliquée, ordre conservé").isEqualTo(edges);
        assertThat(slices).allSatisfy(slice -> {
            assertThat(slice.length % 2).isZero();
            assertThat(slice.length / 2).isBetween(250, 251); // tranches équilibrées
        });
    }

    @Test
    void plusDeTranchesQueDAretesDonneDesTranchesVides() {
        List<int[]> slices = Graphs.splitEdges(new int[] {0, 1}, 3);
        assertThat(slices.stream().mapToInt(slice -> slice.length).sum()).isEqualTo(2);
    }

    @Test
    void lePartitionnementParNoeudsRangeChaqueArcChezLeProprietaireDeSonOrigine() {
        int[] edges = {0, 5, 1, 2, 2, 0, 3, 3, 4, 1};

        List<int[]> slices = Graphs.splitBySource(edges, 2);

        assertThat(slices.get(0)).containsExactly(0, 5, 2, 0, 4, 1); // origines paires
        assertThat(slices.get(1)).containsExactly(1, 2, 3, 3);       // origines impaires
    }

    @Test
    void distanceParParcoursEnLargeur() {
        int[] chain = {0, 1, 1, 2, 2, 3, 0, 3}; // 0→1→2→3 et raccourci 0→3

        assertThat(Graphs.distance(5, chain, 0, 3)).isEqualTo(1);
        assertThat(Graphs.distance(5, chain, 0, 2)).isEqualTo(2);
        assertThat(Graphs.distance(5, chain, 0, 0)).isZero();
        assertThat(Graphs.distance(5, chain, 3, 0)).as("le graphe est orienté").isEqualTo(-1);
        assertThat(Graphs.distance(5, chain, 0, 4)).as("nœud isolé").isEqualTo(-1);
    }

    @Test
    void unGrapheInvalideEstRefuse() {
        assertThatThrownBy(() -> Graphs.validate(3, new int[] {0, 1, 2})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Graphs.validate(3, new int[] {0, 3})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Graphs.validate(0, new int[0])).isInstanceOf(IllegalArgumentException.class);
    }
}
