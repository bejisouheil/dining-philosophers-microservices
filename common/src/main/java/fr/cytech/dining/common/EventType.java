package fr.cytech.dining.common;

/** Événements émis par un philosophe : un au démarrage, puis trois à chaque cycle. */
public enum EventType {
    /**
     * Le service du philosophe (re)démarre : il pense et ne tient aucune fourchette. Après une
     * panne en plein repas, c'est ce qui indique au moniteur que le repas interrompu est terminé.
     */
    STARTED,
    /** Le philosophe a faim : il veut manger. */
    HUNGRY,
    /** Le philosophe commence à manger. */
    EATING,
    /** Le philosophe a fini de manger et retourne penser. */
    DONE
}
