package com.example.demo.service;


import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.CreatePurchaseOrderRequest;
import com.example.demo.dto.PurchaseOrderItemResponse;
import com.example.demo.dto.PurchaseOrderResponse;
import com.example.demo.dto.ReceiveGoodsRequest;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.PurchaseOrderItem;
import com.example.demo.entity.Supplier;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.PurchaseOrderItemRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.repository.SupplierRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.WarehouseRepository;
import com.example.demo.entity.User;
import com.example.demo.entity.Warehouse;
import com.example.demo.entity.Product;

import exception.InvalidOperationException;
import exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupplierRepository supplierRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;

    @Transactional
    public PurchaseOrderResponse createPurchaseOrder(CreatePurchaseOrderRequest request, Long userId) {
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id: " + request.getSupplierId()));
        Warehouse warehouse = warehouseRepository.findById(request.getDestinationWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with id: " + request.getDestinationWarehouseId()));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        PurchaseOrder po = PurchaseOrder.builder()
                .poNumber("PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .supplier(supplier)
                .destinationWarehouse(warehouse)
                .status(PurchaseOrder.POStatus.DRAFT)
                .createdBy(user)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        List<PurchaseOrderItem> items = new ArrayList<>();

        for (var itemReq : request.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + itemReq.getProductId()));

            BigDecimal lineTotal = itemReq.getUnitCost().multiply(BigDecimal.valueOf(itemReq.getOrderedQty()));
            total = total.add(lineTotal);

            items.add(PurchaseOrderItem.builder()
                    .purchaseOrder(po)
                    .product(product)
                    .orderedQty(itemReq.getOrderedQty())
                    .receivedQty(0)
                    .unitCost(itemReq.getUnitCost())
                    .build());
        }

        po.setTotalAmount(total);
        po.setItems(items);
        return mapToResponse(purchaseOrderRepository.save(po));
    }

    @Transactional
    public PurchaseOrderResponse submitPurchaseOrder(Long poId) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + poId));

        if (po.getStatus() != PurchaseOrder.POStatus.DRAFT) {
            throw new InvalidOperationException("Only DRAFT purchase orders can be submitted");
        }

        po.setStatus(PurchaseOrder.POStatus.SUBMITTED);
        return mapToResponse(purchaseOrderRepository.save(po));
    }

    @Transactional
    public PurchaseOrderResponse receiveGoods(Long poId, ReceiveGoodsRequest request, Long userId) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + poId));

        if (po.getStatus() != PurchaseOrder.POStatus.SUBMITTED && po.getStatus() != PurchaseOrder.POStatus.PARTIALLY_RECEIVED) {
            throw new InvalidOperationException("Goods can only be received against SUBMITTED or PARTIALLY_RECEIVED purchase orders");
        }

        Map<Long, PurchaseOrderItem> poItemsMap = new HashMap<>();
        for (PurchaseOrderItem item : po.getItems()) {
            poItemsMap.put(item.getId(), item);
        }

        for (var receipt : request.getReceipts()) {
            PurchaseOrderItem item = poItemsMap.get(receipt.getPurchaseOrderItemId());
            if (item == null) {
                throw new ResourceNotFoundException("PO Item " + receipt.getPurchaseOrderItemId() + " does not belong to PO " + poId);
            }

            int newTotalReceived = item.getReceivedQty() + receipt.getQuantityReceived();
            if (newTotalReceived > item.getOrderedQty()) {
                throw new InvalidOperationException("Cannot receive more units than ordered. Item: " 
                        + item.getProduct().getName() + " [Ordered: " + item.getOrderedQty() 
                        + ", Previously Received: " + item.getReceivedQty() 
                        + ", Attempted: " + receipt.getQuantityReceived() + "]");
            }

            item.setReceivedQty(newTotalReceived);
            purchaseOrderItemRepository.save(item);

            // Increase on-hand stock atomically and write audit movement record
            inventoryService.receiveGoods(
                    po.getDestinationWarehouse().getId(),
                    item.getProduct().getId(),
                    receipt.getQuantityReceived(),
                    po.getPoNumber(),
                    userId
            );
        }

        // Determine new overall PO status
        boolean allComplete = po.getItems().stream().allMatch(i -> Objects.equals(i.getReceivedQty(), i.getOrderedQty()));
        boolean anyReceived = po.getItems().stream().anyMatch(i -> i.getReceivedQty() > 0);

        if (allComplete) {
            po.setStatus(PurchaseOrder.POStatus.RECEIVED);
        } else if (anyReceived) {
            po.setStatus(PurchaseOrder.POStatus.PARTIALLY_RECEIVED);
        }

        return mapToResponse(purchaseOrderRepository.save(po));
    }

    public PurchaseOrderResponse mapToResponse(PurchaseOrder po) {
        return PurchaseOrderResponse.builder()
                .id(po.getId())
                .poNumber(po.getPoNumber())
                .supplierId(po.getSupplier().getId())
                .supplierName(po.getSupplier().getName())
                .destinationWarehouseId(po.getDestinationWarehouse().getId())
                .destinationWarehouseName(po.getDestinationWarehouse().getName())
                .status(po.getStatus().name())
                .totalAmount(po.getTotalAmount())
                .items(po.getItems().stream().map(i -> PurchaseOrderItemResponse.builder()
                        .id(i.getId())
                        .productId(i.getProduct().getId())
                        .productSku(i.getProduct().getSku())
                        .productName(i.getProduct().getName())
                        .orderedQty(i.getOrderedQty())
                        .receivedQty(i.getReceivedQty())
                        .remainingQty(i.getRemainingQty())
                        .unitCost(i.getUnitCost())
                        .lineTotal(i.getUnitCost().multiply(BigDecimal.valueOf(i.getOrderedQty())))
                        .build()).toList())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}