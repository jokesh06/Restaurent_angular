package com.example.demo.service;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.CreateStockTransferRequest;
import com.example.demo.dto.StockTransferItemResponse;
import com.example.demo.dto.StockTransferResponse;
import com.example.demo.entity.InventoryBalance;
import com.example.demo.entity.InventoryMovement;
import com.example.demo.entity.StockTransfer;
import com.example.demo.entity.StockTransferItem;
import com.example.demo.entity.*;
import com.example.demo.entity.Warehouse;
import com.example.demo.repository.InventoryBalanceRepository;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.StockTransferRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.WarehouseRepository;

import exception.InsufficientStockException;
import exception.InvalidOperationException;
import exception.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockTransferService {

    private final StockTransferRepository stockTransferRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryMovementRepository movementRepository;

    @Transactional
    public StockTransferResponse createTransfer(CreateStockTransferRequest request, Long userId) {
        if (Objects.equals(request.getSourceWarehouseId(), request.getDestinationWarehouseId())) {
            throw new InvalidOperationException("Source and destination warehouses must be different");
        }

        Warehouse source = warehouseRepository.findById(request.getSourceWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Source warehouse not found: " + request.getSourceWarehouseId()));
        Warehouse dest = warehouseRepository.findById(request.getDestinationWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Destination warehouse not found: " + request.getDestinationWarehouseId()));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        StockTransfer transfer = StockTransfer.builder()
                .transferNumber("TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .sourceWarehouse(source)
                .destinationWarehouse(dest)
                .status(StockTransfer.TransferStatus.DRAFT)
                .createdBy(user)
                .build();

        List<StockTransferItem> items = new ArrayList<>();
        for (var itemReq : request.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemReq.getProductId()));

            items.add(StockTransferItem.builder()
                    .stockTransfer(transfer)
                    .product(product)
                    .quantity(itemReq.getQuantity())
                    .build());
        }

        transfer.setItems(items);
        return mapToResponse(stockTransferRepository.save(transfer));
    }

    @Transactional
    public StockTransferResponse dispatchTransfer(Long transferId, Long userId) {
        StockTransfer transfer = stockTransferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found: " + transferId));

        if (transfer.getStatus() != StockTransfer.TransferStatus.DRAFT) {
            throw new InvalidOperationException("Only DRAFT transfers can be dispatched");
        }

        User user = userRepository.findById(userId).orElseThrow();
        Long sourceWarehouseId = transfer.getSourceWarehouse().getId();

        // 1. Lock source warehouse records ordered by product ID to avoid deadlocks
        List<Long> productIds = transfer.getItems().stream()
                .map(i -> i.getProduct().getId())
                .sorted()
                .toList();

        List<InventoryBalance> sourceBalances = inventoryBalanceRepository
                .findByWarehouseIdAndProductIdsOrderedWithPessimisticLock(sourceWarehouseId, productIds);

        Map<Long, InventoryBalance> balanceMap = sourceBalances.stream()
                .collect(Collectors.toMap(b -> b.getProduct().getId(), b -> b));

        // 2. Validate availability and deduct stock at source warehouse
        for (StockTransferItem item : transfer.getItems()) {
            InventoryBalance balance = balanceMap.get(item.getProduct().getId());
            if (balance == null || balance.getAvailableQty() < item.getQuantity()) {
                int available = balance == null ? 0 : balance.getAvailableQty();
                throw new InsufficientStockException("Insufficient available stock at source warehouse for " 
                        + item.getProduct().getName() + ". Required: " + item.getQuantity() + ", Available: " + available);
            }

            balance.setOnHandQty(balance.getOnHandQty() - item.getQuantity());
            inventoryBalanceRepository.save(balance);

            // Audit movement ledger: TRANSFER_OUT
            movementRepository.save(InventoryMovement.builder()
                    .movementType(InventoryMovement.MovementType.TRANSFER_OUT)
                    .product(item.getProduct())
                    .warehouse(transfer.getSourceWarehouse())
                    .onHandChange(-item.getQuantity())
                    .reservedChange(0)
                    .referenceDocument(transfer.getTransferNumber())
                    .reason("Dispatched to warehouse " + transfer.getDestinationWarehouse().getCode())
                    .user(user)
                    .timestamp(LocalDateTime.now())
                    .build());
        }

        transfer.setStatus(StockTransfer.TransferStatus.DISPATCHED);
        transfer.setDispatchedAt(LocalDateTime.now());
        return mapToResponse(stockTransferRepository.save(transfer));
    }

    @Transactional
    public StockTransferResponse receiveTransfer(Long transferId, Long userId) {
        StockTransfer transfer = stockTransferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found: " + transferId));

        if (transfer.getStatus() != StockTransfer.TransferStatus.DISPATCHED) {
            throw new InvalidOperationException("Only DISPATCHED transfers can be received");
        }

        User user = userRepository.findById(userId).orElseThrow();
        Long destWarehouseId = transfer.getDestinationWarehouse().getId();

        // Credit destination warehouse balance and record ledger entries
        for (StockTransferItem item : transfer.getItems()) {
            InventoryBalance balance = inventoryBalanceRepository
                    .findByWarehouseIdAndProductIdWithPessimisticLock(destWarehouseId, item.getProduct().getId())
                    .orElseGet(() -> InventoryBalance.builder()
                            .warehouse(transfer.getDestinationWarehouse())
                            .product(item.getProduct())
                            .onHandQty(0)
                            .reservedQty(0)
                            .build());

            balance.setOnHandQty(balance.getOnHandQty() + item.getQuantity());
            inventoryBalanceRepository.save(balance);

            // Audit movement ledger: TRANSFER_IN
            movementRepository.save(InventoryMovement.builder()
                    .movementType(InventoryMovement.MovementType.TRANSFER_IN)
                    .product(item.getProduct())
                    .warehouse(transfer.getDestinationWarehouse())
                    .onHandChange(item.getQuantity())
                    .reservedChange(0)
                    .referenceDocument(transfer.getTransferNumber())
                    .reason("Received from warehouse " + transfer.getSourceWarehouse().getCode())
                    .user(user)
                    .timestamp(LocalDateTime.now())
                    .build());
        }

        transfer.setStatus(StockTransfer.TransferStatus.RECEIVED);
        transfer.setReceivedAt(LocalDateTime.now());
        return mapToResponse(stockTransferRepository.save(transfer));
    }

    public StockTransferResponse mapToResponse(StockTransfer transfer) {
        return StockTransferResponse.builder()
                .id(transfer.getId())
                .transferNumber(transfer.getTransferNumber())
                .sourceWarehouseId(transfer.getSourceWarehouse().getId())
                .sourceWarehouseName(transfer.getSourceWarehouse().getName())
                .destinationWarehouseId(transfer.getDestinationWarehouse().getId())
                .destinationWarehouseName(transfer.getDestinationWarehouse().getName())
                .status(transfer.getStatus().name())
                .items(transfer.getItems().stream().map(i -> StockTransferItemResponse.builder()
                        .id(i.getId())
                        .productId(i.getProduct().getId())
                        .productSku(i.getProduct().getSku())
                        .productName(i.getProduct().getName())
                        .quantity(i.getQuantity())
                        .build()).toList())
                .dispatchedAt(transfer.getDispatchedAt())
                .receivedAt(transfer.getReceivedAt())
                .createdAt(transfer.getCreatedAt())
                .build();
    }
}