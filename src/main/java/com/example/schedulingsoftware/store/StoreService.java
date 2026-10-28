package com.example.schedulingsoftware.store;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.Area;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.schedule.ScheduleEntryRepository;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreDtos.StoreResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final EmployeeRepository employeeRepository;
    private final ScheduleEntryRepository entryRepository;
    private final AreaService areaService;
    private final AccessPolicy accessPolicy;

    /** Admins see every store (optionally one area); managers and employees see their own area's stores. */
    @Transactional(readOnly = true)
    public List<StoreResponse> list(Long requestedAreaId) {
        Long areaId = accessPolicy.restrictToArea(requestedAreaId);
        Map<Long, Long> counts = employeeRepository.countActiveByHomeStore();
        List<Store> stores = areaId == null
                ? storeRepository.findAllByOrderByNameAsc()
                : storeRepository.findByAreaIdOrderByNameAsc(areaId);
        return stores.stream().map(s -> StoreResponse.from(s, counts.getOrDefault(s.getId(), 0L))).toList();
    }

    @Transactional
    public StoreResponse create(StoreRequest request) {
        Area area = areaService.getArea(request.areaId());
        String name = request.name().trim();
        if (storeRepository.existsByAreaIdAndNameIgnoreCase(area.getId(), name)) {
            throw new BusinessRuleException(area.getName() + " already has a store named '" + name + "'.");
        }
        Store store = new Store(area, name, normalizeCode(request.code()), blankToNull(request.address()));
        if (request.minStaffPerDay() != null) {
            store.setMinStaffPerDay(request.minStaffPerDay());
        }
        if (request.active() != null) {
            store.setActive(request.active());
        }
        return StoreResponse.from(storeRepository.save(store), 0);
    }

    @Transactional
    public StoreResponse update(Long id, StoreRequest request) {
        Store store = getStore(id);
        Area area = areaService.getArea(request.areaId());
        String name = request.name().trim();
        if (storeRepository.existsByAreaIdAndNameIgnoreCaseAndIdNot(area.getId(), name, id)) {
            throw new BusinessRuleException(area.getName() + " already has a store named '" + name + "'.");
        }
        long staff = employeeRepository.countByHomeStoreIdAndActiveTrue(id);
        if (!area.getId().equals(store.getArea().getId()) && staff > 0) {
            throw new BusinessRuleException("Move the " + staff + " employee(s) based at " + store.getName()
                    + " to another store before changing its area.");
        }
        store.setArea(area);
        store.setName(name);
        store.setCode(normalizeCode(request.code()));
        store.setAddress(blankToNull(request.address()));
        if (request.minStaffPerDay() != null) {
            store.setMinStaffPerDay(request.minStaffPerDay());
        }
        if (request.active() != null) {
            store.setActive(request.active());
        }
        return StoreResponse.from(store, staff);
    }

    /** Stores that were ever used keep their history; deactivate them instead. */
    @Transactional
    public void delete(Long id) {
        Store store = getStore(id);
        if (employeeRepository.existsByHomeStoreId(id) || entryRepository.existsByStoreId(id)) {
            throw new BusinessRuleException(store.getName()
                    + " has employees or schedule history. Deactivate it instead of deleting.");
        }
        storeRepository.delete(store);
    }

    public Store getStore(Long id) {
        return storeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Store", id));
    }

    /** A store someone can be assigned to: it must exist, be active and belong to the given area. */
    public Store requireAssignable(Long storeId, Long areaId) {
        Store store = getStore(storeId);
        if (areaId == null || !store.getArea().getId().equals(areaId)) {
            throw new BusinessRuleException(store.getName() + " is not in this employee's area.");
        }
        if (!store.isActive()) {
            throw new BusinessRuleException(store.getName() + " is inactive.");
        }
        return store;
    }

    private static String normalizeCode(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
