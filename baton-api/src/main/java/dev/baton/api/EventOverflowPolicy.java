package dev.baton.api;

/** Which event is dropped when a run's event queue is full. */
public enum EventOverflowPolicy {
    /** The new event is dropped. The default. */
    DROP_NEWEST,
    /** The oldest queued event is dropped to make room for the new one. */
    DROP_OLDEST
}
