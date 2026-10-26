package com.example.demo.controller;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.example.demo.dto.CreateShipmentRequest;
import com.example.demo.dto.ShipmentResponse;
import com.example.demo.dto.UpdateShipmentStatusRequest;
import com.example.demo.entity.User;
import com.example.demo.repository.ShipmentRepository;
import com.example.demo.service.ShipmentService;

import exception.ResourceNotFoundException;
import com.example.demo.entity.Shipment;


@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
public class ShipmentController {

    private final ShipmentService shipmentService;
    private final ShipmentRepository shipmentRepository;

    @GetMapping
    public ResponseEntity<Page<ShipmentResponse>> getAllShipments(
            @PageableDefault(size = 15, sort = "shippedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<Shipment> shipments = shipmentRepository.findAll(pageable);
        return ResponseEntity.ok(shipments.map(shipmentService::mapToResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShipmentResponse> getShipmentById(@PathVariable Long id) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found with id: " + id));
        return ResponseEntity.ok(shipmentService.mapToResponse(shipment));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ShipmentResponse> getShipmentByOrderId(@PathVariable Long orderId) {
        return ResponseEntity.ok(shipmentService.getShipmentByOrderId(orderId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER', 'WAREHOUSE_STAFF')")
    public ResponseEntity<ShipmentResponse> processShipment(
            @Valid @RequestBody CreateShipmentRequest request,
            @AuthenticationPrincipal User user) {

        ShipmentResponse response = shipmentService.createAndProcessShipment(request, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER', 'WAREHOUSE_STAFF')")
    public ResponseEntity<ShipmentResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateShipmentStatusRequest request,
            @AuthenticationPrincipal User user) {

        ShipmentResponse updated = shipmentService.updateShipmentStatus(id, request, user.getId());
        return ResponseEntity.ok(updated);
    }
}