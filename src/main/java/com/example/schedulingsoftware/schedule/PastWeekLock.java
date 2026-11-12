package com.example.schedulingsoftware.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.InputRules;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Weeks that have passed are locked for managers: they ask an admin for access, and once approved they can change
 * that week for a while (24 hours by default). Admins can always make changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PastWeekLock {

    static final int DEFAULT_HOURS = 24;
    static final int MAX_HOURS = 7 * 24;

    private final PastWeekAccessRepository repository;
    private final AreaService areaService;
    private final EmployeeService employeeService;
    private final AccessPolicy accessPolicy;

    /** Whether the whole week (Monday to Sunday) is over. */
    public static boolean passed(LocalDate anyDayInWeek) {
        return DayOffService.weekStart(anyDayInWeek).plusDays(6).isBefore(LocalDate.now());
    }

    /** Throws unless the current user may change this area's week: admins always; managers only before it's over. */
    @Transactional(readOnly = true)
    public void requireOpen(Long areaId, LocalDate anyDayInWeek) {
        var user = com.example.schedulingsoftware.auth.CurrentUser.find().orElse(null);
        // System work (no one signed in) and admins aren't held back.
        if (user == null || user.isAdmin() || !passed(anyDayInWeek)) {
            return;
        }
        LocalDate monday = DayOffService.weekStart(anyDayInWeek);
        if (openAccess(areaId, monday) == null) {
            throw new BusinessRuleException("The week of " + shortDate(monday) + " has passed, so it's locked. "
                    + "Ask an admin for access to make changes.");
        }
    }

    /** What the schedule screen shows for a week: whether it's locked for you, and your latest request. */
    @Transactional(readOnly = true)
    public WeekStatus status(Long areaId, LocalDate anyDayInWeek) {
        accessPolicy.requireArea(areaId);
        LocalDate monday = DayOffService.weekStart(anyDayInWeek);
        boolean passed = passed(monday);
        if (!passed) {
            return new WeekStatus(false, false, null);
        }
        boolean admin = accessPolicy.current().isAdmin();
        PastWeekAccess latest = repository.findByAreaIdAndWeekStartOrderByRequestedAtDesc(areaId, monday).stream()
                .findFirst().orElse(null);
        boolean locked = !admin && openAccess(areaId, monday) == null;
        return new WeekStatus(true, locked, latest == null ? null : AccessView.from(latest));
    }

    /** A manager asks an admin to open a week that has passed. */
    @Transactional
    public AccessView request(Long areaId, LocalDate anyDayInWeek, String reason) {
        var user = accessPolicy.current();
        accessPolicy.requireManagerOf(areaId);
        if (user.isAdmin()) {
            throw new BusinessRuleException("Admins can change any week; there's nothing to ask for.");
        }
        LocalDate monday = DayOffService.weekStart(anyDayInWeek);
        if (!passed(monday)) {
            throw new BusinessRuleException("The week of " + shortDate(monday) + " isn't over yet, so it's still open.");
        }
        List<PastWeekAccess> earlier = repository.findByAreaIdAndWeekStartOrderByRequestedAtDesc(areaId, monday);
        if (earlier.stream().anyMatch(a -> a.getStatus() == PastWeekAccess.Status.PENDING)) {
            throw new BusinessRuleException("You've already asked for this week. An admin will decide.");
        }
        if (openAccess(areaId, monday) != null) {
            throw new BusinessRuleException("This week is already open for changes.");
        }
        String why = InputRules.clean(reason);
        if (why == null || why.isBlank()) {
            throw new BusinessRuleException("Say what needs changing, so the admin knows why.");
        }
        PastWeekAccess access = new PastWeekAccess();
        access.setArea(areaService.getArea(areaId));
        access.setWeekStart(monday);
        access.setRequestedBy(employeeService.getEmployee(user.id()));
        access.setReason(why.length() > 500 ? why.substring(0, 500) : why);
        access.setRequestedAt(Instant.now());
        repository.save(access);
        log.info("{} asked for access to the week of {} in {}", user.email(), monday, access.getArea().getName());
        return AccessView.from(access);
    }

    /** Admins: requests waiting for a decision, oldest first. */
    @Transactional(readOnly = true)
    public List<AccessView> pending() {
        requireAdmin();
        return repository.findByStatusOrderByRequestedAtAsc(PastWeekAccess.Status.PENDING).stream()
                .map(AccessView::from).toList();
    }

    /** Admins: approve (open the week for "hours", 24 by default) or turn down. */
    @Transactional
    public AccessView decide(Long id, boolean approve, Integer hours, String note) {
        requireAdmin();
        PastWeekAccess access = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Access request", id));
        if (access.getStatus() != PastWeekAccess.Status.PENDING) {
            throw new BusinessRuleException("This request has already been decided.");
        }
        Instant now = Instant.now();
        access.setStatus(approve ? PastWeekAccess.Status.APPROVED : PastWeekAccess.Status.REJECTED);
        access.setDecidedBy(accessPolicy.current().fullName());
        access.setDecidedAt(now);
        access.setNote(InputRules.clean(note));
        if (approve) {
            int h = hours == null ? DEFAULT_HOURS : Math.max(1, Math.min(MAX_HOURS, hours));
            access.setOpenUntil(now.plus(Duration.ofHours(h)));
        }
        log.info("{} {} access to the week of {} in {}", accessPolicy.current().email(),
                approve ? "gave" : "turned down", access.getWeekStart(), access.getArea().getName());
        return AccessView.from(access);
    }

    /** Admins: lock an opened week again before its time runs out. */
    @Transactional
    public AccessView lockAgain(Long id) {
        requireAdmin();
        PastWeekAccess access = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Access request", id));
        if (access.getStatus() == PastWeekAccess.Status.APPROVED) {
            access.setOpenUntil(Instant.now());
        }
        return AccessView.from(access);
    }

    private PastWeekAccess openAccess(Long areaId, LocalDate monday) {
        Instant now = Instant.now();
        return repository.findByAreaIdAndWeekStartOrderByRequestedAtDesc(areaId, monday).stream()
                .filter(a -> a.getStatus() == PastWeekAccess.Status.APPROVED && a.getOpenUntil() != null
                        && a.getOpenUntil().isAfter(now))
                .findFirst().orElse(null);
    }

    private void requireAdmin() {
        if (!accessPolicy.current().isAdmin()) {
            throw new AccessDeniedException("Only an admin can do this.");
        }
    }

    private static String shortDate(LocalDate d) {
        return d.getMonth().getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.getDayOfMonth();
    }

    public record WeekStatus(boolean passed, boolean locked, AccessView request) {
    }

    public record AccessView(Long id, Long areaId, String areaName, LocalDate weekStart, Long requestedById,
            String requestedByName, String reason, PastWeekAccess.Status status, Instant requestedAt,
            String decidedBy, Instant decidedAt, Instant openUntil, boolean open, String note) {

        static AccessView from(PastWeekAccess a) {
            boolean open = a.getStatus() == PastWeekAccess.Status.APPROVED && a.getOpenUntil() != null
                    && a.getOpenUntil().isAfter(Instant.now());
            return new AccessView(a.getId(), a.getArea().getId(), a.getArea().getName(), a.getWeekStart(),
                    a.getRequestedBy().getId(), a.getRequestedBy().getFullName(), a.getReason(), a.getStatus(),
                    a.getRequestedAt(), a.getDecidedBy(), a.getDecidedAt(), a.getOpenUntil(), open, a.getNote());
        }
    }
}
