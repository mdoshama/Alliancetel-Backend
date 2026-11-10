package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.dayoff.DayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffRequestRepository;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.DayOffStatus;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.store.Store;
import com.example.schedulingsoftware.store.StoreRepository;

import lombok.RequiredArgsConstructor;

/**
 * Who has sent their day-off request for a week, and suggested days off for people who haven't (or whose request
 * was turned down). Everyone who works fewer than 7 days must have a request in before the week is generated.
 */
@Service
@RequiredArgsConstructor
public class WeekRequests {

    private static final Set<Role> SCHEDULED_ROLES = EnumSet.of(Role.MANAGER, Role.EMPLOYEE);

    private final EmployeeRepository employeeRepository;
    private final DayOffRequestRepository dayOffRepository;
    private final StoreRepository storeRepository;
    private final AccessPolicy accessPolicy;
    private final com.example.schedulingsoftware.dayoff.RequestPortal portal;
    private final FullWeekRepository fullWeeks;
    private final WeeklyScheduleRepository scheduleRepository;

    /** Where someone stands for the week. */
    public enum State {
        /** Hasn't sent a request. */
        MISSING,
        /** Sent one, waiting for a decision. */
        PENDING,
        /** Has an approved day off. */
        APPROVED,
        /** Every request this week was turned down. */
        DENIED,
        /** Hasn't asked for anything; has the days the system reserved. */
        RESERVED,
        /** Works all 7 days this week (a manager or admin decided): no day off needed. */
        FULL_WEEK,
        /** Hasn't asked for anything else; gets their regular day(s) off. */
        REGULAR
    }

    /**
     * @param daysOffNeeded how many days off they normally take a week (7 − work days)
     * @param daysOffAsked  pending or approved days off this week
     * @param phone         their phone number (managers and admins call people who haven't picked), or null
     */
    public record PersonStatus(Long employeeId, String name, String homeStoreName, State state, List<LocalDate> dates,
            int daysOffNeeded, int daysOffAsked, String phone) {

        /** Days off still to be asked for or given. */
        public int stillNeeded() {
            return Math.max(0, daysOffNeeded - daysOffAsked);
        }
    }

    /**
     * @param fewer people who asked, but for fewer days than they normally take off (e.g. 1 of their 2)
     */
    public record Status(LocalDate weekStart, int total, int sent, List<PersonStatus> missing,
            List<PersonStatus> denied, List<PersonStatus> fewer, List<PersonStatus> reserved,
            List<PersonStatus> others, boolean requestsOpen, Capacity capacity, List<PersonStatus> fullWeek,
            List<PersonStatus> regular) {
    }

    /**
     * The week at a glance: shifts the stores need (sum of each open store's minimum staffing, every day) against
     * the shifts the team can work (each person's work days a week), and each day as it stands: from the schedule
     * once the week is made, otherwise with the days off asked for so far (pending, reserved and approved).
     */
    public record Capacity(int shiftsNeeded, int shiftsAvailable, int daysOffToGive, List<DayCapacity> days) {

        public int spare() {
            return shiftsAvailable - shiftsNeeded;
        }
    }

    /** One day: people working with the days off so far, and how many shifts the stores still can't fill. */
    public record DayCapacity(LocalDate date, int needed, int working, int off, int shortBy,
            List<String> shortStores) {
    }

    /**
     * A suggested day off; {@code options} lists every day of the week from most to least convenient. Someone who
     * takes two days off a week gets two suggestions ({@code dayNumber} 1 and 2 of {@code daysNeeded}).
     */
    public record Suggestion(Long employeeId, String name, String homeStoreName, State state, LocalDate date,
            List<LocalDate> options, String reason, int dayNumber, int daysNeeded) {
    }

