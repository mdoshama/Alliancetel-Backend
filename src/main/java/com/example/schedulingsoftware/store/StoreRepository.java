package com.example.schedulingsoftware.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findAllByOrderByNameAsc();

    List<Store> findByAreaIdOrderByNameAsc(Long areaId);

    boolean existsByAreaIdAndNameIgnoreCase(Long areaId, String name);

    boolean existsByAreaIdAndNameIgnoreCaseAndIdNot(Long areaId, String name, Long id);

}
