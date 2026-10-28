package com.example.schedulingsoftware.dayoff;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.security.access.AccessDeniedException;

import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.auth.CurrentUser;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeService;

import jakarta.persistence.criteria.Predicate;

@Service
public class DayOffService {

    private static final EnumSet<DayOffStatus> OPEN_STATUSES = EnumSet.of(DayOffStatus.PENDING, DayOffStatus.APPROVED);

    private final DayOffRequestRepository repository;
    private final EmployeeService employeeService;
    private final AccessPolicy accessPolicy;
    private final int maxPerWeek;

    public DayOffService(DayOffRequestRepository repository,
            EmployeeService employeeService,
            AccessPolicy accessPolicy,
            @Value("${alliancetel.scheduling.max-day-off-requests-per-week:2}") int maxPerWeek) {
        this.repository = repository;
        this.employeeService = employeeService;
        this.accessPolicy = accessPolicy;
        this.maxPerWeek = maxPerWeek;
    }

    @Transactional(readOnly = true)
    public List<DayOffResponse> search(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to) {
        CurrentUser user = accessPolicy.current();
        Long areaId = accessPolicy.restrictToArea(requestedAreaId);
        // Employees only ever see their own requests.
        Long employeeId = user.isEmployee() ? user.id() : requestedEmployeeId;
        Specification<DayOffRequest> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (areaId != null) {
                predicates.add(cb.equal(root.get("employee").get("area").get("id"), areaId));
            }
            if (employeeId != null) {
                predicates.add(cb.equal(root.get("employee").get("id"), employeeId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("offDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("offDate"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return repository.findAll(spec, Sort.by("offDate", "id")).stream()
                .map(DayOffResponse::from)
                .toList();
    }

    @Transactional
    public List<DayOffResponse> create(CreateDayOffRequest request) {
        Employee employee = employeeService.getEmployee(request.employeeId());
        CurrentUser user = accessPolicy.current();
        if (user.isEmployee() && !employee.getId().equals(user.id())) {
            throw new AccessDeniedException("You can only request days off for yourself.");
        }
        if (user.isManager() && !employee.getId().equals(user.id())) {
            accessPolicy.requireManagerOf(areaOf(employee));
        }
        if (!employee.isActive()) {
            throw new BusinessRuleException(employee.getFullName() + " is inactive and cannot request days off.");
        }
        LocalDate today = LocalDate.now();
        TreeSet<LocalDate> dates = new TreeSet<>(request.dates());

        // Group the requested dates by week (Monday) so the weekly limit is checked per week.
        Map<LocalDate, Integer> requestedPerWeek = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            if (date.isBefore(today)) {
                throw new BusinessRuleException("Cannot request a day off in the past (" + date + ").");
            }
            if (repository.existsByEmployeeIdAndOffDate(employee.getId(), date)) {
                throw new BusinessRuleException(employee.getFullName() + " already has a request for " + date + ".");
            }
            requestedPerWeek.merge(weekStart(date), 1, Integer::sum);
        }
        requestedPerWeek.forEach((monday, count) -> {
            long existing = repository.countByEmployeeIdAndOffDateBetweenAndStatusIn(
                    employee.getId(), monday, monday.plusDays(6), OPEN_STATUSES);
            if (existing + count > maxPerWeek) {
                throw new BusinessRuleException("At most " + maxPerWeek + " days off can be requested in one week (week of "
                        + monday + " already has " + existing + ").");
            }
        });

        List<DayOffResponse> created = new ArrayList<>();
        for (LocalDate date : dates) {
            DayOffRequest r = new DayOffRequest();
            r.setEmployee(employee);
            r.setOffDate(date);
            r.setReason(request.reason());
            created.add(DayOffResponse.from(repository.save(r)));
        }
        return created;
    }

    @Transactional
    public DayOffResponse approve(Long id, String note) {
        return review(id, DayOffStatus.APPROVED, note);
    }

    @Transactional
    public DayOffResponse reject(Long id, String note) {
        return review(id, DayOffStatus.REJECTED, note);
    }

    /** Employees can withdraw a request while it is still pending. */
    @Transactional
    public void cancel(Long id) {
        DayOffRequest r = get(id);
        CurrentUser user = accessPolicy.current();
        boolean own = r.getEmployee().getId().equals(user.id());
        if (!own) {
            accessPolicy.requireManagerOf(areaOf(r.getEmployee()));
        }
        if (r.getStatus() != DayOffStatus.PENDING) {
            throw new BusinessRuleException("Only pending requests can be cancelled.");
        }
        repository.delete(r);
    }

    private DayOffResponse review(Long id, DayOffStatus newStatus, String note) {
        DayOffRequest r = get(id);
        accessPolicy.requireManagerOf(areaOf(r.getEmployee()));
        if (r.getEmployee().getId().equals(accessPolicy.current().id())) {
            throw new AccessDeniedException("You cannot review your own request. An admin will decide on it.");
        }
        if (r.getStatus() != DayOffStatus.PENDING) {
            throw new BusinessRuleException("This request was already " + r.getStatus().name().toLowerCase() + ".");
        }
        r.setStatus(newStatus);
        r.setReviewNote(note);
        r.setReviewedAt(Instant.now());
        return DayOffResponse.from(r);
    }

    private DayOffRequest get(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Day-off request", id));
    }

    private static Long areaOf(Employee e) {
        return e.getArea() == null ? null : e.getArea().getId();
    }

    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