    @Transactional(readOnly = true)
    public Status status(Long areaId, LocalDate anyDayInWeek) {
        accessPolicy.requireManagerOf(areaId);
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        List<PersonStatus> people = people(areaId, week);
        List<PersonStatus> missing = people.stream().filter(p -> p.state() == State.MISSING).toList();
        List<PersonStatus> denied = people.stream().filter(p -> p.state() == State.DENIED).toList();
        List<PersonStatus> reserved = people.stream().filter(p -> p.state() == State.RESERVED).toList();
        List<PersonStatus> fewer = people.stream()
                .filter(p -> p.state() == State.PENDING || p.state() == State.APPROVED)
                .filter(p -> p.stillNeeded() > 0).toList();
        List<PersonStatus> others = people.stream()
                .filter(p -> p.state() == State.PENDING || p.state() == State.APPROVED)
                .filter(p -> p.stillNeeded() == 0).toList();
        List<PersonStatus> full = people.stream().filter(p -> p.state() == State.FULL_WEEK).toList();
        List<PersonStatus> regular = people.stream().filter(p -> p.state() == State.REGULAR).toList();
        return new Status(week, people.size(), people.size() - missing.size() - reserved.size(), missing, denied,
                fewer, reserved, others, portal.isOpenFor(week), capacity(areaId, week), full, regular);
    }

    /** See {@link Capacity}. */
    @Transactional(readOnly = true)
    public Capacity capacity(Long areaId, LocalDate anyDayInWeek) {
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        Coverage coverage = coverage(areaId);
        // Once the week is made, the schedule is the truth (days given, edits, swaps); before that, the requests.
        Map<LocalDate, Set<Long>> off = scheduleRepository.findByAreaIdAndWeekStart(areaId, week)
                .map(WeekRequests::offInSchedule)
                .orElseGet(() -> offByDate(requests(areaId, week)));
        Set<Long> full = fullWeekIds(areaId, week);
        if (scheduleRepository.findByAreaIdAndWeekStart(areaId, week).isEmpty()) {
            // Regular days off not in the requests yet still count: they're given when the week is generated.
            Map<Long, List<DayOffRequest>> byPerson = requests(areaId, week).stream()
                    .collect(Collectors.groupingBy(r -> r.getEmployee().getId()));
            for (Employee e : coverage.staff()) {
                if (!full.contains(e.getId())) {
                    for (LocalDate d : plannedRegular(e, week, byPerson.getOrDefault(e.getId(), List.of()))) {
                        off.computeIfAbsent(d, k -> new HashSet<>()).add(e.getId());
                    }
                }
            }
        }
        int needed = 0;
        int available = 0;
        int toGive = 0;
        for (Employee e : coverage.staff()) {
            int days = full.contains(e.getId()) ? 7 : e.getWorkDaysPerWeek();
            available += Math.min(7, Math.max(0, days));
            toGive += Math.max(0, 7 - days);
        }
        List<DayCapacity> days = new ArrayList<>();
        for (LocalDate d : week.datesUntil(week.plusDays(7)).toList()) {
            Set<Long> o = off.getOrDefault(d, Set.of());
            Coverage.Day day = coverage.day(d, o);
            needed += day.needed();
            days.add(new DayCapacity(d, day.needed(), day.working(), o.size(), day.shortBy(), day.shortStores()));
        }
        return new Capacity(needed, available, toGive, days);
    }

    /** Coverage for the area's stores and staff (see {@link Coverage}). */
    @Transactional(readOnly = true)
    public Coverage coverage(Long areaId) {
        return Coverage.of(storeRepository.findByAreaIdOrderByNameAsc(areaId), staff(areaId));
    }

    /** People working all 7 days in that week (see {@link FullWeek}). */
    @Transactional(readOnly = true)
    public Set<Long> fullWeekIds(Long areaId, LocalDate weekStart) {
        return fullWeeks.findForArea(areaId, DayOffService.weekStart(weekStart)).stream()
                .map(f -> f.getEmployee().getId()).collect(Collectors.toSet());
    }

    /** Who isn't working each day on a schedule that was already made. */
    private static Map<LocalDate, Set<Long>> offInSchedule(WeeklySchedule schedule) {
        Map<LocalDate, Set<Long>> off = new HashMap<>();
        for (ScheduleEntry e : schedule.getEntries()) {
            if (e.getType() != DayType.WORK) {
                off.computeIfAbsent(e.getWorkDate(), k -> new HashSet<>()).add(e.getEmployee().getId());
            }
        }
        return off;
    }

    private static Map<LocalDate, Set<Long>> offByDate(List<DayOffRequest> requests) {
        Map<LocalDate, Set<Long>> off = new HashMap<>();
        for (DayOffRequest r : requests) {
            if (r.getStatus() != DayOffStatus.REJECTED) {
                off.computeIfAbsent(r.getOffDate(), k -> new HashSet<>()).add(r.getEmployee().getId());
            }
        }
        return off;
    }

