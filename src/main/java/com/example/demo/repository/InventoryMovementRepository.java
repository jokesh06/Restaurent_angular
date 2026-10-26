package com.example.demo.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.demo.entity.InventoryMovement;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    @Query("SELECT im FROM InventoryMovement im " +
           "WHERE (:warehouseId IS NULL OR im.warehouse.id = :warehouseId) " +
           "AND (:productId IS NULL OR im.product.id = :productId) " +
           "AND (:startDate IS NULL OR im.timestamp >= :startDate) " +
           "AND (:endDate IS NULL OR im.timestamp <= :endDate) " +
           "ORDER BY im.timestamp DESC")
    Page<InventoryMovement> filterMovements(
            @Param("warehouseId") Long warehouseId,
            @Param("productId") Long productId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    List<InventoryMovement> findTop10ByOrderByTimestampDesc();
}