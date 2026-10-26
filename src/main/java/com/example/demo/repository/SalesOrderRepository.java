package com.example.demo.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.demo.entity.SalesOrder;

import java.util.Optional;

@Repository
public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
    Optional<SalesOrder> findByOrderNumber(String orderNumber);

    Page<SalesOrder> findByWarehouseId(Long warehouseId, Pageable pageable);

    Page<SalesOrder> findByStatus(SalesOrder.OrderStatus status, Pageable pageable);

    @Query("SELECT COUNT(s) FROM SalesOrder s WHERE s.status = :status")
    long countByStatus(@Param("status") SalesOrder.OrderStatus status);

    @Query("SELECT COUNT(s) FROM SalesOrder s WHERE s.status IN ('DRAFT', 'CONFIRMED')")
    long countPendingOrders();
}