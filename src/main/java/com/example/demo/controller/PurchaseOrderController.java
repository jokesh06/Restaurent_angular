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

import com.example.demo.dto.CreatePurchaseOrderRequest;
import com.example.demo.dto.PurchaseOrderResponse;
import com.example.demo.dto.ReceiveGoodsRequest;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.User;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.service.PurchaseOrderService;

import exception.ResourceNotFoundException;

@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseOrderRepository purchaseOrderRepository;

    @GetMapping
    public ResponseEntity<Page<PurchaseOrderResponse>> getAllPurchaseOrders(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 15, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<PurchaseOrder> page;
        if (status != null && !status.isBlank()) {
            page = purchaseOrderRepository.findByStatus(PurchaseOrder.POStatus.valueOf(status.toUpperCase()), pageable);
        } else {
            page = purchaseOrderRepository.findAll(pageable);
        }
        return ResponseEntity.ok(page.map(purchaseOrderService::mapToResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurchaseOrderResponse> getPurchaseOrderById(@PathVariable Long id) {
        PurchaseOrder po = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + id));
        return ResponseEntity.ok(purchaseOrderService.mapToResponse(po));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public ResponseEntity<PurchaseOrderResponse> createPurchaseOrder(
            @Valid @RequestBody CreatePurchaseOrderRequest request,
            @AuthenticationPrincipal User user) {

        PurchaseOrderResponse created = purchaseOrderService.createPurchaseOrder(request, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public ResponseEntity<PurchaseOrderResponse> submitPurchaseOrder(@PathVariable Long id) {
        return ResponseEntity.ok(purchaseOrderService.submitPurchaseOrder(id));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER', 'WAREHOUSE_STAFF')")
    public ResponseEntity<PurchaseOrderResponse> receiveGoods(
            @PathVariable Long id,
            @Valid @RequestBody ReceiveGoodsRequest request,
            @AuthenticationPrincipal User user) {

        PurchaseOrderResponse received = purchaseOrderService.receiveGoods(id, request, user.getId());
        return ResponseEntity.ok(received);
    }
}