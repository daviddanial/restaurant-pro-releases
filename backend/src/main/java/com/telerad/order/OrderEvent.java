package com.telerad.order;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One line of an order's history. */
@Entity
@Table(name = "order_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {
    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private Instant at;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(length = 512)
    private String detail;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (at == null) at = Instant.now();
    }
}
