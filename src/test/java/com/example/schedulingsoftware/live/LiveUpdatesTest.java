package com.example.schedulingsoftware.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.CurrentUser;
import com.example.schedulingsoftware.auth.JwtService;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.support.TestUsers;

/** Changes made on one screen are pushed to the other open screens. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LiveUpdatesTest {

    @Autowired
    MockMvc mvc;
    @MockitoSpyBean
    LiveUpdates liveUpdates;
    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    JwtService jwtService;

    Long area;
    String admin;
    String manager;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Live Area", null, true)).id();
        admin = token("LV-1", Role.ADMIN);
        manager = token("LV-2", Role.MANAGER);
        TestUsers.signOut();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    @Test
    void signedInScreensCanOpenTheStream() throws Exception {
        mvc.perform(get("/api/live").param("client", "tab-12345678").header("Authorization", "Bearer " + manager)
                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        mvc.perform(get("/api/live").accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isUnauthorized());
    }

    @Test
    void aChangeThatWentThroughIsPublishedWithTheTabThatMadeIt() throws Exception {
        mvc.perform(post("/api/areas").header("Authorization", "Bearer " + admin)
                .header(LiveChangeInterceptor.CLIENT_HEADER, "tab-abcdef12")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pushed Area\",\"active\":true}"))
                .andExpect(status().is2xxSuccessful());
        verify(liveUpdates).publish(eq("people"), any(CurrentUser.class), eq("tab-abcdef12"));
    }

    @Test
    void refusedChangesAndReadsAreNotPublished() throws Exception {
        mvc.perform(post("/api/areas").header("Authorization", "Bearer " + manager)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nope\",\"active\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/areas").header("Authorization", "Bearer " + manager)).andExpect(status().isOk());
        verify(liveUpdates, never()).publish(anyString(), any(), any());
    }

    @Test
    void pathsMapToWhatChanged() {
        assertEquals("schedule", LiveChangeInterceptor.topicOf("/api/schedules/4/swap"));
        assertEquals("dayoff", LiveChangeInterceptor.topicOf("/api/day-off-requests/approve-many"));
        assertEquals("people", LiveChangeInterceptor.topicOf("/api/employees/9/home-store"));
        assertEquals("timeclock", LiveChangeInterceptor.topicOf("/api/time-clock/in"));
        assertNull(LiveChangeInterceptor.topicOf("/api/auth/login"));
        assertTrue(LiveChangeInterceptor.changes("PATCH"));
        assertFalse(LiveChangeInterceptor.changes("GET"));
        assertNull(LiveChangeInterceptor.cleanClientId("<script>"));
        assertEquals("tab-abcdef12", LiveChangeInterceptor.cleanClientId("tab-abcdef12"));
    }

    @Test
    void managersOnlyHearAboutTheirOwnArea() {
        CurrentUser sd = new CurrentUser(1L, "a@x", "Ana Lopez", Role.MANAGER, 10L, false);
        CurrentUser adminUser = new CurrentUser(2L, "b@x", "Boss", Role.ADMIN, null, false);
        CurrentUser backOffice = new CurrentUser(3L, "c@x", "Cam", Role.BACK_OFFICE, null, false);
        assertTrue(LiveUpdates.reaches(sd, "schedule", 10L));
        assertFalse(LiveUpdates.reaches(sd, "schedule", 20L));
        assertTrue(LiveUpdates.reaches(sd, "schedule", null), "admin changes reach everyone");
        assertTrue(LiveUpdates.reaches(adminUser, "dayoff", 20L));
        assertTrue(LiveUpdates.reaches(backOffice, "timeclock", 20L));
        assertFalse(LiveUpdates.reaches(backOffice, "schedule", null));
    }

    @Test
    void shuttingDownEndsEveryStream() {
        LiveUpdates updates = new LiveUpdates();
        CurrentUser user = new CurrentUser(1L, "a@x", "Ana Lopez", Role.MANAGER, 10L, false);
        updates.connect(user, "tab-11111111");
        updates.connect(user, "tab-22222222");
        assertEquals(2, updates.connected());
        updates.closeAll();
        assertEquals(0, updates.connected());
    }

    /** A signed-in user of that role in the test area (password already changed). */
    private String token(String code, Role role) {
        Long id = employeeService.create(new EmployeeRequest(code, "Test", code, code.toLowerCase() + "@test.example",
                null, role, role == Role.ADMIN ? null : area, null, 6, true, null)).employee().id();
        Employee e = employeeRepository.findById(id).orElseThrow();
        e.setMustChangePassword(false);
        employeeRepository.saveAndFlush(e);
        return jwtService.issue(id, e.getTokenVersion()).token();
    }
}
