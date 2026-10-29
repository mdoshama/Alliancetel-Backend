package com.example.schedulingsoftware.dayoff;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.schedulingsoftware.common.PageResponse;

import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse;
import com.example.schedulingsoftware.dayoff.DayOffDtos.ReviewDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.TakenDay;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayOffImpact;
import com.example.schedulingsoftware.schedule.ScheduleService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/day-off-requests")
@RequiredArgsConstructor
public class DayOffController {

    private final DayOffService dayOffService;
    private final ScheduleService scheduleService;

    /** What approving this request would do to staffing that week (managers and admins). */
    @GetMapping("/{id}/impact")
    public DayOffImpact impact(@PathVariable Long id) {
        return scheduleService.dayOffImpact(id);
    }

    @GetMapping
    public List<DayOffResponse> list(
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) DayOffStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dayOffService.search(areaId, employeeId, status, from, to);
    }

    /** One page of requests; "q" searches the employee's name or code. */
    @GetMapping("/page")
    public PageResponse<DayOffResponse> page(
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) DayOffStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return dayOffService.page(areaId, employeeId, status, from, to, query, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<DayOffResponse> create(@Valid @RequestBody CreateDayOffRequest request) {
        return dayOffService.create(request);
    }

    /** Days a colleague at the same store already has off, so the request form can show them as taken. */
    @GetMapping("/taken-days")
    public List<TakenDay> takenDays(@RequestParam Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dayOffService.takenDays(employeeId, from, to);
    }

    @PostMapping("/{id}/approve")
    public DayOffResponse approve(@PathVariable Long id, @Valid @RequestBody(required = false) ReviewDayOffRequest body) {
        return dayOffService.approve(id, body == null ? null : body.note());
    }

    @PostMapping("/{id}/reject")
    public DayOffResponse reject(@PathVariable Long id, @Valid @RequestBody(required = false) ReviewDayOffRequest body) {
        return dayOffService.reject(id, body == null ? null : body.note());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id) {
        dayOffService.cancel(id);
    }
}
