package com.telerad.order;

/** Side of the body, chosen on the order (not part of the procedure). */
public enum Laterality {
    NOT_APPLICABLE, LEFT, RIGHT, BILATERAL;

    /** Words added to the exam name, e.g. "X-ray knee AP (Left)"; empty when not applicable. */
    public String label() {
        return switch (this) {
            case LEFT -> "Left";
            case RIGHT -> "Right";
            case BILATERAL -> "Bilateral";
            case NOT_APPLICABLE -> "";
        };
    }
}
