package fr.cytech.dining.philosopher.core;

import java.util.List;

/** Section 1, « philosophes gâtés » : aucune fourchette partagée, on mange immédiatement. */
public class SpoiledStrategy implements DiningStrategy {

    @Override
    public List<Integer> forks() {
        return List.of();
    }

    @Override
    public void acquire() {
        // Rien à obtenir.
    }

    @Override
    public void release() {
        // Rien à rendre.
    }
}
