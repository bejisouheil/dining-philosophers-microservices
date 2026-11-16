package fr.cytech.dining.philosopher.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ForkAssignmentTest {

    @Test
    void chaquePhilosopheRecoitSaFourchetteEtLaSuivante() {
        assertThat(ForkAssignment.forksFor(0, 5)).containsExactly(0, 1);
        assertThat(ForkAssignment.forksFor(3, 5)).containsExactly(3, 4);
    }

    @Test
    void leDernierPhilosopheBoucleSurLaFourchetteZeroEtLaListeResteTriee() {
        assertThat(ForkAssignment.forksFor(4, 5)).containsExactly(0, 4);
    }

    @Test
    void chaqueFourchetteEstPartageeParExactementDeuxPhilosophes() {
        int count = 7;
        Map<Integer, Integer> users = new HashMap<>();
        for (int id = 0; id < count; id++) {
            ForkAssignment.forksFor(id, count).forEach(fork -> users.merge(fork, 1, Integer::sum));
        }
        assertThat(users).hasSize(count);
        assertThat(users.values()).containsOnly(2);
    }

    @Test
    void unPhilosopheSeulNePeutPasPartager() {
        assertThatThrownBy(() -> ForkAssignment.forksFor(0, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
