package com.example.demo.service;

import com.example.demo.dto.CreateSalesOrderRequest;
import com.example.demo.dto.SalesOrderResponse;
import com.example.demo.entity.OrderStatusHistory;
import com.example.demo.entity.Product;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.entity.User;
import com.example.demo.entity.Warehouse;
import com.example.demo.repository.OrderStatusHistoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.WarehouseRepository;

import exception.InvalidOperationException;
import exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SalesOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final OrderStatusHistoryRepository statusHistoryRepository;

    @Transactional
    public SalesOrderResponse createOrder(CreateSalesOrderRequest request, Long userId) {
        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
            .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found"));
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        SalesOrder order = SalesOrder.builder()
            .orderNumber("SO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
            .customerName(request.getCustomerName())
            .customerEmail(request.getCustomerEmail())
            .warehouse(warehouse)
            .status(SalesOrder.OrderStatus.DRAFT)
            .createdBy(user)
            .build();

        BigDecimal total = BigDecimal.ZERO;
        List<SalesOrderItem> items = new ArrayList<>();

        for (var itemReq : request.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemReq.getProductId()));
            
            BigDecimal subtotal = product.getUnitSellingPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            total = total.add(subtotal);

            items.add(SalesOrderItem.builder()
                .salesOrder(order)
                .product(product)
                .quantity(itemReq.getQuantity())
                .unitPriceSnapshot(product.getUnitSellingPrice())
                .subtotal(subtotal)
                .build());
        }

        order.setTotalAmount(total);
        order.setItems(items);
        SalesOrder savedOrder = salesOrderRepository.save(order);

        recordStatusChange(savedOrder, "NONE", "DRAFT", user, "Order created in draft");
        return mapToResponse(savedOrder);
    }

    @Transactional
    public SalesOrderResponse confirmOrder(Long orderId, Long userId) {
        SalesOrder order = salesOrderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (order.getStatus() != SalesOrder.OrderStatus.DRAFT) {
            throw new InvalidOperationException("Only DRAFT orders can be confirmed");
        }

        Map<Long, Integer> productQuantities = order.getItems().stream()
            .collect(Collectors.toMap(i -> i.getProduct().getId(), SalesOrderItem::getQuantity));

        // Enforce atomic stock reservation via InventoryService (pessimistic lock)
        inventoryService.reserveStockForOrder(order.getWarehouse().getId(), productQuantities, order.getOrderNumber(), userId);

        order.setStatus(SalesOrder.OrderStatus.CONFIRMED);
        SalesOrder updatedOrder = salesOrderRepository.save(order);

        User user = userRepository.findById(userId).orElseThrow();
        recordStatusChange(updatedOrder, "DRAFT", "CONFIRMED", user, "Order confirmed and stock reserved");

        return mapToResponse(updatedOrder);
    }

    @Transactional
    public SalesOrderResponse cancelOrder(Long orderId, String reason, Long userId) {
        SalesOrder order = salesOrderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (order.getStatus() == SalesOrder.OrderStatus.SHIPPED || order.getStatus() == SalesOrder.OrderStatus.DELIVERED) {
            throw new InvalidOperationException("Cannot cancel an order that has already shipped or delivered");
        }

        if (order.getStatus() == SalesOrder.OrderStatus.CONFIRMED) {
            Map<Long, Integer> productQuantities = order.getItems().stream()
                .collect(Collectors.toMap(i -> i.getProduct().getId(), SalesOrderItem::getQuantity));
            inventoryService.releaseReservationForOrder(order.getWarehouse().getId(), productQuantities, order.getOrderNumber(), userId);
        }

        String prevStatus = order.getStatus().name();
        order.setStatus(SalesOrder.OrderStatus.CANCELLED);
        SalesOrder updatedOrder = salesOrderRepository.save(order);

        User user = userRepository.findById(userId).orElseThrow();
        recordStatusChange(updatedOrder, prevStatus, "CANCELLED", user, reason);

        return mapToResponse(updatedOrder);
    }

    private void recordStatusChange(SalesOrder order, String from, String to, User user, String reason) {
        OrderStatusHistory history = OrderStatusHistory.builder()
            .salesOrder(order)
            .fromStatus(from)
            .toStatus(to)
            .changedBy(user)
            .changeReason(reason)
            .build();
        statusHistoryRepository.save(history);
    }

    private SalesOrderResponse mapToResponse(SalesOrder order) {
        return SalesOrderResponse.builder()
            .id(order.getId())
            .orderNumber(order.getOrderNumber())
            .customerName(order.getCustomerName())
            .customerEmail(order.getCustomerEmail())
            .warehouseId(order.getWarehouse().getId())
            .warehouseName(order.getWarehouse().getName())
            .status(order.getStatus().name())
            .totalAmount(order.getTotalAmount())
            .createdAt(order.getCreatedAt())
            .build();
    }
}