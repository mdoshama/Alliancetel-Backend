package com.example.schedulingsoftware.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

import jakarta.persistence.EntityManager;

/** Admins clearing old messages from the Messages page. */
@SpringBootTest
@Transactional
class MessageCleanupTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    NotificationService notificationService;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    EntityManager em;

    Long area;
    Long manager;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Cleanup Area", null, true)).id();
        Long store = storeService.create(new StoreRequest(area, "Pier", "PIR", null, 1, true, null, null)).id();
        manager = employeeService.create(new EmployeeRequest("C-1", "Cam", "Lead", "cam.lead@test.example", null,
                Role.MANAGER, area, store, 5, true, null)).employee().id();
        // An announcement from the admin queues one email to Cam.
        notificationService.announce(area, "Inventory count Friday");
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private List<Notification> mine() {
        return notifications.findAll().stream().filter(n -> area.equals(n.getAreaId())).toList();
    }

    private void age(Notification n, int days, NotificationStatus status) {
        em.createNativeQuery("update notifications set created_at = ?, status = ? where id = ?")
                .setParameter(1, Instant.now().minus(days, ChronoUnit.DAYS))
                .setParameter(2, status.name())
                .setParameter(3, n.getId())
                .executeUpdate();
        em.clear();
    }

    @Test
    void adminsClearOldMessagesButNeverOnesStillSending() {
        notificationService.announce(area, "Second note");
        notificationService.announce(area, "Third note");
        List<Notification> all = mine();
        assertEquals(3, all.size());
        age(all.get(0), 40, NotificationStatus.SENT);
        age(all.get(1), 40, NotificationStatus.QUEUED);
        age(all.get(2), 2, NotificationStatus.FAILED);

        assertEquals(1, notificationService.deleteOlderThan(30, area), "only the old one that was already sent");
        assertEquals(2, mine().size());
        assertTrue(mine().stream().anyMatch(n -> n.getStatus() == NotificationStatus.QUEUED), "still sending: kept");
        assertThrows(BusinessRuleException.class, () -> notificationService.deleteOlderThan(0, area));
    }

    @Test
    void adminsDeleteOneMessageButNotWhileItsSending() {
        Notification n = mine().getFirst();
        assertThrows(BusinessRuleException.class, () -> notificationService.delete(n.getId()), "still queued");
        age(n, 1, NotificationStatus.SENT);
        notificationService.delete(n.getId());
        assertTrue(mine().isEmpty());
    }

    @Test
    void managersCannotDeleteMessages() {
        Notification n = mine().getFirst();
        TestUsers.signInAs(manager, Role.MANAGER, area);
        assertThrows(AccessDeniedException.class, () -> notificationService.delete(n.getId()));
        assertThrows(AccessDeniedException.class, () -> notificationService.deleteOlderThan(30, area));
    }
}
