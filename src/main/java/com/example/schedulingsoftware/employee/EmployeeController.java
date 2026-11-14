package com.example.schedulingsoftware.employee;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.schedulingsoftware.common.PageResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.CredentialsResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.HomeStoreRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;
    private final BirthdayService birthdayService;
    private final com.example.schedulingsoftware.dayoff.DayOffReservations reservations;

    /** Birthdays in the next "days" days (default 30), soonest first. Admins: all areas; managers: their own. */
    @GetMapping("/birthdays")
    public List<BirthdayService.UpcomingBirthday> birthdays(
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) Integer days) {
        return birthdayService.upcoming(areaId, days);
    }

    @GetMapping
    public List<EmployeeResponse> list(
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false, name = "q") String query) {
        return employeeService.search(areaId, role, active, query);
    }

    /** One page of the staff list: filters, search ("q": name, email or code), page from 0, size up to 100. */
    @GetMapping("/page")
    public PageResponse<EmployeeResponse> page(
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) StoreRole storeRole,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return employeeService.page(areaId, role, active, storeId, storeRole, query, page, size, sort);
    }

    @GetMapping("/{id}")
    public EmployeeResponse get(@PathVariable Long id) {
        return employeeService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CredentialsResponse create(@Valid @RequestBody EmployeeRequest request,
            @RequestParam(defaultValue = "false") boolean textLink) {
        return employeeService.create(request, textLink);
    }

    @PutMapping("/{id}")
    public EmployeeResponse update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        return employeeService.update(id, request);
    }

    @PatchMapping("/{id}/home-store")
    public EmployeeResponse changeHomeStore(@PathVariable Long id, @RequestBody HomeStoreRequest request) {
        return employeeService.changeHomeStore(id, request.storeId(), request.primary());
    }

    /**
     * Sets the days of the week someone is normally off (managers of their area, admins). Days the system holds
     * for them in weeks still open are reserved again on the new days.
     */
    @PutMapping("/{id}/regular-days-off")
    public EmployeeResponse setRegularDaysOff(@PathVariable Long id,
            @Valid @RequestBody EmployeeDtos.RegularDaysOffRequest request) {
        employeeService.setRegularDaysOff(id, request.days(), reservations::checkRegularDays);
        reservations.replanFor(employeeService.getEmployee(id));
        return employeeService.findById(id);
    }

    /**
     * Sets how many days a week someone works (managers of their area, admins). Days the system holds for them in
     * weeks still open are planned again for the new number.
     */
    @PutMapping("/{id}/work-days")
    public EmployeeResponse setWorkDays(@PathVariable Long id,
            @Valid @RequestBody EmployeeDtos.WorkDaysRequest request) {
        employeeService.setWorkDaysPerWeek(id, request.days());
        reservations.replanFor(employeeService.getEmployee(id));
        return employeeService.findById(id);
    }

    /** Admins: the areas a Market Manager manages (besides their home area). */
    @PutMapping("/{id}/managed-areas")
    public EmployeeResponse setManagedAreas(@PathVariable Long id,
            @Valid @RequestBody EmployeeDtos.ManagedAreasRequest request) {
        return employeeService.setManagedAreas(id, request.areaIds());
    }

    @PostMapping("/{id}/reset-password")
    public CredentialsResponse resetPassword(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean textLink) {
        return employeeService.resetPassword(id, textLink);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable Long id) {
        employeeService.deactivate(id);
    }
}
