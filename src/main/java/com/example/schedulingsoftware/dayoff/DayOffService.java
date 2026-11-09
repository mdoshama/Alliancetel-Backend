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
import com.example.schedulingsoftware.common.InputRules;
import com.example.schedulingsoftware.common.PageResponse;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.ApproveManyResult;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.SkippedRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse;
import com.example.schedulingsoftware.dayoff.DayOffDtos.TakenDay;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayRoom;
import com.example.schedulingsoftware.notify.ScheduleNotifier;
import com.example.schedulingsoftware.schedule.ScheduleService;

import jakarta.persistence.criteria.Predicate;

@Service
public class DayOffService {

    private static final EnumSet<DayOffStatus> OPEN_STATUSES = EnumSet.of(DayOffStatus.PENDING, DayOffStatus.APPROVED);

    private final DayOffRequestRepository repository;
    private final EmployeeService employeeService;
    private final AccessPolicy accessPolicy;
    private final ScheduleService scheduleService;
    private final ScheduleNotifier notifier;
    private final int maxPerWeek;
    /** Weekly request window: people ask for next week's days off by Friday (company time zone). */
    private final boolean requestWindow;
    /** At most one emergency request per calendar month. */
    private final boolean oneEmergencyPerMonth;
    private final java.time.ZoneId zone;
    private final DayOffReservations reservations;
    private final RequestPortal portal;

    public DayOffService(DayOffRequestRepository repository,
            EmployeeService employeeService,
            AccessPolicy accessPolicy,
            ScheduleService scheduleService,
            ScheduleNotifier notifier,
            @Value("${alliancetel.scheduling.max-day-off-requests-per-week:2}") int maxPerWeek,
            @Value("${alliancetel.scheduling.request-window:true}") boolean requestWindow,
            @Value("${alliancetel.scheduling.one-emergency-per-month:true}") boolean oneEmergencyPerMonth,
            @Value("${alliancetel.scheduling.zone:America/Los_Angeles}") String zone,
            DayOffReservations reservations,
            RequestPortal portal) {
        this.reservations = reservations;
        this.portal = portal;
        this.requestWindow = requestWindow;
        this.oneEmergencyPerMonth = oneEmergencyPerMonth;
        this.zone = java.time.ZoneId.of(zone);
        this.repository = repository;
        this.employeeService = employeeService;
        this.accessPolicy = accessPolicy;
        this.scheduleService = scheduleService;
        this.notifier = notifier;
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
        return page(requestedAreaId, requestedEmployeeId, status, from, to, query, null, null, page, size);
    }

    /**
     * Same, optionally only reserved days ({@code reserved = true}) or only ones people asked for ({@code false}),
     * and only regular days off ({@code regular = true}) or none of them ({@code false}).
     */
    @Transactional(readOnly = true)
    public PageResponse<DayOffResponse> page(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to, String query, Boolean reserved, Boolean regular, Integer page, Integer size) {
        var spec = filter(requestedAreaId, requestedEmployeeId, status, from, to, query, reserved, regular);
        return PageResponse.from(repository.findAll(spec, PageResponse.request(page, size, Sort.by("offDate", "id"))),
                DayOffResponse::from);
    }

    private Specification<DayOffRequest> filter(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to, String query) {
        return filter(requestedAreaId, requestedEmployeeId, status, from, to, query, null, null);
    }

