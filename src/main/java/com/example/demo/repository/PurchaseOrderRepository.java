package com.example.demo.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.example.demo.entity.PurchaseOrder;

import java.util.Optional;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
    Optional<PurchaseOrder> findByPoNumber(String poNumber);

    @Query("SELECT COUNT(po) FROM PurchaseOrder po WHERE po.status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_RECEIVED')")
    long countPendingPurchaseOrders();

    Page<PurchaseOrder> findByStatus(PurchaseOrder.POStatus status, Pageable pageable);
}