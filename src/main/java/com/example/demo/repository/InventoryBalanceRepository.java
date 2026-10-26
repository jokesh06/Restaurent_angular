package com.example.demo.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.demo.entity.InventoryBalance;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, Long> {

    Optional<InventoryBalance> findByWarehouseIdAndProductId(Long warehouseId, Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ib FROM InventoryBalance ib WHERE ib.warehouse.id = :warehouseId AND ib.product.id = :productId")
    Optional<InventoryBalance> findByWarehouseIdAndProductIdWithPessimisticLock(
        @Param("warehouseId") Long warehouseId, 
        @Param("productId") Long productId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ib FROM InventoryBalance ib WHERE ib.warehouse.id = :warehouseId AND ib.product.id IN :productIds ORDER BY ib.product.id ASC")
    List<InventoryBalance> findByWarehouseIdAndProductIdsOrderedWithPessimisticLock(
        @Param("warehouseId") Long warehouseId,
        @Param("productIds") List<Long> productIds
    );

    @Query("SELECT ib FROM InventoryBalance ib JOIN FETCH ib.product p JOIN FETCH ib.warehouse w WHERE ib.warehouse.id = :warehouseId")
    List<InventoryBalance> findByWarehouseIdWithProductAndWarehouse(@Param("warehouseId") Long warehouseId);
}