    private Specification<DayOffRequest> filter(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to, String query, Boolean reserved, Boolean regular) {
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
            if (reserved != null) {
                predicates.add(cb.equal(root.get("reserved"), reserved));
            }
            if (regular != null) {
                predicates.add(cb.equal(root.get("regular"), regular));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("offDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("offDate"), to));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + EmployeeService.escapeLike(EmployeeService.shorten(query).toLowerCase(Locale.ROOT)) + "%";
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
        if (!employee.worksShifts()) {
            throw new BusinessRuleException(employee.getFullName() + " isn't on the schedule (only manages), so there "
                    + "are no days off to request.");
        }
        LocalDate today = LocalDate.now();
        TreeSet<LocalDate> dates = new TreeSet<>(request.dates());
        boolean emergency = Boolean.TRUE.equals(request.emergency());

        // Group the requested dates by week (Monday) so the weekly limit is checked per week.
        // People asking for themselves (not admins) follow the weekly window and the emergency limit; a manager
        // entering a request for someone else, or an admin, can make exceptions.
        boolean selfService = !user.isAdmin() && employee.getId().equals(user.id());
        boolean override = Boolean.TRUE.equals(request.override());
        if (override && (user.isEmployee() || employee.getId().equals(user.id()))) {
            throw new AccessDeniedException("Only a manager or admin can give someone else a day off directly.");
        }
        if (selfService && !emergency && requestWindow) {
            checkWindow(dates);
        }
        if (selfService && emergency && oneEmergencyPerMonth) {
            checkEmergencyLimit(employee, dates);
        }
        // Asking for a day that is already reserved for them just keeps it (now as their own choice).
        List<DayOffResponse> confirmed = new ArrayList<>();
        for (java.util.Iterator<LocalDate> it = dates.iterator(); it.hasNext();) {
            LocalDate date = it.next();
            DayOffRequest existing = repository.findByEmployeeIdAndOffDate(employee.getId(), date).orElse(null);
            if (existing != null && existing.isReserved() && existing.getStatus() != DayOffStatus.REJECTED
                    && !override) {
                // Now their own request; still waiting for a manager unless one already approved it.
                existing.setReserved(false);
                existing.setRegular(false);
                existing.setReason(InputRules.clean(request.reason()));
                confirmed.add(DayOffResponse.from(existing));
                it.remove();
            }
        }
        if (dates.isEmpty()) {
            return confirmed;
        }
        Map<LocalDate, Integer> requestedPerWeek = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            if (date.isAfter(today.plusYears(1))) {
                throw new BusinessRuleException("Days off can be requested up to a year ahead (" + date + " is too far).");
            }
            if (date.isBefore(today)) {
                throw new BusinessRuleException("Cannot request a day off in the past (" + date + ").");
            }
            if (override) {
                DayOffRequest existing = repository.findByEmployeeIdAndOffDate(employee.getId(), date).orElse(null);
                if (existing != null && existing.getStatus() == DayOffStatus.APPROVED) {
                    throw new BusinessRuleException(employee.getFullName() + " already has " + dayName(date) + " off.");
                }
                if (existing != null) {
                    // Their pending (or reserved, or turned-down) request for that day becomes this one.
                    repository.delete(existing);
                    repository.flush();
                }
            } else if (repository.existsByEmployeeIdAndOffDate(employee.getId(), date)) {
                throw new BusinessRuleException(employee.getFullName() + " already has a request for " + date + ".");
            }
            requestedPerWeek.merge(weekStart(date), 1, Integer::sum);
        }
        if (!emergency && !override) {
            requestedPerWeek.forEach((monday, count) -> {
                long existing = repository.countByEmployeeIdAndOffDateBetweenAndStatusInAndEmergencyFalseAndReservedFalse(
                        employee.getId(), monday, monday.plusDays(6), OPEN_STATUSES);
                if (existing + count > maxPerWeek) {
                    throw new BusinessRuleException("At most " + maxPerWeek + " days off can be requested in one week "
                            + "(week of " + monday + " already has " + existing + "). For something urgent, send an "
                            + "emergency request.");
                }
            });
            // One person off per store per day, so the store's primary and secondary are never both away. A day the
            // system only reserved for a colleague (not approved yet) doesn't block the request: if a manager approves
            // it, the colleague's reservation moves to another day.
            if (employee.getHomeStore() != null) {
                DayOffRequest real = repository.findSameStore(employee.getHomeStore().getId(), employee.getId(),
                        dates.first(), dates.last(), OPEN_STATUSES).stream()
                        .filter(r -> dates.contains(r.getOffDate())
                                && !r.isMovable())
                        .findFirst().orElse(null);
                if (real != null) {
                    throw new BusinessRuleException(real.getEmployee().getFirstName() + " already has "
                            + dayName(real.getOffDate()) + " off at " + employee.getHomeStore().getName()
                            + ". Only one person per store can be off on a day. "
                            + "For something urgent, send an emergency request.");
                }
            }
            // The whole area: never accept more days off than the stores can spare that day (other people's reserved
            // days can move, so they don't count against it).
            for (LocalDate date : dates) {
                DayRoom room = scheduleService.dayRoom(employee, date, OPEN_STATUSES, true);
                if (room.full()) {
                    throw new BusinessRuleException(fullDayMessage(room));
                }
            }
        }

        List<DayOffRequest> saved = new ArrayList<>();
        for (LocalDate date : dates) {
            DayOffRequest r = new DayOffRequest();
            r.setEmployee(employee);
            r.setOffDate(date);
            r.setReason(InputRules.clean(request.reason()));
            r.setEmergency(emergency);
            saved.add(repository.save(r));
        }
        List<DayOffResponse> created = new ArrayList<>(confirmed);
        if (override) {
            // A manager or admin decided: approved now (their reserved day that week is given back).
            String note = "Given by " + user.fullName() + ".";
            saved.forEach(r -> created.add(approve(r.getId(), note)));
            return created;
        }
        // Requests wait for a manager or admin. Their reserved day stays until one is approved (then it's given back).
        saved.forEach(r -> created.add(DayOffResponse.from(r)));
        return created;
    }

    /** Days the system reserved for other people in the area on this date. */
    private List<DayOffRequest> reservedOthersOn(Employee employee, LocalDate date) {
        Long area = areaOf(employee);
        if (area == null) {
            return List.of();
        }
        // Only ones nobody approved yet can move; a reserved day a manager approved stays.
        return repository.findForAreaOnDate(area, date, List.of(DayOffStatus.PENDING)).stream()
                .filter(r -> r.isMovable() && !r.getEmployee().getId().equals(employee.getId()))
                .toList();
    }

    /**
     * Gives days off chosen by a manager (the suggested days off): each becomes an approved request with a note,
     * the schedule is updated if the week was already made, and the person is told. Skips the request rules:
     * this is the manager deciding.
     */
    @Transactional
    public List<DayOffResponse> assignDaysOff(Long areaId, List<DayOffDtos.AssignedDay> items) {
        accessPolicy.requireManagerOf(areaId);
        List<DayOffResponse> result = new ArrayList<>();
        for (DayOffDtos.AssignedDay item : items) {
            Employee e = employeeService.getEmployee(item.employeeId());
            if (e.getArea() == null || !areaId.equals(e.getArea().getId()) || !e.isActive()) {
                throw new BusinessRuleException(e.getFullName() + " isn't active in this area.");
            }
            if (item.date().isBefore(LocalDate.now(zone))) {
                throw new BusinessRuleException("Can't give a day off in the past (" + item.date() + ").");
            }
            if (repository.existsByEmployeeIdAndOffDate(e.getId(), item.date())) {
                throw new BusinessRuleException(e.getFullName() + " already has a request for " + dayName(item.date())
                        + ". Pick another day.");
            }
            DayOffRequest r = new DayOffRequest();
            r.setEmployee(e);
            r.setOffDate(item.date());
            r.setReason("Day off given by your manager");
            repository.save(r);
            int perWeek = Math.max(1, 7 - e.getWorkDaysPerWeek());
            result.add(approve(r.getId(), perWeek > 1
                    ? "You take " + perWeek + " days off a week, so your manager picked this one for you."
                    : "No day-off request came in, so your manager picked this day for you."));
        }
        return result;
    }

    /**
     * The week people can ask for right now, and its Friday deadline. Each week's requests open on the Saturday
     * nine days before it starts and close at the end of the Friday before it (company time zone).
     */
    public DayOffDtos.RequestWindow window() {
        LocalDate today = portal.today();
        LocalDate open = RequestPortal.openWeek(today);
        RequestPortalSetting setting = portal.setting();
        List<LocalDate> weeks = RequestPortal.openWeeks(setting.getMode(), today);
        return new DayOffDtos.RequestWindow(requestWindow, open, open.plusDays(6), open.minusDays(3),
                open.plusDays(7).minusDays(9), oneEmergencyPerMonth,
                oneEmergencyPerMonth && emergencyUsed(accessPolicy.current().id(), today), setting.getMode(), weeks,
                setting.getChangedBy(), setting.getChangedAt(), reservations.isEnabled());
    }

    /** Admins: opens or closes the request portal, or puts it back on the normal Friday cycle. */
    @Transactional
    public DayOffDtos.RequestWindow setPortal(PortalMode mode) {
        CurrentUser user = accessPolicy.current();
        if (!user.isAdmin()) {
            throw new AccessDeniedException("Only admins can open or close day-off requests.");
        }
        portal.setMode(mode, user.fullName());
        if (mode != PortalMode.CLOSED) {
            reservations.reserveUpcoming();
        }
        return window();
    }

    /** Monday of the week open for requests: next week until Friday, the week after from Saturday. */
    static LocalDate openWeek(LocalDate today) {
        return RequestPortal.openWeek(today);
    }

    private void checkWindow(java.util.Collection<LocalDate> dates) {
        LocalDate today = portal.today();
        PortalMode mode = portal.mode();
        if (mode == PortalMode.CLOSED) {
            throw new BusinessRuleException("Day-off requests are closed right now. For something urgent, send an "
                    + "emergency request, or ask your manager.");
        }
        List<LocalDate> open = RequestPortal.openWeeks(mode, today);
        for (LocalDate date : dates) {
            LocalDate week = weekStart(date);
            if (open.contains(week)) {
                continue;
            }
            LocalDate first = open.getFirst();
            LocalDate last = open.getLast();
            if (week.isBefore(first)) {
                throw new BusinessRuleException("Requests for the week of " + shortDate(week) + " closed on Friday, "
                        + shortDate(week.minusDays(3)) + ". For something urgent, send an emergency request.");
            }
            throw new BusinessRuleException("Right now you can only ask for days off in the week of "
                    + shortDate(first) + "–" + shortDate(last.plusDays(6)) + ". Requests for the week of "
                    + shortDate(week) + " open on Saturday, " + shortDate(week.minusDays(9)) + ".");
        }
    }

    private void checkEmergencyLimit(Employee employee, java.util.Collection<LocalDate> dates) {
        for (LocalDate date : dates) {
            if (emergencyUsed(employee.getId(), date)) {
                throw new BusinessRuleException("You've already used your emergency request for "
                        + date.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.US)
                        + ". Only one is allowed each month; please talk to your manager.");
            }
        }
    }

