package com.example.schedulingsoftware.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Only admins create, change or delete areas and stores; everyone signed in can look at them. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminOnlyAreasAndStoresTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    JwtService jwtService;

    Long area;
    Long store;
    String admin;
    String manager;
    String employee;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Access Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Access Store", "ACS", null, null, true, null, null)).id();
        admin = token("AO-1", Role.ADMIN);
        manager = token("AO-2", Role.MANAGER);
        employee = token("AO-3", Role.EMPLOYEE);
        TestUsers.signOut();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    @Test
    void managersAndEmployeesCannotCreateChangeOrDeleteAreas() throws Exception {
        String body = "{\"name\":\"Sneaky Area\",\"active\":true}";
        for (String t : new String[] {manager, employee}) {
            mvc.perform(as(t, post("/api/areas").contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isForbidden());
            mvc.perform(as(t, put("/api/areas/" + area).contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isForbidden());
            mvc.perform(as(t, delete("/api/areas/" + area))).andExpect(status().isForbidden());
        }
        mvc.perform(as(admin, post("/api/areas").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void managersAndEmployeesCannotCreateChangeOrDeleteStores() throws Exception {
        String body = "{\"areaId\":" + area + ",\"name\":\"Sneaky Store\",\"active\":true}";
        for (String t : new String[] {manager, employee}) {
            mvc.perform(as(t, post("/api/stores").contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isForbidden());
            mvc.perform(as(t, put("/api/stores/" + store).contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isForbidden());
            mvc.perform(as(t, delete("/api/stores/" + store))).andExpect(status().isForbidden());
        }
        mvc.perform(as(admin, post("/api/stores").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void everyoneSignedInCanStillLookAtStores() throws Exception {
        mvc.perform(as(manager, get("/api/stores").param("areaId", area.toString()))).andExpect(status().isOk());
        mvc.perform(as(employee, get("/api/stores").param("areaId", area.toString()))).andExpect(status().isOk());
        mvc.perform(get("/api/stores")).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
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
