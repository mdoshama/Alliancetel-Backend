package com.example.schedulingsoftware.employee;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.auth.PasswordPolicy;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.PhoneNumbers;
import com.example.schedulingsoftware.common.InputRules;
import com.example.schedulingsoftware.common.PageResponse;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.employee.EmployeeDtos.CredentialsResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeResponse;
import com.example.schedulingsoftware.store.StoreService;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final AreaService areaService;
    private final AccessPolicy accessPolicy;
    private final PasswordEncoder passwordEncoder;
    private final StoreService storeService;

    @Transactional(readOnly = true)
    public List<EmployeeResponse> search(Long requestedAreaId, Role role, Boolean active, String query) {
        Long areaId = accessPolicy.restrictToArea(requestedAreaId);
        return employeeRepository.findAll(filter(areaId, role, active, null, null, query), sortBy(null)).stream()
                .map(EmployeeResponse::from)
                .toList();
    }

    /**
     * One page of employees, for the staff list. Same filters as {@link #search} plus home store and
     * primary / secondary / floater; managers only ever see their own area.
     *
     * @param sort "name" (default), "code" or "role"
     */
    @Transactional(readOnly = true)
    public PageResponse<EmployeeResponse> page(Long requestedAreaId, Role role, Boolean active, Long storeId,
            StoreRole storeRole, String query, Integer page, Integer size, String sort) {
        Long areaId = accessPolicy.restrictToArea(requestedAreaId);
        Specification<Employee> spec = filter(areaId, role, active, storeId, storeRole, query);
        return PageResponse.from(employeeRepository.findAll(spec, PageResponse.request(page, size, sortBy(sort))),
                EmployeeResponse::from);
    }

    private static Sort sortBy(String sort) {
        Sort byName = Sort.by("lastName", "firstName", "id");
        if ("code".equalsIgnoreCase(sort)) {
            return Sort.by("employeeCode", "id");
        }
        if ("role".equalsIgnoreCase(sort)) {
            return Sort.by("role").and(byName);
        }
        return byName;
    }

    private static Specification<Employee> filter(Long areaId, Role role, Boolean active, Long storeId,
            StoreRole storeRole, String query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (areaId != null) {
                predicates.add(cb.equal(root.get("area").get("id"), areaId));
            }
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (storeId != null) {
                predicates.add(cb.equal(root.get("homeStore").get("id"), storeId));
            }
            if (storeRole != null) {
                switch (storeRole) {
                    case FLOATER -> predicates.add(cb.isNull(root.get("homeStore")));
                    case PRIMARY -> predicates.add(cb.and(cb.isNotNull(root.get("homeStore")),
                            cb.isTrue(root.get("primaryAtStore"))));
                    case SECONDARY -> predicates.add(cb.and(cb.isNotNull(root.get("homeStore")),
                            cb.isFalse(root.get("primaryAtStore"))));
                }
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + escapeLike(shorten(query).toLowerCase(Locale.ROOT)) + "%";
                // "ana lopez" also matches first + last name together.
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), like, '\\'),
                        cb.like(cb.lower(root.get("lastName")), like, '\\'),
                        cb.like(cb.lower(cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName"))), like, '\\'),
                        cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(cb.lower(root.get("employeeCode")), like, '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** A search term trimmed and cut to 100 characters. */
    public static String shorten(String query) {
        String q = query.trim();
        return q.length() > 100 ? q.substring(0, 100) : q;
    }

    /** Makes % and _ in a search match themselves. */
    public static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional(readOnly = true)
    public EmployeeResponse findById(Long id) {
        Employee e = getEmployee(id);
        accessPolicy.requireArea(e.getArea() == null ? null : e.getArea().getId());
        return EmployeeResponse.from(e);
    }

    @Transactional
    public CredentialsResponse create(EmployeeRequest request) {
        String code = request.employeeCode().trim().toUpperCase(Locale.ROOT);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (employeeRepository.existsByEmployeeCodeIgnoreCase(code)) {
            throw new BusinessRuleException("Employee code '" + code + "' is already in use.");
        }
        if (employeeRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessRuleException("Email '" + email + "' is already in use.");
        }
        Employee employee = new Employee();
        apply(employee, request, code, email);
        String temporary = PasswordPolicy.generateTemporary();
        employee.setPassword(passwordEncoder.encode(temporary), true);
        return new CredentialsResponse(EmployeeResponse.from(employeeRepository.save(employee)), temporary);
    }

    @Transactional
    public EmployeeResponse update(Long id, EmployeeRequest request) {
        Employee employee = getEmployee(id);
        String code = request.employeeCode().trim().toUpperCase(Locale.ROOT);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (employeeRepository.existsByEmployeeCodeIgnoreCaseAndIdNot(code, id)) {
            throw new BusinessRuleException("Employee code '" + code + "' is already in use.");
        }
        if (employeeRepository.existsByEmailIgnoreCaseAndIdNot(email, id)) {
            throw new BusinessRuleException("Email '" + email + "' is already in use.");
        }
        apply(employee, request, code, email);
        return EmployeeResponse.from(employee);
    }

    /** Employees are deactivated rather than deleted so their schedule history stays intact. */
    @Transactional
    public void deactivate(Long id) {
        if (id.equals(accessPolicy.current().id())) {
            throw new BusinessRuleException("You cannot deactivate your own account.");
        }
        Employee employee = getEmployee(id);
        employee.setActive(false);
        employee.endAllSessions();
        log.info("Deactivated {} (by {})", employee.getEmail(), accessPolicy.current().email());
    }

    /**
     * Moves someone to another store in their area (or clears it). Managers can do this for their own area,
     * which is how day-to-day rotations between stores are handled.
     */
    @Transactional
    public EmployeeResponse changeHomeStore(Long id, Long storeId) {
        return changeHomeStore(id, storeId, null);
    }

    /** Same, also setting primary (true) or secondary (false); null keeps the current choice. */
    @Transactional
    public EmployeeResponse changeHomeStore(Long id, Long storeId, Boolean primary) {
        Employee employee = getEmployee(id);
        Long areaId = employee.getArea() == null ? null : employee.getArea().getId();
        accessPolicy.requireManagerOf(areaId);
        employee.setHomeStore(storeId == null ? null : storeService.requireAssignable(storeId, areaId));
        if (primary != null) {
            employee.setPrimaryAtStore(primary);
        }
        if (employee.getHomeStore() == null) {
            employee.setPrimaryAtStore(false); // Floaters are never primary.
        }
        return EmployeeResponse.from(employee);
    }

    /** Issues a new temporary password; the employee must change it at next sign-in. */
    @Transactional
    public CredentialsResponse resetPassword(Long id) {
        Employee employee = getEmployee(id);
        if (!employee.isActive()) {
            throw new BusinessRuleException(employee.getFullName() + " is inactive. Reactivate them first.");
        }
        String temporary = PasswordPolicy.generateTemporary();
        // Also signs them out everywhere, in case the reset is because someone else knew the old password.
        employee.setPassword(passwordEncoder.encode(temporary), true);
        log.info("Password reset for {} (by {})", employee.getEmail(), accessPolicy.current().email());
        return new CredentialsResponse(EmployeeResponse.from(employee), temporary);
    }

    public Employee getEmployee(Long id) {
        return employeeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee", id));
    }

    private void apply(Employee employee, EmployeeRequest request, String code, String email) {
        if (request.role() != Role.ADMIN && request.areaId() == null) {
            throw new BusinessRuleException("Managers and employees must be assigned to an area.");
        }
        if (employee.getId() != null && employee.getId().equals(accessPolicy.current().id())
                && (request.role() != Role.ADMIN || Boolean.FALSE.equals(request.active()))) {
            throw new BusinessRuleException("You cannot remove your own admin access.");
        }
        // A change of role, email or access ends their current sessions, so it takes effect everywhere at once.
        boolean endSessions = employee.getId() != null && (employee.getRole() != request.role()
                || !email.equalsIgnoreCase(employee.getEmail())
                || (Boolean.FALSE.equals(request.active()) && employee.isActive()));
        employee.setEmployeeCode(code);
        employee.setFirstName(InputRules.clean(request.firstName()));
        employee.setLastName(InputRules.clean(request.lastName()));
        employee.setEmail(email);
        employee.setPhone(PhoneNumbers.normalize(request.phone()));
        employee.setRole(request.role());
        employee.setArea(request.areaId() == null ? null : areaService.getArea(request.areaId()));
        employee.setHomeStore(request.homeStoreId() == null ? null
                : storeService.requireAssignable(request.homeStoreId(), request.areaId()));
        if (request.primaryAtStore() != null) {
            employee.setPrimaryAtStore(request.primaryAtStore());
        }
        if (employee.getHomeStore() == null) {
            employee.setPrimaryAtStore(false);
        }
        employee.setWorkDaysPerWeek(request.workDaysPerWeek());
        if (request.whatsappOptIn() != null) {
            if (request.whatsappOptIn() && employee.getPhone() == null) {
                throw new BusinessRuleException("Add a phone number to send WhatsApp schedule updates.");
            }
            employee.setWhatsappOptIn(request.whatsappOptIn());
        }
        if (employee.getPhone() == null) {
            employee.setWhatsappOptIn(false);
        }
        if (request.emailOptIn() != null) {
            employee.setEmailOptIn(request.emailOptIn());
        }
        if (request.active() != null) {
            employee.setActive(request.active());
        }
        if (endSessions) {
            employee.endAllSessions();
        }
    }
}
