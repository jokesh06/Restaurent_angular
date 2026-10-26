package com.example.demo.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.InventoryBalance;
import com.example.demo.entity.InventoryMovement;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.repository.InventoryBalanceRepository;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.WarehouseRepository;
import com.example.demo.entity.Warehouse;


import exception.InsufficientStockException;
import exception.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryMovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reserveStockForOrder(Long warehouseId, Map<Long, Integer> productQuantities, String orderNumber, Long userId) {
        List<Long> orderedProductIds = productQuantities.keySet().stream().sorted().toList();
        List<InventoryBalance> lockedBalances = inventoryBalanceRepository
            .findByWarehouseIdAndProductIdsOrderedWithPessimisticLock(warehouseId, orderedProductIds);

        Map<Long, InventoryBalance> balanceMap = lockedBalances.stream()
            .collect(Collectors.toMap(b -> b.getProduct().getId(), b -> b));

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        for (Long productId : orderedProductIds) {
            int requestedQty = productQuantities.get(productId);
            InventoryBalance balance = balanceMap.get(productId);

            if (balance == null || balance.getAvailableQty() < requestedQty) {
                int available = balance == null ? 0 : balance.getAvailableQty();
                throw new InsufficientStockException("Insufficient stock for product ID " + productId + 
                    ". Requested: " + requestedQty + ", Available: " + available);
            }

            balance.setReservedQty(balance.getReservedQty() + requestedQty);
            inventoryBalanceRepository.save(balance);

            InventoryMovement movement = InventoryMovement.builder()
                .movementType(InventoryMovement.MovementType.SALE_CONFIRMATION)
                .product(balance.getProduct())
                .warehouse(balance.getWarehouse())
                .onHandChange(0)
                .reservedChange(requestedQty)
                .referenceDocument(orderNumber)
                .reason("Reservation for Sales Order " + orderNumber)
                .user(user)
                .timestamp(LocalDateTime.now())
                .build();
            movementRepository.save(movement);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseReservationForOrder(Long warehouseId, Map<Long, Integer> productQuantities, String orderNumber, Long userId) {
        List<Long> orderedProductIds = productQuantities.keySet().stream().sorted().toList();
        List<InventoryBalance> lockedBalances = inventoryBalanceRepository
            .findByWarehouseIdAndProductIdsOrderedWithPessimisticLock(warehouseId, orderedProductIds);

        Map<Long, InventoryBalance> balanceMap = lockedBalances.stream()
            .collect(Collectors.toMap(b -> b.getProduct().getId(), b -> b));

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        for (Long productId : orderedProductIds) {
            int releaseQty = productQuantities.get(productId);
            InventoryBalance balance = balanceMap.get(productId);

            if (balance != null) {
                balance.setReservedQty(Math.max(0, balance.getReservedQty() - releaseQty));
                inventoryBalanceRepository.save(balance);

                InventoryMovement movement = InventoryMovement.builder()
                    .movementType(InventoryMovement.MovementType.SALE_CANCELLATION)
                    .product(balance.getProduct())
                    .warehouse(balance.getWarehouse())
                    .onHandChange(0)
                    .reservedChange(-releaseQty)
                    .referenceDocument(orderNumber)
                    .reason("Cancelled reservation for Sales Order " + orderNumber)
                    .user(user)
                    .timestamp(LocalDateTime.now())
                    .build();
                movementRepository.save(movement);
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void fulfillShipment(Long warehouseId, Map<Long, Integer> productQuantities, String shipmentNumber, Long userId) {
        List<Long> orderedProductIds = productQuantities.keySet().stream().sorted().toList();
        List<InventoryBalance> lockedBalances = inventoryBalanceRepository
            .findByWarehouseIdAndProductIdsOrderedWithPessimisticLock(warehouseId, orderedProductIds);

        Map<Long, InventoryBalance> balanceMap = lockedBalances.stream()
            .collect(Collectors.toMap(b -> b.getProduct().getId(), b -> b));

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        for (Long productId : orderedProductIds) {
            int qtyToShip = productQuantities.get(productId);
            InventoryBalance balance = balanceMap.get(productId);

            if (balance == null || balance.getOnHandQty() < qtyToShip || balance.getReservedQty() < qtyToShip) {
                throw new IllegalStateException("Stock state invalid for shipment fulfillment on product " + productId);
            }

            balance.setOnHandQty(balance.getOnHandQty() - qtyToShip);
            balance.setReservedQty(balance.getReservedQty() - qtyToShip);
            inventoryBalanceRepository.save(balance);

            InventoryMovement movement = InventoryMovement.builder()
                .movementType(InventoryMovement.MovementType.SALE_SHIPMENT)
                .product(balance.getProduct())
                .warehouse(balance.getWarehouse())
                .onHandChange(-qtyToShip)
                .reservedChange(-qtyToShip)
                .referenceDocument(shipmentNumber)
                .reason("Shipped under Shipment " + shipmentNumber)
                .user(user)
                .timestamp(LocalDateTime.now())
                .build();
            movementRepository.save(movement);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void receiveGoods(Long warehouseId, Long productId, int qtyReceived, String poNumber, Long userId) {
        InventoryBalance balance = inventoryBalanceRepository
            .findByWarehouseIdAndProductIdWithPessimisticLock(warehouseId, productId)
            .orElseGet(() -> {
            	Warehouse w = warehouseRepository.findById(warehouseId)
                    .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found"));
            	Product p = productRepository.findById(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
                return InventoryBalance.builder()
                    .warehouse(w)
                    .product(p)
                    .onHandQty(0)
                    .reservedQty(0)
                    .build();
            });

        balance.setOnHandQty(balance.getOnHandQty() + qtyReceived);
        inventoryBalanceRepository.save(balance);

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        InventoryMovement movement = InventoryMovement.builder()
            .movementType(InventoryMovement.MovementType.PURCHASE_RECEIPT)
            .product(balance.getProduct())
            .warehouse(balance.getWarehouse())
            .onHandChange(qtyReceived)
            .reservedChange(0)
            .referenceDocument(poNumber)
            .reason("Received goods for PO " + poNumber)
            .user(user)
            .timestamp(LocalDateTime.now())
            .build();
        movementRepository.save(movement);
    }
}