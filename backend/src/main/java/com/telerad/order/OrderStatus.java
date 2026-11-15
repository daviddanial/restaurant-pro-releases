package com.telerad.order;

/**
 * Order status, in workflow order (KB note 15 §6). A status never goes backwards: {@link #rank()}
 * decides whether an event may move it. CANCELLED and NO_SHOW are side exits.
 */
public enum OrderStatus {
    SCHEDULED, ARRIVED, SCANNING, SCANNED, READY, REPORTING, SIGNED, DELIVERED, CANCELLED, NO_SHOW;

    public int rank() {
        return switch (this) {
            case CANCELLED, NO_SHOW -> 100;
            default -> ordinal();
        };
    }

    public boolean isClosed() {
        return this == CANCELLED || this == NO_SHOW;
    }

    public boolean isBefore(OrderStatus other) {
        return !isClosed() && rank() < other.rank();
    }
}