    /** Names of people who haven't sent a request for the week (used to hold back generating it). */
    @Transactional(readOnly = true)
    public List<String> missingNames(Long areaId, LocalDate weekStart) {
        return people(areaId, weekStart).stream().filter(p -> p.state() == State.MISSING)
                .map(PersonStatus::name).toList();
    }

    /**
     * Days off for everyone short of their usual number this week (no request, turned down, or asked for fewer days
     * than they normally take), each on the day the area can best spare them:
     * most people working above what the open stores need, avoiding a day someone from their store is already off,
     * and spreading suggestions across the week.
     */
    @Transactional(readOnly = true)
    public List<Suggestion> suggest(Long areaId, LocalDate anyDayInWeek) {
        accessPolicy.requireManagerOf(areaId);
        return suggestFor(areaId, anyDayInWeek);
    }

    public Employee employee(Long id) {
        return employeeRepository.findById(id).orElseThrow();
    }

    /** Same as {@link #suggest} without the access check (used to reserve days off). */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestFor(Long areaId, LocalDate anyDayInWeek) {
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        List<LocalDate> dates = week.datesUntil(week.plusDays(7)).toList();
        List<Employee> staff = staff(areaId);
        List<DayOffRequest> requests = requests(areaId, week);
        Coverage coverage = Coverage.of(storeRepository.findByAreaIdOrderByNameAsc(areaId), staff);

        Map<LocalDate, Set<Long>> off = offByDate(requests);
        Map<Long, Set<LocalDate>> storeOff = new HashMap<>();
        Map<Long, Set<LocalDate>> asked = new HashMap<>();
        for (DayOffRequest r : requests) {
            asked.computeIfAbsent(r.getEmployee().getId(), k -> new HashSet<>()).add(r.getOffDate());
            if (r.getStatus() != DayOffStatus.REJECTED) {
                Store home = r.getEmployee().getHomeStore();
                if (home != null) {
                    storeOff.computeIfAbsent(home.getId(), k -> new HashSet<>()).add(r.getOffDate());
                }
            }
        }

        // Regular days not in the requests yet: they'll be given, so plan around them.
        Set<Long> fullIds = fullWeekIds(areaId, week);
        Map<Long, List<DayOffRequest>> byPerson = requests.stream()
                .collect(Collectors.groupingBy(r -> r.getEmployee().getId()));
        for (Employee e : staff) {
            if (fullIds.contains(e.getId())) {
                continue;
            }
            for (LocalDate d : plannedRegular(e, week, byPerson.getOrDefault(e.getId(), List.of()))) {
                asked.computeIfAbsent(e.getId(), k -> new HashSet<>()).add(d);
                off.computeIfAbsent(d, k -> new HashSet<>()).add(e.getId());
                if (e.getHomeStore() != null) {
                    storeOff.computeIfAbsent(e.getHomeStore().getId(), k -> new HashSet<>()).add(d);
                }
            }
        }
        Map<Long, PersonStatus> statuses = people(staff, requests, fullIds, week).stream()
                .collect(Collectors.toMap(PersonStatus::employeeId, p -> p));
        // Most constrained first: the people with the fewest days the stores can spare them (a primary tied to one
        // store, a floater with only a couple of stores), so everyone still gets a day that works.
        Map<Long, Long> freeDays = new HashMap<>();
        for (Employee e : staff) {
            freeDays.put(e.getId(), dates.stream()
                    .filter(d -> !asked.getOrDefault(e.getId(), Set.of()).contains(d))
                    .filter(d -> coverage.canBeOff(d, off.getOrDefault(d, Set.of()), e.getId())).count());
        }
        List<Employee> ordered = staff.stream()
                .filter(WeekRequests::needsDayOff)
                .filter(e -> statuses.get(e.getId()).stillNeeded() > 0)
                .sorted(Comparator.comparing((Employee e) -> freeDays.get(e.getId()))
                        .thenComparing(e -> !e.isPrimaryAtStore())
                        .thenComparing(Employee::getLastName)
                        .thenComparing(Employee::getFirstName))
                .toList();
        List<Suggestion> result = new ArrayList<>();
        for (Employee e : ordered) {
            Long home = e.getHomeStore() == null ? null : e.getHomeStore().getId();
            PersonStatus st = statuses.get(e.getId());
            int need = st.stillNeeded();
            Set<LocalDate> taken = new HashSet<>(asked.getOrDefault(e.getId(), Set.of()));
            for (int k = 1; k <= need; k++) {
                Map<LocalDate, Coverage.Day> without = new HashMap<>();
                Map<LocalDate, Boolean> fits = new HashMap<>();
                for (LocalDate d : dates) {
                    if (taken.contains(d)) {
                        continue;
                    }
                    Set<Long> o = off.getOrDefault(d, Set.of());
                    Set<Long> plus = new HashSet<>(o);
                    plus.add(e.getId());
                    Coverage.Day after = coverage.day(d, plus);
                    without.put(d, after);
                    fits.put(d, after.shortBy() <= coverage.day(d, o).shortBy());
                }
                List<LocalDate> options = dates.stream()
                        .filter(d -> !taken.contains(d))
                        .sorted(Comparator
                                // Days the stores can still be covered without them first.
                                .comparing((LocalDate d) -> !fits.get(d))
                                // Then a day nobody else at their store is off.
                                .thenComparing(d -> home != null && storeOff.getOrDefault(home, Set.of()).contains(d))
                                // Then the day with the most people to spare once they're off.
                                .thenComparing(d -> -without.get(d).spare())
                                .thenComparing(d -> off.getOrDefault(d, Set.of()).size())
                                .thenComparing(d -> d))
                        .toList();
                if (options.isEmpty()) {
                    break;
                }
                LocalDate pick = options.getFirst();
                boolean clash = home != null && storeOff.getOrDefault(home, Set.of()).contains(pick);
                boolean ok = fits.get(pick);
                taken.add(pick);
                off.computeIfAbsent(pick, x -> new HashSet<>()).add(e.getId());
                if (home != null) {
                    storeOff.computeIfAbsent(home, x -> new HashSet<>()).add(pick);
                }
                Coverage.Day after = without.get(pick);
                String why = switch (st.state()) {
                    case MISSING -> "No request sent.";
                    case DENIED -> "Their request was turned down.";
                    case RESERVED -> "Reserved, not picked yet.";
                    default -> "Asked for " + st.daysOffAsked() + " of their " + st.daysOffNeeded() + " days off.";
                };
                String usual = st.daysOffNeeded() > 1 ? " Takes " + st.daysOffNeeded() + " days off a week." : "";
                String reason = why + usual + " "
                        + (ok ? "Stores still covered that day (" + Math.max(0, after.spare()) + " to spare)"
                                : "The stores are short that day even so ("
                                        + String.join(", ", after.shortStores()) + ")")
                        + (clash ? "; someone else from their store is also off" : "") + ".";
                result.add(new Suggestion(e.getId(), e.getFullName(),
                        e.getHomeStore() == null ? null : e.getHomeStore().getName(), st.state(), pick, options,
                        reason, st.daysOffAsked() + k, st.daysOffNeeded()));
            }
        }
        return result;
    }