    /** Whether they already have an emergency request (pending or approved) in the month of this date. */
    private boolean emergencyUsed(Long employeeId, LocalDate anyDayInMonth) {
        LocalDate first = anyDayInMonth.withDayOfMonth(1);
        return repository.existsByEmployeeIdAndEmergencyTrueAndOffDateBetweenAndStatusIn(employeeId, first,
                first.plusMonths(1).minusDays(1), OPEN_STATUSES);
    }

    private static String shortDate(LocalDate d) {
        return d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US) + " " + d.getDayOfMonth();
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

    /** Days the employee can't ask for normally: same-store days first, then days the area has no one to spare. */
    private List<TakenDay> takenDays(Employee employee, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(62).isBefore(to)) {
            throw new BusinessRuleException("Pick a range of up to two months.");
        }
        List<TakenDay> result = new ArrayList<>(sameStoreDays(employee, from, to));
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            LocalDate date = d;
            if (result.stream().noneMatch(t -> t.date().equals(date))
                    && scheduleService.dayRoom(employee, date, OPEN_STATUSES, true).full()) {
                result.add(TakenDay.full(date));
            }
        }
        result.sort(java.util.Comparator.comparing(TakenDay::date));
        return result;
    }

    /** Days a colleague at the same home store is off. Floaters aren't tied to a store, so nobody blocks them. */
    private List<TakenDay> sameStoreDays(Employee employee, LocalDate from, LocalDate to) {
        if (employee.getHomeStore() == null) {
            return List.of();
        }
        // A colleague's reserved day isn't shown as taken: asking for it moves their reservation to another day.
        return repository.findSameStore(employee.getHomeStore().getId(), employee.getId(), from, to, OPEN_STATUSES)
                .stream()
                .filter(r -> !r.isMovable())
                .map(r -> new TakenDay(r.getOffDate(), r.getEmployee().getFirstName(), r.getStatus()))
                .toList();
    }

    private static String fullDayMessage(DayRoom room) {
        String who = room.offNames().isEmpty() ? ""
                : " " + String.join(", ", room.offNames()) + (room.offNames().size() == 1 ? " is" : " are") + " already off.";
        int shortBy = Math.max(1, room.needed() - Math.max(0, room.working()));
        return dayName(room.date()) + " is full: the stores need " + room.needed() + " people that day and the others "
                + "working can't cover them all, so a store would be short" + (room.working() < room.needed()
                        ? " by " + shortBy : "")
                + ". Primaries stay at their store and floaters only cover the stores assigned to them." + who
                + " Pick another day, or send an emergency request if it's urgent.";
    }

    private static String dayName(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US) + " "
                + date.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) + " "
                + date.getDayOfMonth();
    }

    @Transactional
    public DayOffResponse approve(Long id, String note) {
        DayOffResponse approved = review(id, DayOffStatus.APPROVED, note);
        DayOffRequest r = get(id);
        Employee e = r.getEmployee();
        LocalDate date = r.getOffDate();
        // Reserved days of others give way to an approved request: a colleague at the same store, and others in the
        // area if the stores would otherwise be short that day. They get another day reserved.
        List<DayOffRequest> aside = new ArrayList<>();
        if (e.getHomeStore() != null) {
            repository.findSameStore(e.getHomeStore().getId(), e.getId(), date, date, OPEN_STATUSES).stream()
                    .filter(DayOffRequest::isMovable).forEach(aside::add);
        }
        aside.forEach(reservations::moveAside);
        for (DayOffRequest other : reservedOthersOn(e, date)) {
            if (!scheduleService.dayRoom(e, date, OPEN_STATUSES).full()) {
                break;
            }
            aside.add(other);
            reservations.moveAside(other);
        }
        // If that week's schedule was already made, the person must not stay scheduled to work that day.
        String update = scheduleService.applyApprovedDayOff(e, date);
        // Their own reserved day(s) that week go back to work.
        List<LocalDate> released = reservations.trim(e, date);
        String message = r.getReviewNote();
        if (!released.isEmpty()) {
            String back = "Your reserved day off on "
                    + String.join(" and ", released.stream().map(DayOffService::dayName).toList())
                    + " is now a work day.";
            message = message == null ? back : message + " " + back;
        }
        notifier.dayOffDecided(r, true, message);
        repository.flush();
        aside.forEach(o -> reservations.refill(o.getEmployee(), o.getOffDate()));
        return approved.withScheduleUpdate(update);
    }

    /**
     * Approves several pending requests in one go, earliest day first (emergencies first on the same day). Each one is
     * checked against the schedule as it stands after the ones before it, so "only if covered" never approves two
     * people off when the stores can spare one. Requests that can't be approved are left pending, with the reason.
     */
    @Transactional
    public ApproveManyResult approveMany(List<Long> ids, boolean onlyIfCovered) {
        // Work from the ids, not the loaded rows: approving one request can give back (delete and re-reserve) a
        // reserved day that is also in this batch, so each one is looked up again just before it's decided.
        List<Long> order = repository.findAllById(new java.util.LinkedHashSet<>(ids)).stream()
                .sorted(java.util.Comparator.comparing(DayOffRequest::getOffDate)
                        .thenComparing(r -> !r.isEmergency())
                        .thenComparing(DayOffRequest::getId))
                .map(DayOffRequest::getId)
                .toList();
        CurrentUser user = accessPolicy.current();
        List<DayOffResponse> approved = new ArrayList<>();
        List<SkippedRequest> skipped = new ArrayList<>();
        for (Long id : order) {
            DayOffRequest r = repository.findById(id).orElse(null);
            if (r == null) {
                // A reserved day given back because an earlier request in this batch was approved. Nothing to decide.
                continue;
            }
            Employee e = r.getEmployee();
            String why = null;
            if (r.getStatus() != DayOffStatus.PENDING) {
                why = "Already " + r.getStatus().name().toLowerCase() + ".";
            } else if (e.getId().equals(user.id())) {
                why = "Your own request: an admin decides on it.";
            } else if (!user.isAdmin() && !java.util.Objects.equals(areaOf(e), user.areaId())) {
                why = "Not in your area.";
            } else if (onlyIfCovered) {
                var impact = scheduleService.dayOffImpact(r.getId());
                if (impact.level() == com.example.schedulingsoftware.schedule.ScheduleDtos.ImpactLevel.SHORT) {
                    why = impact.summary();
                }
            }
            if (why != null) {
                skipped.add(new SkippedRequest(r.getId(), e.getFullName(), r.getOffDate(), why));
            } else {
                approved.add(approve(r.getId(), null));
            }
        }
        return new ApproveManyResult(approved, skipped);
    }

    @Transactional
    public DayOffResponse reject(Long id, String note) {
        DayOffResponse rejected = review(id, DayOffStatus.REJECTED, note);
        DayOffRequest r = get(id);
        notifier.dayOffDecided(r, false, r.getReviewNote());
        reservations.refill(r.getEmployee(), r.getOffDate());
        return rejected;
    }

    /**
     * A manager (of the person's area) or an admin changes a decision already made: approved becomes rejected, or
     * rejected becomes approved. A reason is required; it is kept on the request and sent to the employee. The week's
     * schedule follows: a revoked day off becomes a work day again, a newly approved one is taken off.
     */
    @Transactional
    public DayOffResponse changeDecision(Long id, DayOffStatus newStatus, String reason) {
        DayOffRequest r = get(id);
        CurrentUser user = accessPolicy.current();
        accessPolicy.requireManagerOf(areaOf(r.getEmployee()));
        if (r.getEmployee().getId().equals(user.id()) && !user.isAdmin()) {
            throw new AccessDeniedException("You cannot change the decision on your own request. An admin can.");
        }
        String why = InputRules.clean(reason);
        if (why == null) {
            throw new BusinessRuleException("Give a reason for changing the decision.");
        }
        if (newStatus == null || newStatus == DayOffStatus.PENDING) {
            throw new BusinessRuleException("A decision can only be changed to approved or rejected.");
        }
        DayOffStatus old = r.getStatus();
        if (old == DayOffStatus.PENDING) {
            throw new BusinessRuleException("This request hasn't been decided yet. Approve or reject it instead.");
        }
        if (old == newStatus) {
            throw new BusinessRuleException("This request is already " + old.name().toLowerCase() + ".");
        }
        if (r.getOffDate().isBefore(LocalDate.now())) {
            throw new BusinessRuleException(dayName(r.getOffDate()) + " has already passed, so the decision can't be "
                    + "changed.");
        }
        r.setPreviousStatus(old);
        r.setStatus(newStatus);
        r.setDecisionChangedBy(user.fullName());
        r.setDecisionChangeReason(why);
        r.setDecisionChangedAt(Instant.now());
        r.setReviewedAt(Instant.now());
        repository.flush();

        String update = newStatus == DayOffStatus.APPROVED
                ? scheduleService.applyApprovedDayOff(r.getEmployee(), r.getOffDate())
                : scheduleService.revokeApprovedDayOff(r.getEmployee(), r.getOffDate());
        notifier.dayOffDecisionChanged(r, newStatus == DayOffStatus.APPROVED, why);
        if (newStatus == DayOffStatus.APPROVED) {
            reservations.trim(r.getEmployee(), r.getOffDate());
        } else {
            reservations.refill(r.getEmployee(), r.getOffDate());
        }
        return DayOffResponse.from(r).withScheduleUpdate(update);
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
        if (own && r.isReserved()) {
            throw new BusinessRuleException("This day was reserved for you. To change it, ask for a different day; "
                    + "once your manager approves it, this one goes back to being a work day.");
        }
        Employee e = r.getEmployee();
        LocalDate date = r.getOffDate();
        repository.delete(r);
        repository.flush();
        reservations.refill(e, date);
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
        r.setReviewNote(InputRules.clean(note));
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
