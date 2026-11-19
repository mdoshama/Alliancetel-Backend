package com.example.schedulingsoftware.area;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AreaRepository extends JpaRepository<Area, Long> {

    List<Area> findAllByOrderByNameAsc();

    /**
     * Locks the area's row until the transaction ends, so day-off requests in one area are decided one at a time
     * (two people asking for the same day at the same moment can't both pass the coverage check).
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Area a where a.id = :id")
    java.util.Optional<Area> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
