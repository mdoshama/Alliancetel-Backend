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
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.employee.EmployeeDtos.CredentialsResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeResponse;
import com.example.schedulingsoftware.store.StoreService;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

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
        Specification<Employee> spec = (root, cq, cb) -> {
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
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), like),
                        cb.like(cb.lower(root.get("lastName")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("employeeCode")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return employeeRepository.findAll(spec, Sort.by("lastName", "firstName")).stream()
                .map(EmployeeResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse findById(Long id) {
        Employee e = getEmployee(id);
        accessPolicy.requireArea(e.getArea() == null ? null : e.getArea().getId());
        return EmployeeResponse.from(e);
    }

    @Transactional
    public CredentialsResponse create(EmployeeRequest request) {
        String code = request.employeeCode().trim();
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
        employee.setPasswordHash(passwordEncoder.encode(temporary));
        employee.setMustChangePassword(true);
        return new CredentialsResponse(EmployeeResponse.from(employeeRepository.save(employee)), temporary);
    }

    @Transactional
    public EmployeeResponse update(Long id, EmployeeRequest request) {
        Employee employee = getEmployee(id);
        String code = request.employeeCode().trim();
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
        getEmployee(id).setActive(false);
    }

    /**
     * Moves someone to another store in their area (or clears it). Managers can do this for their own area,
     * which is how day-to-day rotations between stores are handled.
     */
    @Transactional
    public EmployeeResponse changeHomeStore(Long id, Long storeId) {
        Employee employee = getEmployee(id);
        Long areaId = employee.getArea() == null ? null : employee.getArea().getId();
        accessPolicy.requireManagerOf(areaId);
        employee.setHomeStore(storeId == null ? null : storeService.requireAssignable(storeId, areaId));
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
        employee.setPasswordHash(passwordEncoder.encode(temporary));
        employee.setMustChangePassword(true);
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
        employee.setEmployeeCode(code);
        employee.setFirstName(request.firstName().trim());
        employee.setLastName(request.lastName().trim());
        employee.setEmail(email);
        employee.setPhone(request.phone());
        employee.setRole(request.role());
        employee.setArea(request.areaId() == null ? null : areaService.getArea(request.areaId()));
        employee.setHomeStore(request.homeStoreId() == null ? null
                : storeService.requireAssignable(request.homeStoreId(), request.areaId()));
        employee.setWorkDaysPerWeek(request.workDaysPerWeek());
        if (request.active() != null) {
            employee.setActive(request.active());
        }
    }
}
