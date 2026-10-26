package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_movements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false)
    private MovementType movementType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "on_hand_change", nullable = false)
    private Integer onHandChange;

    @Column(name = "reserved_change", nullable = false)
    private Integer reservedChange;

    @Column(name = "reference_document", nullable = false)
    private String referenceDocument;

    @Column(name = "reason")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    public enum MovementType {
        PURCHASE_RECEIPT,
        SALE_CONFIRMATION,
        SALE_SHIPMENT,
        SALE_CANCELLATION,
        TRANSFER_OUT,
        TRANSFER_IN,
        MANUAL_ADJUSTMENT
    }
}