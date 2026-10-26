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

import com.example.demo.dto.CreateStockTransferRequest;
import com.example.demo.dto.StockTransferResponse;
import com.example.demo.entity.StockTransfer;
import com.example.demo.entity.User;
import com.example.demo.repository.StockTransferRepository;
import com.example.demo.service.StockTransferService;

import exception.ResourceNotFoundException;

@RestController
@RequestMapping("/api/stock-transfers")
@RequiredArgsConstructor
public class StockTransferController {

    private final StockTransferService stockTransferService;
    private final StockTransferRepository stockTransferRepository;

    @GetMapping
    public ResponseEntity<Page<StockTransferResponse>> getAllTransfers(
            @PageableDefault(size = 15, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<StockTransfer> page = stockTransferRepository.findAll(pageable);
        return ResponseEntity.ok(page.map(stockTransferService::mapToResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<StockTransferResponse> getTransferById(@PathVariable Long id) {
        StockTransfer transfer = stockTransferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stock transfer not found with id: " + id));
        return ResponseEntity.ok(stockTransferService.mapToResponse(transfer));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public ResponseEntity<StockTransferResponse> createTransfer(
            @Valid @RequestBody CreateStockTransferRequest request,
            @AuthenticationPrincipal User user) {

        StockTransferResponse response = stockTransferService.createTransfer(request, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER', 'WAREHOUSE_STAFF')")
    public ResponseEntity<StockTransferResponse> dispatchTransfer(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(stockTransferService.dispatchTransfer(id, user.getId()));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER', 'WAREHOUSE_STAFF')")
    public ResponseEntity<StockTransferResponse> receiveTransfer(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(stockTransferService.receiveTransfer(id, user.getId()));
    }
}