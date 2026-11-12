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

    /** Note on days off the system gave by itself (the stores stay covered). */
    static final String AUTO_NOTE = "Given automatically: the stores stay covered.";


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
    private final com.example.schedulingsoftware.area.AreaRepository areaRepository;

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
            RequestPortal portal,
            com.example.schedulingsoftware.area.AreaRepository areaRepository) {
        this.areaRepository = areaRepository;
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
        return page(requestedAreaId, requestedEmployeeId, status, from, to, query, null, null, null, page, size);
    }

    /**
     * Same, optionally only reserved days ({@code reserved = true}) or only ones people asked for ({@code false}),
     * only regular days off ({@code regular = true}) or none of them ({@code false}), and only emergencies
     * ({@code emergency = true}) or none ({@code false}).
     */
    @Transactional(readOnly = true)
    public PageResponse<DayOffResponse> page(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to, String query, Boolean reserved, Boolean regular, Boolean emergency,
            Integer page, Integer size) {
        var spec = filter(requestedAreaId, requestedEmployeeId, status, from, to, query, reserved, regular, emergency);
        // Managers and admins see each person's days together, people A to Z; employees see their own by date.
        Sort order = accessPolicy.current().isEmployee() ? Sort.by("offDate", "id")
                : Sort.by("employee.firstName", "employee.lastName", "employee.id", "offDate", "id");
        return PageResponse.from(repository.findAll(spec, PageResponse.request(page, size, order)),
                DayOffResponse::from);
    }

    private Specification<DayOffRequest> filter(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to, String query) {
        return filter(requestedAreaId, requestedEmployeeId, status, from, to, query, null, null, null);
    }

    private Specification<DayOffRequest> filter(Long requestedAreaId, Long requestedEmployeeId, DayOffStatus status,
            LocalDate from, LocalDate to, String query, Boolean reserved, Boolean regular, Boolean emergency) {
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
            if (user.isEmployee()) {
                // Days the system picked are planning: employees see them on the published schedule only. Their
                // regular day off is theirs, so they see it.
                predicates.add(cb.or(cb.isFalse(root.get("reserved")), cb.isTrue(root.get("regular"))));
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
            if (emergency != null) {
                predicates.add(cb.equal(root.get("emergency"), emergency));
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
            checkWindow(employee, dates);
        }
        if (selfService && emergency && oneEmergencyPerMonth) {
            checkEmergencyLimit(employee, dates);
        }
        // For everyone (admins and managers too): not this week (its schedule is made), and only the next two
        // weeks, or up to the date the area was opened until.
        checkNotThisWeek(dates, selfService);
        checkHorizon(employee, dates);
        if (selfService && emergency) {
            checkEmergencyOnlyWhenFull(employee, dates);
        }
        // Asking for a day that is already reserved for them just keeps it, now as their own choice: it's theirs
        // (the stores were planned around it), so it's given right away.
        List<DayOffResponse> confirmed = new ArrayList<>();
        for (java.util.Iterator<LocalDate> it = dates.iterator(); it.hasNext();) {
            LocalDate date = it.next();
            DayOffRequest existing = repository.findByEmployeeIdAndOffDate(employee.getId(), date).orElse(null);
            if (existing != null && existing.isReserved() && existing.getStatus() != DayOffStatus.REJECTED
                    && !override) {
                // A regular day stays their regular day (it comes first); a day the system picked becomes theirs.
                if (!existing.isRegular()) {
                    existing.setReserved(false);
                    existing.setReason(InputRules.clean(request.reason()));
                }
                confirmed.add(existing.getStatus() == DayOffStatus.PENDING
                        ? grantAutomatically(existing, AUTO_NOTE)
                        : DayOffResponse.from(existing));
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
            } else if (!clearRejected(employee, date)) {
                throw new BusinessRuleException(employee.getFullName() + " already has a request for " + date + ".");
            }
            requestedPerWeek.merge(weekStart(date), 1, Integer::sum);
        }
        if (!emergency && !override) {
            int limit = normalDaysOffPerWeek(employee);
            requestedPerWeek.forEach((monday, count) -> {
                long existing = repository.countByEmployeeIdAndOffDateBetweenAndStatusInAndEmergencyFalseAndReservedFalse(
                        employee.getId(), monday, monday.plusDays(6), OPEN_STATUSES);
                if (existing + count > limit) {
                    throw new BusinessRuleException(employee.getFullName() + " works " + employee.getWorkDaysPerWeek()
                            + " days a week, so only " + limit + " day" + (limit == 1 ? "" : "s") + " off can be "
                            + "requested in one week (week of " + shortDate(monday) + " already has " + existing
                            + "). For more, send an emergency request.");
                }
            });
            String why = whyNotCovered(employee, dates);
            if (why != null) {
                throw new BusinessRuleException(why);
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
        if (emergency) {
            // Emergencies are the only requests a manager or admin decides on (they can break the coverage rules).
            saved.forEach(r -> created.add(DayOffResponse.from(r)));
            return created;
        }
        // Everything else passed the checks above (store covered, one person per store, primaries and floaters
        // accounted for), so the system gives it straight away; their reserved day that week goes back to work.
        saved.forEach(r -> created.add(grantAutomatically(r, AUTO_NOTE)));
        return created;
    }

    /**
     * Why these days can't be given without a manager, or null if they can: only one person per store off a day (so
     * a store's primary and secondary are never both away; a colleague's day the system only reserved doesn't count,
     * it can move), and never more days off in the area than the stores can spare.
     */
    private String whyNotCovered(Employee employee, java.util.SortedSet<LocalDate> dates) {
        if (employee.getHomeStore() != null) {
            DayOffRequest real = repository.findSameStore(employee.getHomeStore().getId(), employee.getId(),
                    dates.first(), dates.last(), OPEN_STATUSES).stream()
                    .filter(r -> dates.contains(r.getOffDate()) && !r.isMovable())
                    .findFirst().orElse(null);
            if (real != null) {
                return real.getEmployee().getFirstName() + " already has " + dayName(real.getOffDate()) + " off at "
                        + employee.getHomeStore().getName() + ". Only one person per store can be off on a day. "
                        + "For something urgent, send an emergency request.";
            }
        }
        for (LocalDate date : dates) {
            DayRoom room = scheduleService.dayRoom(employee, date, OPEN_STATUSES, true);
            if (room.full()) {
                return fullDayMessage(room);
            }
        }
        return null;
    }

    /**
     * Before a week is generated: requests still waiting that aren't emergencies (sent before the system gave days
     * by itself) are settled the way a new request would be: given when the stores stay covered, otherwise turned
     * down so the person gets another day. Only emergencies are left for a manager.
     *
     * @return how many were settled
     */
    @Transactional
    public int settleWaitingRequests(Long areaId, LocalDate anyDayInWeek) {
        LocalDate week = weekStart(anyDayInWeek);
        List<DayOffRequest> waiting = repository.findForArea(areaId, DayOffStatus.PENDING, week, week.plusDays(6))
                .stream().filter(r -> !r.isEmergency() && !r.isReserved())
                .sorted(java.util.Comparator.comparing(DayOffRequest::getOffDate)).toList();
        for (DayOffRequest r : waiting) {
            // Checked as if it weren't there yet, so it doesn't count against itself.
            r.setStatus(DayOffStatus.REJECTED);
            repository.flush();
            String why = whyNotCovered(r.getEmployee(), new TreeSet<>(List.of(r.getOffDate())));
            if (why == null) {
                grantAutomatically(r, AUTO_NOTE);
            } else {
                r.setReviewNote("Not given: " + why);
                r.setReviewedAt(Instant.now());
                notifier.dayOffDecided(r, false, r.getReviewNote());
            }
            repository.flush();
        }
        return waiting.size();
    }

    /** "Your regular day off on Tuesday is a work day this week." for days given back, or null. */
    private static String releasedNote(List<DayOffRequest> released) {
        if (released.isEmpty()) {
            return null;
        }
        List<String> regular = released.stream().filter(DayOffRequest::isRegular)
                .map(r -> dayName(r.getOffDate())).toList();
        List<String> reserved = released.stream().filter(r -> !r.isRegular())
                .map(r -> dayName(r.getOffDate())).toList();
        List<String> parts = new ArrayList<>();
        if (!regular.isEmpty()) {
            parts.add("Your regular day off on " + String.join(" and ", regular) + " is a work day this week.");
        }
        if (!reserved.isEmpty()) {
            parts.add("Your reserved day off on " + String.join(" and ", reserved) + " is now a work day.");
        }
        return String.join(" ", parts);
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
            if (!clearRejected(e, item.date())) {
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
        CurrentUser user = accessPolicy.current();
        com.example.schedulingsoftware.area.Area area = user.areaId() == null ? null
                : areaRepository.findById(user.areaId()).orElse(null);
        LocalDate until = openUntil(area);
        return new DayOffDtos.RequestWindow(requestWindow, open, open.plusDays(6), open.minusDays(3),
                open.plusDays(7).minusDays(9), oneEmergencyPerMonth,
                oneEmergencyPerMonth && emergencyUsed(user.id(), today), setting.getMode(), weeks,
                setting.getChangedBy(), setting.getChangedAt(), reservations.isEnabled(),
                until, until == null ? null : area.getRequestsOpenedBy(), lastSelfServiceDate(until));
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

    /** Managers (their own area) and admins: employees may ask up to {@code until}; null goes back to the usual window. */
    @Transactional
    public DayOffDtos.OpenUntilResponse setOpenUntil(Long areaId, LocalDate until) {
        accessPolicy.requireManagerOf(areaId);
        com.example.schedulingsoftware.area.Area area = areaRepository.findById(areaId)
                .orElseThrow(() -> new ResourceNotFoundException("Area", areaId));
        LocalDate today = portal.today();
        if (until != null) {
            if (!until.isAfter(today)) {
                throw new BusinessRuleException("Pick a date after today.");
            }
            if (until.isAfter(today.plusYears(1))) {
                throw new BusinessRuleException("Requests can be opened up to a year ahead.");
            }
        }
        area.setRequestsOpenUntil(until);
        area.setRequestsOpenedBy(until == null ? null : accessPolicy.current().fullName());
        area.setRequestsOpenedAt(until == null ? null : Instant.now());
        return new DayOffDtos.OpenUntilResponse(areaId, until, area.getRequestsOpenedBy());
    }

    /**
     * This week's schedule is already made: people asking for themselves start at next week. A manager or admin can
     * still give someone a day off this week.
     */
    private void checkNotThisWeek(java.util.Collection<LocalDate> dates, boolean selfService) {
        LocalDate nextMonday = weekStart(portal.today()).plusWeeks(1);
        for (LocalDate date : dates) {
            if (date.isBefore(nextMonday)) {
                throw new BusinessRuleException("This week's schedule is already made, so " + shortDate(date)
                        + " can't be requested. " + (selfService
                                ? "If you need a day off this week, ask your manager."
                                : "To change this week, edit the schedule itself."));
            }
        }
    }

    /** The date the area takes requests up to, when a manager opened them further ahead and it hasn't passed. */
    private LocalDate openUntil(com.example.schedulingsoftware.area.Area area) {
        LocalDate until = area == null ? null : area.getRequestsOpenUntil();
        return until != null && !until.isBefore(portal.today()) ? until : null;
    }

    /** The last date someone asking for themselves can pick: two weeks ahead, or further when their area is open. */
    private LocalDate lastSelfServiceDate(LocalDate openUntil) {
        LocalDate usual = weekStart(portal.today()).plusWeeks(SELF_SERVICE_WEEKS_AHEAD).plusDays(6);
        return openUntil != null && openUntil.isAfter(usual) ? openUntil : usual;
    }

    /**
     * Whether someone asking for themselves can ask for this date now: in a week taking requests, or (from next week
     * on) up to the date their manager opened requests until.
     */
    private boolean openForRequests(Employee employee, LocalDate date) {
        LocalDate today = portal.today();
        if (RequestPortal.openWeeks(portal.mode(), today).contains(weekStart(date))) {
            return true;
        }
        LocalDate until = openUntil(employee.getArea());
        return until != null && !date.isAfter(until) && !weekStart(date).isBefore(weekStart(today).plusWeeks(1));
    }

    private void checkWindow(Employee employee, java.util.Collection<LocalDate> dates) {
        LocalDate today = portal.today();
        PortalMode mode = portal.mode();
        LocalDate until = openUntil(employee.getArea());
        if (until != null) {
            for (LocalDate date : dates) {
                if (!openForRequests(employee, date)) {
                    throw new BusinessRuleException("You can ask for days off from the week of "
                            + shortDate(weekStart(today).plusWeeks(1)) + " until " + shortDate(until) + ". "
                            + shortDate(date) + " isn't in that range.");
                }
            }
            return;
        }
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

    /**
     * Days off a normal request can get in one week: their usual days off (1 for a 6-day worker, 2 for a 5-day
     * worker), never more than the configured weekly maximum. Anything beyond needs an emergency request.
     */
    private int normalDaysOffPerWeek(Employee employee) {
        return Math.max(1, Math.min(maxPerWeek, 7 - employee.getWorkDaysPerWeek()));
    }

    /** How many weeks past the current one people asking for themselves can request days off. */
    static final int SELF_SERVICE_WEEKS_AHEAD = 2;

    /** People asking for themselves can only reach the current week and the next two. */
    private void checkHorizon(Employee employee, java.util.Collection<LocalDate> dates) {
        LocalDate until = openUntil(employee.getArea());
        LocalDate lastAllowed = lastSelfServiceDate(until);
        for (LocalDate date : dates) {
            if (date.isAfter(lastAllowed)) {
                throw new BusinessRuleException(until != null && until.equals(lastAllowed)
                        ? "Requests are open until " + shortDate(lastAllowed) + ". " + shortDate(date) + " is too far."
                        : "Days off can be requested up to " + SELF_SERVICE_WEEKS_AHEAD + " weeks ahead (until "
                                + shortDate(lastAllowed) + "). " + shortDate(date) + " is too far.");
            }
        }
    }

    /** An emergency request is only for a week with no day left that a normal request could get. */
    /**
     * An emergency is for a day they can't get with a normal request: the day is full or a colleague at their store
     * has it, or they've already used their days off that week. A day still open is asked for normally.
     */
    private void checkEmergencyOnlyWhenFull(Employee employee, java.util.Collection<LocalDate> dates) {
        for (LocalDate date : dates) {
            if (openForNormalRequest(employee, date)) {
                throw new BusinessRuleException(dayName(date) + " is still open, so ask for it as a normal day off. "
                        + "Emergency requests are for days that are full, or once your days off that week are used.");
            }
        }
    }

    /** Whether they could get this day with a normal request right now. */
    private boolean openForNormalRequest(Employee employee, LocalDate date) {
        if (date.isBefore(portal.today()) || (requestWindow && !openForRequests(employee, date))) {
            return false;
        }
        LocalDate monday = weekStart(date);
        long normal = repository.countByEmployeeIdAndOffDateBetweenAndStatusInAndEmergencyFalseAndReservedFalse(
                employee.getId(), monday, monday.plusDays(6), OPEN_STATUSES);
        if (normal >= normalDaysOffPerWeek(employee)) {
            return false;
        }
        boolean taken = takenDays(employee, date, date).stream().anyMatch(t -> t.date().equals(date));
        return !taken && !hasLiveRequest(employee.getId(), date);
    }

    /** The first day in the week they could still get with a normal request, or null when the week is full for them. */
    private LocalDate firstOpenDay(Employee employee, LocalDate monday) {
        LocalDate today = portal.today();
        LocalDate sunday = monday.plusDays(6);
        LocalDate from = monday.isBefore(today) ? today : monday;
        if (from.isAfter(sunday)) {
            return null;
        }
        long normal = repository.countByEmployeeIdAndOffDateBetweenAndStatusInAndEmergencyFalseAndReservedFalse(
                employee.getId(), monday, sunday, OPEN_STATUSES);
        if (normal >= normalDaysOffPerWeek(employee)) {
            return null;
        }
        java.util.Set<LocalDate> taken = new java.util.HashSet<>();
        takenDays(employee, from, sunday).forEach(t -> taken.add(t.date()));
        for (LocalDate d = from; !d.isAfter(sunday); d = d.plusDays(1)) {
            if ((!requestWindow || openForRequests(employee, d)) && !taken.contains(d)
                    && !hasLiveRequest(employee.getId(), d)) {
                return d;
            }
        }
        return null;
    }

    /**
     * Makes the day free to ask for again: a request that was turned down doesn't block a new one for the same day (it
     * is replaced). Returns false when they still have a live request (waiting or approved) that day.
     */
    private boolean clearRejected(Employee employee, LocalDate date) {
        DayOffRequest existing = repository.findByEmployeeIdAndOffDate(employee.getId(), date).orElse(null);
        if (existing == null) {
            return true;
        }
        if (existing.getStatus() != DayOffStatus.REJECTED) {
            return false;
        }
        repository.delete(existing);
        repository.flush();
        return true;
    }

    /** A request that day still waiting or given (one that was turned down doesn't count). */
    private boolean hasLiveRequest(Long employeeId, LocalDate date) {
        return repository.findByEmployeeIdAndOffDate(employeeId, date)
                .map(r -> r.getStatus() != DayOffStatus.REJECTED).orElse(false);
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
        return approved.withScheduleUpdate(grant(get(id), true));
    }

    /** Marks the request approved by the system (no manager involved) and applies it like any approval. */
    private DayOffResponse grantAutomatically(DayOffRequest r, String note) {
        r.setStatus(DayOffStatus.APPROVED);
        r.setReviewNote(note);
        r.setReviewedAt(Instant.now());
        String update = grant(r, false);
        return DayOffResponse.from(r).withScheduleUpdate(update);
    }

    /**
     * Applies an approved day off: colleagues' movable reserved days give way, the schedule (if made) takes them off
     * that day, and their own reserved days that week go back to work.
     *
     * @param notify tell the person (a manager's decision); automatic approvals show in the app straight away
     * @return what changed on an existing schedule, or null
     */
    private String grant(DayOffRequest r, boolean notify) {
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
        // Days held for them that week go back to work: the ones the system picked and, when they chose this day
        // themselves (not an emergency), their regular day too.
        String message = r.getReviewNote();
        String back = releasedNote(reservations.trim(e, date, !r.isEmergency()));
        if (back != null) {
            message = message == null ? back : message + " " + back;
        }
        if (notify) {
            notifier.dayOffDecided(r, true, message);
        }
        repository.flush();
        aside.forEach(o -> reservations.refill(o.getEmployee(), o.getOffDate()));
        return update;
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
            reservations.trim(r.getEmployee(), r.getOffDate(), !r.isEmergency());
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
