package com.example.schedulingsoftware.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.auth.AuthDtos.LoginRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.ReviewDayOffRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** The field rules on every request body, and the plain-language messages they give. */
class RequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Map<String, String> errors(Object request) {
        Map<String, String> result = new TreeMap<>();
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        violations.forEach(v -> result.put(v.getPropertyPath().toString(), v.getMessage()));
        return result;
    }

    private static EmployeeRequest employee(String code, String first, String last, String email, String phone) {
        return new EmployeeRequest(code, first, last, email, phone, Role.EMPLOYEE, 1L, null, 6, true, null);
    }

    @Test
    void aGoodEmployeePasses() {
        assertEquals(Map.of(), errors(employee("AT-1050", "José", "O'Neil-Smith", "jose.oneil@alliancetel.com", "(619) 555-0142")));
        assertEquals(Map.of(), errors(employee(" at_7 ", " Mary Ann ", "St. John", "a+b@mail.co.uk", null)));
    }

    @Test
    void badEmployeeFieldsAreRejected() {
        Map<String, String> e = errors(employee("AT 10", "Ana3", "<b>Lee</b>", "ana@mail", "call me"));
        assertEquals(Set.of("employeeCode", "firstName", "lastName", "email", "phone"), e.keySet());
        assertEquals("may only contain letters, numbers, - and _ (no spaces)", e.get("employeeCode"));
        assertEquals("must be a valid email like name@example.com", e.get("email"));
        assertTrue(errors(employee("", " ", "x".repeat(61), "a@b.com", null)).keySet()
                .containsAll(List.of("employeeCode", "firstName", "lastName")));
        assertEquals("is required", errors(employee("A1", "", "B", "a@b.com", null)).get("firstName"));
        assertEquals("must be at most 60 characters", errors(employee("A1", "A", "x".repeat(61), "a@b.com", null)).get("lastName"));
        var days = new EmployeeRequest("A1", "A", "B", "a@b.com", null, Role.EMPLOYEE, 1L, null, 9, true, null);
        assertEquals("must be at most 7", errors(days).get("workDaysPerWeek"));
    }

    @Test
    void areasAndStores() {
        assertEquals(Map.of(), errors(new AreaRequest("San Diego (North)", "Coastal stores, I-5 corridor", true)));
        assertEquals(Set.of("name", "description"), errors(new AreaRequest("<script>", "line\nbreak", true)).keySet());
        assertEquals(Map.of(), errors(new StoreRequest(1L, "Oceanside #2", "OCN-2", "401 Mission Ave, Oceanside, CA", 2, true, null, "#2563EB")));
        assertEquals(Map.of(), errors(new StoreRequest(1L, "Plaza", "", null, null, null, null, null)));
        Map<String, String> bad = errors(new StoreRequest(null, "!", "OC N", "<b>", -1, true, null, "blue"));
        assertEquals(Set.of("areaId", "name", "code", "address", "minStaffPerDay", "color"), bad.keySet());
    }

    @Test
    void dayOffRequestsAndNotes() {
        LocalDate d = LocalDate.now().plusDays(10);
        assertEquals(Map.of(), errors(new CreateDayOffRequest(1L, List.of(d), "Doctor's appointment, 2pm", null)));
        assertEquals(Set.of("dates", "reason"), errors(new CreateDayOffRequest(1L, List.of(), "<img>", null)).keySet());
        assertTrue(errors(new CreateDayOffRequest(null, List.of(d, d, d, d, d, d, d, d), null, null)).containsKey("employeeId"));
        assertEquals("can't contain < or > or line breaks", errors(new ReviewDayOffRequest("a\nb")).get("note"));
    }

    @Test
    void signInHasLengthLimits() {
        assertTrue(errors(new LoginRequest("x".repeat(121), "p")).containsKey("email"));
        assertTrue(errors(new LoginRequest("a@b.com", "p".repeat(101))).containsKey("password"));
    }

    @Test
    void fieldNamesBecomeReadableLabels() {
        assertEquals("Employee code", GlobalExceptionHandler.label("employeeCode"));
        assertEquals("Opens at", GlobalExceptionHandler.label("hours[2].opensAt"));
        assertEquals("Dates", GlobalExceptionHandler.label("dates[0]"));
    }
}
