package com.example.demo.controller;


import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.example.demo.dto.InventoryBalanceResponse;
import com.example.demo.dto.InventoryMovementResponse;
import com.example.demo.dto.ManualAdjustmentRequest;
import com.example.demo.entity.InventoryBalance;
import com.example.demo.entity.InventoryMovement;
import com.example.demo.entity.User;
import com.example.demo.repository.InventoryBalanceRepository;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.WarehouseRepository;

import exception.InsufficientStockException;
import exception.ResourceNotFoundException;
import jakarta.validation.Valid;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryMovementRepository movementRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;

    @GetMapping("/warehouse/{warehouseId}")
    public ResponseEntity<List<InventoryBalanceResponse>> getStockByWarehouse(@PathVariable Long warehouseId) {
        List<InventoryBalance> balances = inventoryBalanceRepository.findByWarehouseIdWithProductAndWarehouse(warehouseId);
        return ResponseEntity.ok(balances.stream().map(this::mapBalanceToResponse).toList());
    }

    @GetMapping("/balance")
    public ResponseEntity<InventoryBalanceResponse> getProductStockAtWarehouse(
            @RequestParam Long warehouseId,
            @RequestParam Long productId) {

        InventoryBalance balance = inventoryBalanceRepository
                .findByWarehouseIdAndProductId(warehouseId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("No inventory record found for warehouse " + warehouseId + " and product " + productId));

        return ResponseEntity.ok(mapBalanceToResponse(balance));
    }

    @PostMapping("/adjust")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    @Transactional
    public ResponseEntity<InventoryBalanceResponse> adjustStock(
            @Valid @RequestBody ManualAdjustmentRequest request,
            @AuthenticationPrincipal User user) {

        InventoryBalance balance = inventoryBalanceRepository
                .findByWarehouseIdAndProductIdWithPessimisticLock(request.getWarehouseId(), request.getProductId())
                .orElseGet(() -> {
                    var w = warehouseRepository.findById(request.getWarehouseId())
                            .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + request.getWarehouseId()));
                    var p = productRepository.findById(request.getProductId())
                            .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + request.getProductId()));
                    return InventoryBalance.builder()
                            .warehouse(w)
                            .product(p)
                            .onHandQty(0)
                            .reservedQty(0)
                            .build();
                });

        int newOnHand = balance.getOnHandQty() + request.getQuantityChange();

        // Enforce invariants: on-hand >= 0 and on-hand >= reserved
        if (newOnHand < 0 || newOnHand < balance.getReservedQty()) {
            throw new InsufficientStockException("Manual adjustment rejected: Cannot reduce on-hand stock below reserved quantity (" 
                    + balance.getReservedQty() + " units currently reserved)");
        }

        balance.setOnHandQty(newOnHand);
        InventoryBalance saved = inventoryBalanceRepository.save(balance);

        // Record audit ledger
        InventoryMovement movement = InventoryMovement.builder()
                .movementType(InventoryMovement.MovementType.MANUAL_ADJUSTMENT)
                .product(balance.getProduct())
                .warehouse(balance.getWarehouse())
                .onHandChange(request.getQuantityChange())
                .reservedChange(0)
                .referenceDocument("MANUAL-ADJ-" + System.currentTimeMillis())
                .reason(request.getReason())
                .user(user)
                .timestamp(LocalDateTime.now())
                .build();
        movementRepository.save(movement);

        return ResponseEntity.ok(mapBalanceToResponse(saved));
    }

    @GetMapping("/movements")
    public ResponseEntity<Page<InventoryMovementResponse>> getMovements(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @PageableDefault(size = 20, sort = "timestamp", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<InventoryMovement> movements = movementRepository.filterMovements(warehouseId, productId, startDate, endDate, pageable);
        return ResponseEntity.ok(movements.map(this::mapMovementToResponse));
    }

    private InventoryBalanceResponse mapBalanceToResponse(InventoryBalance b) {
        return InventoryBalanceResponse.builder()
                .id(b.getId())
                .warehouseId(b.getWarehouse().getId())
                .warehouseCode(b.getWarehouse().getCode())
                .warehouseName(b.getWarehouse().getName())
                .productId(b.getProduct().getId())
                .productSku(b.getProduct().getSku())
                .productName(b.getProduct().getName())
                .onHandQty(b.getOnHandQty())
                .reservedQty(b.getReservedQty())
                .availableQty(b.getAvailableQty())
                .updatedAt(b.getUpdatedAt())
                .build();
    }

    private InventoryMovementResponse mapMovementToResponse(InventoryMovement m) {
        return InventoryMovementResponse.builder()
                .id(m.getId())
                .movementType(m.getMovementType().name())
                .productId(m.getProduct().getId())
                .productSku(m.getProduct().getSku())
                .productName(m.getProduct().getName())
                .warehouseId(m.getWarehouse().getId())
                .warehouseCode(m.getWarehouse().getCode())
                .onHandChange(m.getOnHandChange())
                .reservedChange(m.getReservedChange())
                .referenceDocument(m.getReferenceDocument())
                .reason(m.getReason())
                .performedByUsername(m.getUser().getUsername())
                .timestamp(m.getTimestamp())
                .build();
    }
}