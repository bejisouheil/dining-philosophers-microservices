package fr.cytech.dining.common;

/** Les trois événements émis par un philosophe à chaque cycle. */
public enum EventType {
    /** Le philosophe a faim : il veut manger. */
    HUNGRY,
    /** Le philosophe commence à manger. */
    EATING,
    /** Le philosophe a fini de manger et retourne penser. */
    DONE
}
