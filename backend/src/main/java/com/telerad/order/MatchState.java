package com.telerad.order;

/** How a study relates to an order (KB note 15 §4). */
public enum MatchState {
    /** Linked to an order: accession and patient ID match, or a person linked it. */
    LINKED,
    /** Accession found but the patient ID differs (or that order already has a study): a person decides. */
    NEEDS_CHECK,
    /** No accession; exactly one open order fits the patient ID, modality and day: a person confirms. */
    SUGGESTED,
    /** Nothing matches. */
    UNMATCHED,
    /** A person released it for reading without an order (e.g. emergency). */
    RELEASED;

    /** The study waits for a person before it can be read. */
    public boolean isHeld() {
        return this == NEEDS_CHECK || this == SUGGESTED || this == UNMATCHED;
    }
}
