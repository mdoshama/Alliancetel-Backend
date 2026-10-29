package com.example.schedulingsoftware.dayoff;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import com.example.schedulingsoftware.common.PageResponse;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse;
import com.example.schedulingsoftware.dayoff.DayOffDtos.TakenDay;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.schedule.ScheduleService;

import jakarta.persistence.criteria.Predicate;

@Service
public class DayOffService {

    private static final EnumSet<DayOffStatus> OPEN_STATUSES = EnumSet.of(DayOffStatus.PENDING, DayOffStatus.APPROVED);

    private final DayOffRequestRepository repository;
    private final EmployeeService employeeService;
    private final AccessPolicy accessPolicy;
    private final ScheduleService scheduleService;
    private final int maxPerWeek;

    public DayOffService(DayOffRequestRepository repository,
            EmployeeService employeeService,
            AccessPolicy accessPolicy,
            ScheduleService scheduleService,
            @Value("${alliancetel.scheduling.max-day-off-requests-per-week:2}") int maxPerWeek) {
        this.repository = repository;
        this.employeeService = employeeService;
        this.accessPolicy = accessPolicy;
        this.scheduleService = scheduleService;
        this.maxPerWeek = maxPerWeek;
    }

    @Transactional(readOnly = true)
    public List<DayOffResponse> search(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to) {
        return repository.findAll(filter(requestedAreaId, requestedEmployeeId, status, from, to, null),
                Sort.by("offDate", "id")).stream()
                .map(DayOffResponse::from)
                .toList();
    }

    /** One page of requests, oldest day first; "q" matches the employee's name or code. */
    @Transactional(readOnly = true)
    public PageResponse<DayOffResponse> page(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to, String query, Integer page, Integer size) {
        var spec = filter(requestedAreaId, requestedEmployeeId, status, from, to, query);
        return PageResponse.from(repository.findAll(spec, PageResponse.request(page, size, Sort.by("offDate", "id"))),
                DayOffResponse::from);
    }

    private Specification<DayOffRequest> filter(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to, String query) {
        CurrentUser user = accessPolicy.current();
        Long areaId = accessPolicy.restrictToArea(requestedAreaId);
        // Employees only ever see their own requests.
        Long employeeId = user.isEmployee() ? user.id() : requestedEmployeeId;
        return (root, cq, cb) -> {
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
            if (query != null && !query.isBlank()) {
                String like = "%" + EmployeeService.escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
                var employee = root.get("employee");
                predicates.add(cb.or(
                        cb.like(cb.lower(employee.get("firstName")), like, '\\'),
                        cb.like(cb.lower(employee.get("lastName")), like, '\\'),
                        cb.like(cb.lower(cb.concat(cb.concat(employee.get("firstName"), " "), employee.get("lastName"))),
                                like, '\\'),
                        cb.like(cb.lower(employee.get("employeeCode")), like, '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
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
        boolean emergency = Boolean.TRUE.equals(request.emergency());

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
        if (!emergency) {
            requestedPerWeek.forEach((monday, count) -> {
                long existing = repository.countByEmployeeIdAndOffDateBetweenAndStatusInAndEmergencyFalse(
                        employee.getId(), monday, monday.plusDays(6), OPEN_STATUSES);
                if (existing + count > maxPerWeek) {
                    throw new BusinessRuleException("At most " + maxPerWeek + " days off can be requested in one week "
                            + "(week of " + monday + " already has " + existing + "). For something urgent, send an "
                            + "emergency request.");
                }
            });
            // One person off per store per day, so the store's primary and secondary are never both away.
            List<TakenDay> taken = takenDays(employee, dates.first(), dates.last()).stream()
                    .filter(t -> dates.contains(t.date()))
                    .toList();
            if (!taken.isEmpty()) {
                TakenDay t = taken.getFirst();
                throw new BusinessRuleException(t.takenBy() + " already has " + dayName(t.date()) + " off at "
                        + employee.getHomeStore().getName() + ". Only one person per store can be off on a day. "
                        + "For something urgent, send an emergency request.");
            }
        }

        List<DayOffResponse> created = new ArrayList<>();
        for (LocalDate date : dates) {
            DayOffRequest r = new DayOffRequest();
            r.setEmployee(employee);
            r.setOffDate(date);
            r.setReason(request.reason());
            r.setEmergency(emergency);
            created.add(DayOffResponse.from(repository.save(r)));
        }
        return created;
    }

    /**
     * Days in the range that a colleague at the same store already has off (pending or approved). Employees can only
     * ask about themselves; managers about people in their area.
     */
    @Transactional(readOnly = true)
    public List<TakenDay> takenDays(Long employeeId, LocalDate from, LocalDate to) {
        Employee employee = employeeService.getEmployee(employeeId);
        CurrentUser user = accessPolicy.current();
        if (user.isEmployee() && !employee.getId().equals(user.id())) {
            throw new AccessDeniedException("You can only check your own days off.");
        }
        if (!user.isEmployee()) {
            accessPolicy.requireArea(areaOf(employee));
        }
        return takenDays(employee, from, to);
    }

    private List<TakenDay> takenDays(Employee employee, LocalDate from, LocalDate to) {
        if (employee.getHomeStore() == null) {
            return List.of(); // Floaters aren't tied to a store, so nobody blocks them.
        }
        return repository.findSameStore(employee.getHomeStore().getId(), employee.getId(), from, to, OPEN_STATUSES)
                .stream()
                .map(r -> new TakenDay(r.getOffDate(), r.getEmployee().getFirstName(), r.getStatus()))
                .toList();
    }

    private static String dayName(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US) + " "
                + date.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) + " "
                + date.getDayOfMonth();
    }

    @Transactional
    public DayOffResponse approve(Long id, String note) {
        DayOffResponse approved = review(id, DayOffStatus.APPROVED, note);
        // If that week's schedule was already made, the person must not stay scheduled to work that day.
        DayOffRequest r = get(id);
        return approved.withScheduleUpdate(scheduleService.applyApprovedDayOff(r.getEmployee(), r.getOffDate()));
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
