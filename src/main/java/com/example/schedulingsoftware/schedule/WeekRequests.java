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

    /** Where someone stands for the week. */
    public enum State {
        /** Hasn't sent a request. */
        MISSING,
        /** Sent one, waiting for a decision. */
        PENDING,
        /** Has an approved day off. */
        APPROVED,
        /** Every request this week was turned down. */
        DENIED
    }

    /**
     * @param daysOffNeeded how many days off they normally take a week (7 − work days)
     * @param daysOffAsked  pending or approved days off this week
     */
    public record PersonStatus(Long employeeId, String name, String homeStoreName, State state, List<LocalDate> dates,
            int daysOffNeeded, int daysOffAsked) {

        /** Days off still to be asked for or given. */
        public int stillNeeded() {
            return Math.max(0, daysOffNeeded - daysOffAsked);
        }
    }

    /**
     * @param fewer people who asked, but for fewer days than they normally take off (e.g. 1 of their 2)
     */
    public record Status(LocalDate weekStart, int total, int sent, List<PersonStatus> missing,
            List<PersonStatus> denied, List<PersonStatus> fewer, List<PersonStatus> others) {
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
        List<PersonStatus> fewer = people.stream()
                .filter(p -> p.state() != State.MISSING && p.state() != State.DENIED && p.stillNeeded() > 0).toList();
        List<PersonStatus> others = people.stream()
                .filter(p -> p.state() != State.MISSING && p.state() != State.DENIED && p.stillNeeded() == 0).toList();
        return new Status(week, people.size(), people.size() - missing.size(), missing, denied, fewer, others);
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
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        List<LocalDate> dates = week.datesUntil(week.plusDays(7)).toList();
        List<Employee> staff = staff(areaId);
        List<DayOffRequest> requests = requests(areaId, week);
        List<Store> stores = storeRepository.findByAreaIdOrderByNameAsc(areaId).stream().filter(Store::isActive)
                .toList();

        Map<LocalDate, Integer> needed = new HashMap<>();
        for (LocalDate d : dates) {
            needed.put(d, stores.stream().filter(s -> s.isOpenOn(d)).mapToInt(s -> Math.max(0, s.getMinStaffPerDay()))
                    .sum());
        }
        Map<LocalDate, Integer> off = new HashMap<>();
        Map<Long, Set<LocalDate>> storeOff = new HashMap<>();
        Map<Long, Set<LocalDate>> asked = new HashMap<>();
        for (DayOffRequest r : requests) {
            asked.computeIfAbsent(r.getEmployee().getId(), k -> new HashSet<>()).add(r.getOffDate());
            if (r.getStatus() != DayOffStatus.REJECTED) {
                off.merge(r.getOffDate(), 1, Integer::sum);
                Store home = r.getEmployee().getHomeStore();
                if (home != null) {
                    storeOff.computeIfAbsent(home.getId(), k -> new HashSet<>()).add(r.getOffDate());
                }
            }
        }

        Map<Long, PersonStatus> statuses = people(staff, requests).stream()
                .collect(Collectors.toMap(PersonStatus::employeeId, p -> p));
        List<Suggestion> result = new ArrayList<>();
        // Primaries first (fewest good days for them), then by name.
        List<Employee> ordered = staff.stream()
                .filter(e -> needsDayOff(e))
                .filter(e -> statuses.get(e.getId()).stillNeeded() > 0)
                .sorted(Comparator.comparing((Employee e) -> !e.isPrimaryAtStore()).thenComparing(Employee::getLastName)
                        .thenComparing(Employee::getFirstName))
                .toList();
        for (Employee e : ordered) {
            Long home = e.getHomeStore() == null ? null : e.getHomeStore().getId();
            PersonStatus st = statuses.get(e.getId());
            int need = st.stillNeeded();
            Set<LocalDate> taken = new HashSet<>(asked.getOrDefault(e.getId(), Set.of()));
            for (int k = 1; k <= need; k++) {
                List<LocalDate> options = dates.stream()
                        .filter(d -> !taken.contains(d))
                        .sorted(Comparator
                                // A day nobody else at their store is off first.
                                .comparing((LocalDate d) -> home != null
                                        && storeOff.getOrDefault(home, Set.of()).contains(d))
                                // Then the day with the most people to spare.
                                .thenComparing(d -> -(staff.size() - off.getOrDefault(d, 0) - needed.get(d)))
                                .thenComparing(d -> off.getOrDefault(d, 0))
                                .thenComparing(d -> d))
                        .toList();
                if (options.isEmpty()) {
                    break;
                }
                LocalDate pick = options.getFirst();
                boolean clash = home != null && storeOff.getOrDefault(home, Set.of()).contains(pick);
                taken.add(pick);
                off.merge(pick, 1, Integer::sum);
                if (home != null) {
                    storeOff.computeIfAbsent(home, x -> new HashSet<>()).add(pick);
                }
                int spare = staff.size() - off.get(pick) - needed.get(pick);
                String why = switch (st.state()) {
                    case MISSING -> "No request sent.";
                    case DENIED -> "Their request was turned down.";
                    default -> "Asked for " + st.daysOffAsked() + " of their " + st.daysOffNeeded() + " days off.";
                };
                String usual = st.daysOffNeeded() > 1 ? " Takes " + st.daysOffNeeded() + " days off a week." : "";
                String reason = why + usual + " "
                        + (spare >= 0 ? spare + " more than the stores need that day"
                                : "The stores are short that day even so")
                        + (clash ? "; someone else from their store is also off" : "") + ".";
                // Days left to give, counting from the ones they already have.
                result.add(new Suggestion(e.getId(), e.getFullName(),
                        e.getHomeStore() == null ? null : e.getHomeStore().getName(), st.state(), pick, options,
                        reason, st.daysOffAsked() + k, st.daysOffNeeded()));
            }
        }
        return result;
    }

    private List<PersonStatus> people(Long areaId, LocalDate week) {
        return people(staff(areaId), requests(areaId, week));
    }

    private static List<PersonStatus> people(List<Employee> staff, List<DayOffRequest> requests) {
        Map<Long, List<DayOffRequest>> byPerson = requests.stream()
                .collect(Collectors.groupingBy(r -> r.getEmployee().getId()));
        return staff.stream().filter(WeekRequests::needsDayOff).map(e -> {
            List<DayOffRequest> mine = byPerson.getOrDefault(e.getId(), List.of());
            State state;
            if (mine.isEmpty()) {
                state = State.MISSING;
            } else if (mine.stream().anyMatch(r -> r.getStatus() == DayOffStatus.APPROVED)) {
                state = State.APPROVED;
            } else if (mine.stream().anyMatch(r -> r.getStatus() == DayOffStatus.PENDING)) {
                state = State.PENDING;
            } else {
                state = State.DENIED;
            }
            int asked = (int) mine.stream().filter(r -> r.getStatus() != DayOffStatus.REJECTED).count();
            return new PersonStatus(e.getId(), e.getFullName(),
                    e.getHomeStore() == null ? null : e.getHomeStore().getName(), state,
                    mine.stream().map(DayOffRequest::getOffDate).sorted().toList(),
                    Math.max(0, 7 - e.getWorkDaysPerWeek()), asked);
        }).toList();
    }

    /** Someone who works every day of the week has no day off to ask for. */
    private static boolean needsDayOff(Employee e) {
        return e.getWorkDaysPerWeek() < 7;
    }

    private List<Employee> staff(Long areaId) {
        return employeeRepository.findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId,
                SCHEDULED_ROLES);
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
