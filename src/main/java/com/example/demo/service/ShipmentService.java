package com.example.demo.service;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.CreateShipmentRequest;
import com.example.demo.dto.ShipmentResponse;
import com.example.demo.dto.UpdateShipmentStatusRequest;
import com.example.demo.entity.OrderStatusHistory;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.entity.Shipment;
import com.example.demo.entity.User;
import com.example.demo.repository.OrderStatusHistoryRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.repository.ShipmentRepository;
import com.example.demo.repository.UserRepository;

import exception.InvalidOperationException;
import exception.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final UserRepository userRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final InventoryService inventoryService;

    @Transactional
    public ShipmentResponse createAndProcessShipment(CreateShipmentRequest request, Long userId) {
        SalesOrder order = salesOrderRepository.findById(request.getSalesOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Sales Order not found: " + request.getSalesOrderId()));

        // Rule 7 & 13: Only CONFIRMED orders can be fulfilled and shipped
        if (order.getStatus() != SalesOrder.OrderStatus.CONFIRMED) {
            throw new InvalidOperationException("Order cannot be shipped. Current status is " + order.getStatus() 
                    + ", but must be CONFIRMED.");
        }

        // Rule 7: Prevent duplicate shipment processing
        if (shipmentRepository.existsBySalesOrderId(order.getId())) {
            throw new InvalidOperationException("A shipment record already exists for order " + order.getOrderNumber());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        String shipmentNumber = "SHP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // 1. Prepare product quantities map
        Map<Long, Integer> productQuantities = order.getItems().stream()
                .collect(Collectors.toMap(
                        item -> item.getProduct().getId(),
                        SalesOrderItem::getQuantity
                ));

        // 2. Critical Rule 8: Deduct on_hand_qty and release reserved_qty atomically via InventoryService
        inventoryService.fulfillShipment(
                order.getWarehouse().getId(),
                productQuantities,
                shipmentNumber,
                userId
        );

        // 3. Create Shipment entity
        Shipment shipment = Shipment.builder()
                .shipmentNumber(shipmentNumber)
                .salesOrder(order)
                .carrier(request.getCarrier().trim())
                .trackingNumber(request.getTrackingNumber().trim())
                .status(Shipment.ShipmentStatus.PACKED)
                .shippedAt(LocalDateTime.now())
                .createdBy(user)
                .build();

        Shipment savedShipment = shipmentRepository.save(shipment);

        // 4. Update Sales Order status to SHIPPED
        order.setStatus(SalesOrder.OrderStatus.SHIPPED);
        salesOrderRepository.save(order);

        // 5. Audit order status transition
        OrderStatusHistory history = OrderStatusHistory.builder()
                .salesOrder(order)
                .fromStatus(SalesOrder.OrderStatus.CONFIRMED.name())
                .toStatus(SalesOrder.OrderStatus.SHIPPED.name())
                .changedBy(user)
                .changeReason("Fulfillment dispatched via " + request.getCarrier() + " with tracking #" + request.getTrackingNumber())
                .build();
        statusHistoryRepository.save(history);

        return mapToResponse(savedShipment);
    }

    @Transactional
    public ShipmentResponse updateShipmentStatus(Long shipmentId, UpdateShipmentStatusRequest request, Long userId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found with id: " + shipmentId));

        Shipment.ShipmentStatus newStatus;
        try {
            newStatus = Shipment.ShipmentStatus.valueOf(request.getStatus().toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("Invalid shipment status: " + request.getStatus());
        }

        User user = userRepository.findById(userId).orElseThrow();
        SalesOrder order = shipment.getSalesOrder();

        shipment.setStatus(newStatus);
        if (newStatus == Shipment.ShipmentStatus.DELIVERED) {
            shipment.setDeliveredAt(LocalDateTime.now());
            order.setStatus(SalesOrder.OrderStatus.DELIVERED);
            salesOrderRepository.save(order);

            statusHistoryRepository.save(OrderStatusHistory.builder()
                    .salesOrder(order)
                    .fromStatus(SalesOrder.OrderStatus.SHIPPED.name())
                    .toStatus(SalesOrder.OrderStatus.DELIVERED.name())
                    .changedBy(user)
                    .changeReason("Carrier marked shipment as delivered")
                    .build());
        }

        return mapToResponse(shipmentRepository.save(shipment));
    }

    @Transactional(readOnly = true)
    public ShipmentResponse getShipmentByOrderId(Long salesOrderId) {
        Shipment shipment = shipmentRepository.findBySalesOrderId(salesOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("No shipment record found for sales order ID: " + salesOrderId));
        return mapToResponse(shipment);
    }

    public ShipmentResponse mapToResponse(Shipment s) {
        return ShipmentResponse.builder()
                .id(s.getId())
                .shipmentNumber(s.getShipmentNumber())
                .salesOrderId(s.getSalesOrder().getId())
                .salesOrderNumber(s.getSalesOrder().getOrderNumber())
                .customerName(s.getSalesOrder().getCustomerName())
                .carrier(s.getCarrier())
                .trackingNumber(s.getTrackingNumber())
                .status(s.getStatus().name())
                .shippedAt(s.getShippedAt())
                .deliveredAt(s.getDeliveredAt())
                .createdByUsername(s.getCreatedBy() != null ? s.getCreatedBy().getUsername() : "SYSTEM")
                .build();
    }
}