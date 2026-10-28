package com.example.schedulingsoftware.config;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.Area;
import com.example.schedulingsoftware.area.AreaRepository;
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
import lombok.extern.slf4j.Slf4j;

/**
 * Development helper. Fills an empty database with sample areas, staff and day-off requests, and gives every
 * sample account the password {@value #DEMO_PASSWORD} so each role can be tried.
 * Turn off with {@code alliancetel.demo-data.enabled=false} before real use.
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "alliancetel.demo-data.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_PASSWORD = "Welcome123";
    static final String DEMO_DOMAIN = "@alliancetel.example";

    private static final List<String> FIRST_NAMES = List.of("Maria", "James", "Priya", "Daniel", "Sofia", "Kevin",
            "Aisha", "Luis", "Emily", "Ryan", "Grace", "Omar", "Hannah", "Carlos", "Nina", "Ethan", "Mei", "Jordan",
            "Fatima", "Tyler", "Rosa", "Arjun", "Chloe", "Marcus");
    private static final List<String> LAST_NAMES = List.of("Garcia", "Nguyen", "Patel", "Kim", "Lopez", "Chen",
            "Khan", "Rivera", "Johnson", "Shah", "Martinez", "Lee", "Brown", "Singh", "Torres", "Wong", "Ali",
            "Davis", "Hernandez", "Clark", "Ramos", "Gupta", "Wilson", "Reyes");

    /** Sample stores for the sample areas: name, short code, minimum staff per day. */
    private static final Map<String, List<String[]>> DEMO_STORES = Map.of(
            "San Diego", List.of(new String[] {"Oceanside", "OCN", "2"}, new String[] {"Chula Vista", "CHV", "2"},
                    new String[] {"Mission Valley", "MSV", "1"}),
            "Los Angeles", List.of(new String[] {"Hollywood", "HWD", "2"}, new String[] {"Long Beach", "LGB", "2"},
                    new String[] {"Pasadena", "PAS", "1"}),
            "San Francisco", List.of(new String[] {"Mission District", "MSN", "2"}, new String[] {"Daly City", "DLY", "1"},
                    new String[] {"Oakland", "OAK", "2"}));

    private final AreaRepository areaRepository;
    private final StoreRepository storeRepository;
    private final EmployeeRepository employeeRepository;
    private final DayOffRequestRepository dayOffRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String hash = passwordEncoder.encode(DEMO_PASSWORD);
        if (areaRepository.count() == 0) {
            seed(hash);
        }
        enableDemoLogins(hash);
        addDemoStores();
    }

    /**
     * Gives each sample area its sample stores (if it has none yet) and spreads that area's staff across them.
     * Also runs on databases seeded before stores existed.
     */
    private void addDemoStores() {
        for (Area area : areaRepository.findAll()) {
            List<String[]> names = DEMO_STORES.get(area.getName());
            if (names == null || !storeRepository.findByAreaIdOrderByNameAsc(area.getId()).isEmpty()) {
                continue;
            }
            List<Store> stores = storeRepository.saveAll(names.stream()
                    .map(n -> {
                        Store s = new Store(area, n[0], n[1], null);
                        s.setMinStaffPerDay(Integer.parseInt(n[2]));
                        return s;
                    })
                    .toList());
            List<Employee> staff = employeeRepository.findAll().stream()
                    .filter(e -> e.getArea() != null && e.getArea().getId().equals(area.getId()))
                    .filter(e -> e.getHomeStore() == null)
                    .sorted(Comparator.comparing(Employee::getId))
                    .toList();
            for (int i = 0; i < staff.size(); i++) {
                staff.get(i).setHomeStore(stores.get(i % stores.size()));
                // The first person at each store is its primary; the rest are secondaries.
                staff.get(i).setPrimaryAtStore(i < stores.size());
            }
            log.info("Added {} demo stores to {} and assigned {} staff", stores.size(), area.getName(), staff.size());
        }
    }

    private void seed(String hash) {
        log.info("Empty database: seeding AllianceTel demo data");
        employeeRepository.save(employee("AT-0001", "Alex", "Morgan", "admin" + DEMO_DOMAIN, Role.ADMIN, null, hash));

        List<Area> areas = areaRepository.saveAll(List.of(
                new Area("San Diego", "San Diego county stores"),
                new Area("Los Angeles", "Greater Los Angeles stores"),
                new Area("San Francisco", "Bay Area stores")));

        LocalDate nextMonday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);
        int code = 1001;
        int nameIndex = 0;
        for (Area area : areas) {
            String areaSlug = area.getName().toLowerCase(Locale.ROOT).replace(" ", "");
            for (int i = 0; i < 8; i++) {
                Role role = i == 0 ? Role.MANAGER : Role.EMPLOYEE;
                String first = FIRST_NAMES.get(nameIndex % FIRST_NAMES.size());
                String last = LAST_NAMES.get(nameIndex % LAST_NAMES.size());
                nameIndex++;
                String email = role == Role.MANAGER
                        ? "manager." + areaSlug + DEMO_DOMAIN
                        : (first + "." + last).toLowerCase(Locale.ROOT) + DEMO_DOMAIN;
                Employee e = employeeRepository.save(employee("AT-" + code++, first, last, email, role, area, hash));

                // A few sample requests: one approved single day, one pending two-day request.
                if (i == 2) {
                    dayOffRepository.save(request(e, nextMonday.plusDays(2), DayOffStatus.APPROVED, "Family event"));
                }
                if (i == 4) {
                    dayOffRepository.save(request(e, nextMonday.plusDays(4), DayOffStatus.PENDING, "Travel"));
                    dayOffRepository.save(request(e, nextMonday.plusDays(5), DayOffStatus.PENDING, "Travel"));
                }
            }
        }
    }

    /** Sample accounts created before logins existed get the demo password too. */
    private void enableDemoLogins(String hash) {
        List<Employee> demo = employeeRepository.findAll().stream()
                .filter(e -> e.getEmail().endsWith(DEMO_DOMAIN))
                .toList();
        demo.stream().filter(e -> e.getPasswordHash() == null).forEach(e -> e.setPasswordHash(hash));
        demo.stream()
                .filter(e -> e.getRole() != Role.EMPLOYEE && e.isActive())
                .forEach(e -> log.info("Demo login [{}]: {} / {}", e.getRole(), e.getEmail(), DEMO_PASSWORD));
    }

    private Employee employee(String code, String first, String last, String email, Role role, Area area,
            String hash) {
        Employee e = new Employee();
        e.setEmployeeCode(code);
        e.setFirstName(first);
        e.setLastName(last);
        e.setEmail(email);
        e.setRole(role);
        e.setArea(area);
        e.setPasswordHash(hash);
        return e;
    }

    private DayOffRequest request(Employee e, LocalDate date, DayOffStatus status, String reason) {
        DayOffRequest r = new DayOffRequest();
        r.setEmployee(e);
        r.setOffDate(date);
        r.setStatus(status);
        r.setReason(reason);
        return r;
    }
}
