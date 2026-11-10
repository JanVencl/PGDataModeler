package com.datagocar.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OperationLogRepository extends JpaRepository<OperationLogEntity, Long> {

    List<OperationLogEntity> findByModelIdOrderByVersionDesc(UUID modelId, Pageable pageable);
}
