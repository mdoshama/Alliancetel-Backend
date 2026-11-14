package com.example.schedulingsoftware.employee;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    boolean existsByEmployeeCodeIgnoreCase(String employeeCode);

    boolean existsByEmployeeCodeIgnoreCaseAndIdNot(String employeeCode, Long id);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    boolean existsByAreaId(Long areaId);

    Optional<Employee> findByEmailIgnoreCase(String email);

    long countByRoleAndActiveTrue(Role role);

    /** By phone number in +&lt;country&gt;&lt;number&gt; form (as saved). */
    java.util.List<Employee> findByPhone(String phone);

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    Optional<Employee> findFirstByRoleAndActiveTrueOrderByIdAsc(Role role);

    boolean existsByRoleAndActiveTrueAndPasswordHashIsNotNull(Role role);

    long countByAreaIdAndActiveTrue(Long areaId);

    List<Employee> findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(Long areaId,
            Collection<Role> roles);

    boolean existsByHomeStoreId(Long storeId);

    /** The active primary at a store other than the given person, if any (each store has at most one). */
    Optional<Employee> findFirstByHomeStoreIdAndPrimaryAtStoreTrueAndActiveTrueAndIdNot(Long storeId, Long id);

    /** Active people with a birth date on file, for birthday lists and reminders. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = { "area", "homeStore" })
    List<Employee> findByActiveTrueAndBirthDateIsNotNull();

    /** Active admins and managers: who gets birthday reminders. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = { "area" })
    List<Employee> findByActiveTrueAndRoleIn(Collection<Role> roles);

    @org.springframework.data.jpa.repository.Query(
            "select a.id from Employee e join e.managedAreas a where e.id = :id")
    List<Long> findAssignedAreaIds(@org.springframework.data.repository.query.Param("id") Long id);

    /** Market Managers of an area (assigned to it, or with it as their home area). */
    @org.springframework.data.jpa.repository.Query("select distinct e from Employee e left join e.managedAreas a "
            + "where e.active = true and e.role = com.example.schedulingsoftware.employee.Role.MARKET_MANAGER "
            + "and (a.id = :areaId or e.area.id = :areaId)")
    List<Employee> findMarketManagersOf(@org.springframework.data.repository.query.Param("areaId") Long areaId);

    /** The areas someone manages, for the signed-in user: their home area, plus the assigned ones for an MM. */
    default java.util.Set<Long> managedAreaIdsOf(Long id, Role role) {
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        findById(id).map(Employee::getArea).ifPresent(a -> ids.add(a.getId()));
        if (role == Role.MARKET_MANAGER) {
            ids.addAll(findAssignedAreaIds(id));
        }
        return ids;
    }

    long countByHomeStoreIdAndActiveTrue(Long storeId);

    @Query("select e.homeStore.id, count(e) from Employee e where e.active = true and e.homeStore is not null group by e.homeStore.id")
    List<Object[]> countActiveGroupedByHomeStore();

    default Map<Long, Long> countActiveByHomeStore() {
        return countActiveGroupedByHomeStore().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    @Query("select e.area.id, count(e) from Employee e where e.active = true and e.area is not null group by e.area.id")
    List<Object[]> countActiveGroupedByArea();

    default Map<Long, Long> countActiveByArea() {
        return countActiveGroupedByArea().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }
}
