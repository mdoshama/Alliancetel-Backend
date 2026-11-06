package com.example.schedulingsoftware.employee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.notify.BirthdayReminders;
import com.example.schedulingsoftware.notify.Notification;
import com.example.schedulingsoftware.notify.NotificationKind;
import com.example.schedulingsoftware.notify.NotificationRepository;
import com.example.schedulingsoftware.support.TestUsers;

/** Birth dates on employees, the upcoming-birthdays list, and the reminder emails to admins and managers. */
@SpringBootTest
@Transactional
class BirthdayIntegrationTest {

    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    BirthdayService birthdayService;
    @Autowired
    BirthdayReminders reminders;
    @Autowired
    NotificationRepository notifications;

    LocalDate today;
    Long north;
    Long south;
    Long admin;
    Long northManager;
    Long southManager;
    Long ana;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        today = birthdayService.today();
        north = areaService.create(new AreaRequest("Bday North", null, true)).id();
        south = areaService.create(new AreaRequest("Bday South", null, true)).id();
        admin = create("BD-A", "Ada", Role.ADMIN, null, null);
        northManager = create("BD-M1", "Max", Role.MANAGER, north, null);
        southManager = create("BD-M2", "Sue", Role.MANAGER, south, null);
        // Ana (North) has a birthday a week from today.
        ana = create("BD-1", "Ana", Role.EMPLOYEE, north, today.plusDays(7).minusYears(25));
        TestUsers.signInAsAdmin();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long create(String code, String first, Role role, Long area, LocalDate birth) {
        return employeeService.create(new EmployeeRequest(code, first, "Bday", code.toLowerCase() + "@test.example",
                null, role, area, null, 6, true, null, null, null, birth)).employee().id();
    }

    private List<Notification> remindersTo(Long id) {
        return notifications.findAll().stream()
                .filter(n -> n.getKind() == NotificationKind.BIRTHDAY_REMINDER && n.getEmployee().getId().equals(id))
                .toList();
    }

    @Test
    void birthDateIsSavedAndChecked() {
        assertEquals(today.plusDays(7).minusYears(25), employeeService.findById(ana).birthDate());
        assertThrows(BusinessRuleException.class, () -> create("BD-9", "Kid", Role.EMPLOYEE, north, today.minusYears(5)));
        assertThrows(BusinessRuleException.class,
                () -> create("BD-8", "Old", Role.EMPLOYEE, north, today.minusYears(120)));
        // Optional: no date is fine.
        assertNull(employeeService.findById(admin).birthDate());
    }

    @Test
    void upcomingListIsLimitedToTheManagersArea() {
        create("BD-2", "Bo", Role.EMPLOYEE, south, today.plusDays(3).minusYears(30));
        assertTrue(birthdayService.upcoming(null, 30).stream().map(b -> b.fullName()).toList()
                .containsAll(List.of("Ana Bday", "Bo Bday")));

        TestUsers.signInAs(northManager, Role.MANAGER, north);
        List<BirthdayService.UpcomingBirthday> mine = birthdayService.upcoming(null, 30);
        assertEquals(List.of("Ana Bday"), mine.stream().map(b -> b.fullName()).toList());
        assertEquals(7, mine.getFirst().daysUntil());
        assertEquals(25, mine.getFirst().turning());
    }

    @Test
    void remindersGoToAdminsAndTheAreaManagerOncePerDay() {
        reminders.sendDue(today);

        assertEquals(1, remindersTo(admin).size());
        assertEquals(1, remindersTo(northManager).size());
        // Another area's manager and the birthday person don't hear about it.
        assertEquals(0, remindersTo(southManager).size());
        assertEquals(0, remindersTo(ana).size());
        Notification n = remindersTo(northManager).getFirst();
        assertTrue(n.getSubject().contains("Ana Bday"), n.getSubject());
        assertTrue(n.getBody().contains("In 7 days"), n.getBody());

        // The hourly check runs again: no repeats the same day.
        reminders.sendDue(today);
        assertEquals(1, remindersTo(northManager).size());
    }

    @Test
    void noReminderOnDaysInBetween() {
        reminders.sendDue(today.plusDays(2));
        assertEquals(0, remindersTo(admin).size());
    }

    @Test
    void leapDayBirthdaysFallOnFeb28() {
        LocalDate leap = LocalDate.of(2000, 2, 29);
        assertEquals(LocalDate.of(2027, 2, 28), BirthdayService.nextBirthday(leap, LocalDate.of(2027, 1, 10)));
        assertEquals(LocalDate.of(2028, 2, 29), BirthdayService.nextBirthday(leap, LocalDate.of(2027, 3, 1)));
        assertEquals(LocalDate.of(2026, 11, 5), BirthdayService.nextBirthday(LocalDate.of(1990, 11, 5),
                LocalDate.of(2026, 11, 5)));
    }
}