    private List<PersonStatus> people(Long areaId, LocalDate week) {
        return people(staff(areaId), requests(areaId, week), fullWeekIds(areaId, week), week);
    }

    private static List<PersonStatus> people(List<Employee> staff, List<DayOffRequest> requests, Set<Long> fullWeek,
            LocalDate week) {
        Map<Long, List<DayOffRequest>> byPerson = requests.stream()
                .collect(Collectors.groupingBy(r -> r.getEmployee().getId()));
        return staff.stream().filter(WeekRequests::needsDayOff).map(e -> {
            List<DayOffRequest> all = byPerson.getOrDefault(e.getId(), List.of());
            // A regular day off counts as their own choice (a manager set it with them); only days the system
            // picked mean they haven't chosen yet.
            List<DayOffRequest> mine = all.stream().filter(r -> !r.isReserved() || r.isRegular()).toList();
            boolean hasReserved = all.stream().anyMatch(r -> r.isReserved() && !r.isRegular());
            State state;
            if (fullWeek.contains(e.getId())) {
                int asked = (int) all.stream().filter(r -> r.getStatus() != DayOffStatus.REJECTED).count();
                return new PersonStatus(e.getId(), e.getFullName(),
                        e.getHomeStore() == null ? null : e.getHomeStore().getName(), State.FULL_WEEK,
                        all.stream().filter(r -> r.getStatus() != DayOffStatus.REJECTED)
                                .map(DayOffRequest::getOffDate).sorted().toList(), 0, asked, e.getPhone());
            }
            // Regular days that aren't in the requests yet (the week hasn't been opened): they're given at generate.
            List<LocalDate> planned = plannedRegular(e, week, all);
            if (mine.isEmpty() && !planned.isEmpty()) {
                state = State.REGULAR;
            } else if (mine.isEmpty()) {
                state = hasReserved ? State.RESERVED : State.MISSING;
            } else if (mine.stream().allMatch(DayOffRequest::isRegular)
                    && mine.stream().anyMatch(r -> r.getStatus() != DayOffStatus.REJECTED)) {
                state = State.REGULAR;
            } else if (mine.stream().anyMatch(r -> r.getStatus() == DayOffStatus.APPROVED)) {
                state = State.APPROVED;
            } else if (mine.stream().anyMatch(r -> r.getStatus() == DayOffStatus.PENDING)) {
                state = State.PENDING;
            } else {
                state = State.DENIED;
            }
            List<LocalDate> dates = new ArrayList<>(all.stream().filter(r -> r.getStatus() != DayOffStatus.REJECTED)
                    .map(DayOffRequest::getOffDate).toList());
            dates.addAll(planned);
            dates.sort(null);
            // An emergency still waiting isn't a day off yet: until a manager gives it, they keep a day of their own
            // (given back if the emergency is approved).
            int waitingEmergencies = (int) all.stream()
                    .filter(r -> r.isEmergency() && r.getStatus() == DayOffStatus.PENDING).count();
            return new PersonStatus(e.getId(), e.getFullName(),
                    e.getHomeStore() == null ? null : e.getHomeStore().getName(), state, dates,
                    Math.max(0, 7 - e.getWorkDaysPerWeek()), dates.size() - waitingEmergencies, e.getPhone());
        }).toList();
    }

