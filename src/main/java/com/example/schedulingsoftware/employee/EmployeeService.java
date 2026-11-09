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
    private final com.example.schedulingsoftware.dayoff.DayOffRequestRepository dayOffRepository;
    private final com.example.schedulingsoftware.auth.PasswordSetupService passwordSetup;
    private final com.example.schedulingsoftware.notify.ScheduleNotifier notifier;

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
        return create(request, false);
    }

    /**
     * Creates the account with a temporary password and emails them a link to choose their own (also by text when
     * {@code textLink}: the admin confirms they agreed to get it by text).
     */
    @Transactional
    public CredentialsResponse create(EmployeeRequest request, boolean textLink) {
        String code = request.employeeCode().trim().toUpperCase(Locale.ROOT);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (employeeRepository.existsByEmployeeCodeIgnoreCase(code)) {
            throw new BusinessRuleException("NTID '" + code + "' is already in use.");
        }
        if (employeeRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessRuleException("Email '" + email + "' is already in use.");
        }
        Employee employee = new Employee();
        apply(employee, request, code, email);
        String temporary = PasswordPolicy.generateTemporary();
        employee.setPassword(passwordEncoder.encode(temporary), true);
        Employee saved = employeeRepository.save(employee);
        if (saved.isSmsOptIn()) {
            notifier.smsOptInConfirmation(saved.getId());
        }
        var link = passwordSetup.send(saved, true, textLink);
        return new CredentialsResponse(EmployeeResponse.from(saved), temporary, link.emailed(), link.texted(),
                link.note());
    }

    @Transactional
    public EmployeeResponse update(Long id, EmployeeRequest request) {
        Employee employee = getEmployee(id);
        String code = request.employeeCode().trim().toUpperCase(Locale.ROOT);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (employeeRepository.existsByEmployeeCodeIgnoreCaseAndIdNot(code, id)) {
            throw new BusinessRuleException("NTID '" + code + "' is already in use.");
        }
        if (employeeRepository.existsByEmailIgnoreCaseAndIdNot(email, id)) {
            throw new BusinessRuleException("Email '" + email + "' is already in use.");
        }
        boolean textsBefore = employee.isSmsOptIn();
        apply(employee, request, code, email);
        if (!textsBefore && employee.isSmsOptIn()) {
            employeeRepository.flush();
            notifier.smsOptInConfirmation(employee.getId());
        }
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
        Long before = employee.getHomeStore() == null ? null : employee.getHomeStore().getId();
        boolean wasPrimary = employee.isPrimaryAtStore();
        employee.setHomeStore(storeId == null ? null : storeService.requireAssignable(storeId, areaId));
        if (primary != null) {
            employee.setPrimaryAtStore(primary);
        } else if (employee.isPrimaryAtStore() && storeId != null && !storeId.equals(before)
                && otherPrimary(employee) != null) {
            // Moved to a store that already has its primary: they join it as a secondary.
            employee.setPrimaryAtStore(false);
        }
        if (employee.getHomeStore() == null) {
            employee.setPrimaryAtStore(false); // Floaters are never primary.
        }
        if (!(wasPrimary && java.util.Objects.equals(before, storeId))) {
            requireOnePrimary(employee);
        }
        return EmployeeResponse.from(employee);
    }

    /** Issues a new temporary password; the employee must change it at next sign-in. */
    @Transactional
    public CredentialsResponse resetPassword(Long id) {
        return resetPassword(id, false);
    }

    /** Same, also emailing (and texting, if they agreed to texts or {@code textLink}) a link to choose a new one. */
    @Transactional
    public CredentialsResponse resetPassword(Long id, boolean textLink) {
        Employee employee = getEmployee(id);
        if (!employee.isActive()) {
            throw new BusinessRuleException(employee.getFullName() + " is inactive. Reactivate them first.");
        }
        String temporary = PasswordPolicy.generateTemporary();
        // Also signs them out everywhere, in case the reset is because someone else knew the old password.
        employee.setPassword(passwordEncoder.encode(temporary), true);
        log.info("Password reset for {} (by {})", employee.getEmail(), accessPolicy.current().email());
        var link = passwordSetup.send(employee, false, textLink);
        return new CredentialsResponse(EmployeeResponse.from(employee), temporary, link.emailed(), link.texted(),
                link.note());
    }

    public Employee getEmployee(Long id) {
        return employeeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee", id));
    }

    /** Youngest and oldest ages accepted for a birth date, to catch typos like a wrong year. */
    static final int MIN_AGE = 14;
    static final int MAX_AGE = 100;

    /** Checks a birth date (null = none on file); also used when people edit their own profile. */
    public static java.time.LocalDate checkBirthDate(java.time.LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        if (birthDate.isAfter(today.minusYears(MIN_AGE))) {
            throw new BusinessRuleException("Birth date must be at least " + MIN_AGE + " years ago. Check the year.");
        }
        if (birthDate.isBefore(today.minusYears(MAX_AGE))) {
            throw new BusinessRuleException("Birth date is more than " + MAX_AGE + " years ago. Check the year.");
        }
        return birthDate;
    }

    private void apply(Employee employee, EmployeeRequest request, String code, String email) {
        // Someone who already is the active primary here stays so (older data may have two; editing other details
        // shouldn't fail because of that).
        boolean alreadyPrimaryHere = employee.getId() != null && employee.isActive() && employee.isPrimaryAtStore()
                && employee.getHomeStore() != null && employee.getHomeStore().getId().equals(request.homeStoreId());
        if ((request.role() == Role.MANAGER || request.role() == Role.EMPLOYEE) && request.areaId() == null) {
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
        String phone = PhoneNumbers.normalize(request.phone());
        if (!java.util.Objects.equals(phone, employee.getPhone())) {
            // Agreement to texts is for a number: a new number needs the person to agree again.
            employee.setSmsOptIn(false);
        }
        employee.setPhone(phone);
        if (request.smsOptIn() != null && request.smsOptIn() != employee.isSmsOptIn()) {
            var by = accessPolicy.current();
            if (request.smsOptIn()) {
                // Texting needs the person's agreement: an admin records that they gave it.
                if (!by.isAdmin()) {
                    throw new BusinessRuleException("Only an admin can turn on text messages for someone. They can also "
                            + "turn them on themselves in their account menu.");
                }
                if (phone == null) {
                    throw new BusinessRuleException("Add a mobile number before turning on text messages.");
                }
                employee.setSmsOptIn(true);
                employee.setSmsConsentAt(java.time.Instant.now());
                employee.setSmsConsentBy(by.fullName());
                log.info("Text-message consent for {} recorded by {}", email, by.email());
            } else {
                employee.setSmsOptIn(false);
            }
        }
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
        // Only managers can be off the schedule (managing without working shifts); everyone else works.
        boolean wasOnSchedule = employee.isOnSchedule();
        if (request.role() != Role.MANAGER) {
            employee.setOnSchedule(true);
        } else if (request.onSchedule() != null) {
            employee.setOnSchedule(request.onSchedule());
        }
        if (wasOnSchedule && !employee.isOnSchedule() && employee.getId() != null) {
            // Their days the system reserved from today on are no longer needed.
            dayOffRepository.deleteAll(dayOffRepository.findByEmployeeIdAndReservedTrueAndOffDateBetweenOrderByOffDateDesc(
                    employee.getId(), java.time.LocalDate.now(), java.time.LocalDate.now().plusYears(1)).stream()
                    .filter(r -> r.getStatus() == com.example.schedulingsoftware.dayoff.DayOffStatus.PENDING).toList());
        }
        if (request.floatStoreIds() != null) {
            java.util.Set<com.example.schedulingsoftware.store.Store> stores = new java.util.HashSet<>();
            for (Long storeId : new java.util.LinkedHashSet<>(request.floatStoreIds())) {
                stores.add(storeService.requireAssignable(storeId, request.areaId()));
            }
            employee.getFloatStores().clear();
            employee.getFloatStores().addAll(stores);
        } else if (employee.getArea() != null && !employee.getFloatStores().isEmpty()) {
            // Moved to another area: stores from the old area no longer apply.
            Long area = employee.getArea().getId();
            employee.getFloatStores().removeIf(st -> st.getArea() == null || !area.equals(st.getArea().getId()));
        }
        employee.setBirthDate(checkBirthDate(request.birthDate()));
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
        if (!alreadyPrimaryHere) {
            requireOnePrimary(employee);
        }
        if (endSessions) {
            employee.endAllSessions();
        }
    }

    /** The other active primary at this person's home store, or null. */
    private Employee otherPrimary(Employee employee) {
        if (employee.getHomeStore() == null) {
            return null;
        }
        return employeeRepository.findFirstByHomeStoreIdAndPrimaryAtStoreTrueAndActiveTrueAndIdNot(
                employee.getHomeStore().getId(), employee.getId() == null ? -1L : employee.getId()).orElse(null);
    }

    /** Each store has one primary at most: making a second one is refused (make the current one secondary first). */
    private void requireOnePrimary(Employee employee) {
        if (!employee.isActive() || !employee.isPrimaryAtStore()) {
            return;
        }
        Employee other = otherPrimary(employee);
        if (other != null) {
            throw new BusinessRuleException(other.getFullName() + " is already the primary at "
                    + employee.getHomeStore().getName() + ". A store has only one primary: make "
                    + other.getFirstName() + " a secondary first.");
        }
    }
}
