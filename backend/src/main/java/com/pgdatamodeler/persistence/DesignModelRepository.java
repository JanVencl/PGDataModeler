package com.pgdatamodeler.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DesignModelRepository extends JpaRepository<DesignModelEntity, UUID> {

    /** Row lock so concurrent edits are serialized even if several app instances run. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from DesignModelEntity m where m.id = :id")
    Optional<DesignModelEntity> findForUpdate(@Param("id") UUID id);

    List<DesignModelEntity> findAllByOrderByUpdatedAtDesc();
}