    /**
     * Someone's regular days off in the week that have no request yet and will be given when the week is generated:
     * up to their usual number of days off, less the days they asked for themselves (those replace the regular day;
     * an emergency doesn't) and days already held for them. A day with any request, even one turned down, is skipped.
     */
    static List<LocalDate> plannedRegular(Employee e, LocalDate week, List<DayOffRequest> theirs) {
        if (e.getRegularDaysOff() == null || e.getRegularDaysOff().isEmpty()) {
            return List.of();
        }
        long taken = theirs.stream().filter(r -> r.getStatus() != DayOffStatus.REJECTED && !r.isEmergency()).count();
        long room = Math.max(0, 7 - e.getWorkDaysPerWeek()) - taken;
        if (room <= 0) {
            return List.of();
        }
        Set<LocalDate> asked = theirs.stream().map(DayOffRequest::getOffDate).collect(Collectors.toSet());
        return week.datesUntil(week.plusDays(7))
                .filter(d -> e.getRegularDaysOff().contains(d.getDayOfWeek()) && !asked.contains(d))
                .limit(room).toList();
    }

    /** Someone who works every day of the week has no day off to ask for. */
    private static boolean needsDayOff(Employee e) {
        return e.getWorkDaysPerWeek() < 7;
    }

    /** Active people in the area who work shifts (the ones the schedule is made for). */
    public List<Employee> staffOf(Long areaId) {
        return staff(areaId);
    }

    private List<Employee> staff(Long areaId) {
        return employeeRepository.findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId,
                SCHEDULED_ROLES).stream().filter(Employee::worksShifts).toList();
    }

    private List<DayOffRequest> requests(Long areaId, LocalDate week) {
        List<DayOffRequest> all = new ArrayList<>();
        for (DayOffStatus s : DayOffStatus.values()) {
            all.addAll(dayOffRepository.findForArea(areaId, s, week, week.plusDays(6)));
        }
        all.removeIf(r -> !Objects.equals(r.getEmployee().getArea() == null ? null : r.getEmployee().getArea().getId(),
                areaId));
        return all;
    }
}
