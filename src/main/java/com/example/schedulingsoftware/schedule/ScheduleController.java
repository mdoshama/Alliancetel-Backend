package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.schedule.ScheduleDtos.AddToStoreRequest;
import com.example.schedulingsoftware.schedule.ScheduleDtos.GenerateScheduleRequest;
import com.example.schedulingsoftware.schedule.ScheduleDtos.MoveForWeekRequest;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ExchangeRequest;
import com.example.schedulingsoftware.schedule.ScheduleDtos.SwapRequest;
import com.example.schedulingsoftware.schedule.ScheduleDtos.UpdateEntryRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final WeekRequests weekRequests;
    private final DayOffService dayOffService;
    private final FullWeekService fullWeekService;
    private final com.example.schedulingsoftware.employee.EmployeeService employeeService;
    private final com.example.schedulingsoftware.dayoff.DayOffReservations reservations;
    private final com.example.schedulingsoftware.auth.AccessPolicy accessPolicy;
    private final PastWeekLock pastWeekLock;

    private Long employeeArea(Long employeeId) {
        var area = employeeService.getEmployee(employeeId).getArea();
        return area == null ? null : area.getId();
    }

    /** Managers: who has sent their day-off request for the week, who hasn't, and who was turned down. */
    @GetMapping("/requests")
    public WeekRequests.Status requestStatus(@RequestParam Long areaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return weekRequests.status(areaId, weekStart);
    }

    /** Managers: a suggested day off for everyone without a request (or whose request was turned down). */
    @PostMapping("/requests/suggest")
    public List<WeekRequests.Suggestion> suggest(@RequestParam Long areaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return weekRequests.suggest(areaId, weekStart);
    }

    /** Managers and admins: someone works all 7 days of that week (on), or back to their usual days (off). */
    @PostMapping("/requests/full-week")
    public WeekRequests.Status fullWeek(@Valid @RequestBody FullWeekRequest request) {
        pastWeekLock.requireOpen(employeeArea(request.employeeId()), request.weekStart());
        fullWeekService.set(request.employeeId(), request.weekStart(), Boolean.TRUE.equals(request.on()));
        Long areaId = employeeArea(request.employeeId());
        return weekRequests.status(areaId, request.weekStart());
    }

    public record FullWeekRequest(@jakarta.validation.constraints.NotNull Long employeeId,
            @jakarta.validation.constraints.NotNull LocalDate weekStart, Boolean on) {
    }

    /** Managers: gives the chosen suggested days off (approved, and each person is told). */
    @PostMapping("/requests/apply")
    public List<com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse> applySuggestions(
            @Valid @RequestBody com.example.schedulingsoftware.dayoff.DayOffDtos.AssignDaysOffRequest request) {
        return dayOffService.assignDaysOff(request.areaId(), request.items());
    }

    /** Returns 204 when no schedule has been generated for that area and week yet. */
    @GetMapping
    public ResponseEntity<ScheduleResponse> find(
            @RequestParam Long areaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return scheduleService.find(areaId, weekStart)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Whether a week that has passed is locked for you, and the latest request to open it. */
    @GetMapping("/past-week-access")
    public PastWeekLock.WeekStatus pastWeekStatus(@RequestParam Long areaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return pastWeekLock.status(areaId, weekStart);
    }

    /** Managers: ask an admin to open a week that has passed. */
    @PostMapping("/past-week-access")
    public PastWeekLock.AccessView askPastWeekAccess(@Valid @RequestBody PastWeekAccessRequest request) {
        return pastWeekLock.request(request.areaId(), request.weekStart(), request.reason());
    }

    public record PastWeekAccessRequest(@jakarta.validation.constraints.NotNull Long areaId,
            @jakarta.validation.constraints.NotNull LocalDate weekStart,
            @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    /** Admins: requests to open past weeks that are waiting. */
    @GetMapping("/past-week-access/pending")
    public List<PastWeekLock.AccessView> pendingPastWeekAccess() {
        return pastWeekLock.pending();
    }

    /** Admins: open the week (for "hours", 24 by default) or turn the request down. */
    @PostMapping("/past-week-access/{id}/decide")
    public PastWeekLock.AccessView decidePastWeekAccess(@PathVariable Long id,
            @RequestBody PastWeekDecision decision) {
        return pastWeekLock.decide(id, Boolean.TRUE.equals(decision.approve()), decision.hours(), decision.note());
    }

    public record PastWeekDecision(Boolean approve, Integer hours,
            @jakarta.validation.constraints.Size(max = 500) String note) {
    }

    /** Admins: lock an opened week again. */
    @PostMapping("/past-week-access/{id}/lock")
    public PastWeekLock.AccessView lockPastWeek(@PathVariable Long id) {
        return pastWeekLock.lockAgain(id);
    }

    @PostMapping("/generate")
    public ScheduleResponse generate(@Valid @RequestBody GenerateScheduleRequest request) {
        accessPolicy.requireManagerOf(request.areaId());
        // Nobody is left without a day off: waiting requests are settled, regular days come first, then requests,
        // and the system reserves the rest. A published week keeps the days it was published with.
        if (!reservations.published(request.areaId(), DayOffService.weekStart(request.weekStart()))) {
            dayOffService.settleWaitingRequests(request.areaId(), request.weekStart());
            reservations.fillBeforeGenerating(request.areaId(), request.weekStart());
        }
        return scheduleService.generate(request.areaId(), request.weekStart(), Boolean.TRUE.equals(request.force()));
    }

    @PatchMapping("/{scheduleId}/entries/{entryId}")
    public ScheduleResponse updateEntry(@PathVariable Long scheduleId, @PathVariable Long entryId,
            @Valid @RequestBody UpdateEntryRequest request) {
        return scheduleService.updateEntry(scheduleId, entryId, request.type(), request.storeId());
    }

    @PostMapping("/{scheduleId}/swap")
    public ScheduleResponse swap(@PathVariable Long scheduleId, @Valid @RequestBody SwapRequest request) {
        return scheduleService.swap(scheduleId, request.entryIdA(), request.entryIdB());
    }

    /** Drag and drop: two days trade places, any person and any day (optionally landing at a store). */
    @PostMapping("/{scheduleId}/exchange")
    public ScheduleResponse exchange(@PathVariable Long scheduleId, @Valid @RequestBody ExchangeRequest request) {
        return scheduleService.exchange(scheduleId, request.fromEntryId(), request.toEntryId(), request.storeId());
    }

    /** Re-runs automatic store assignment, keeping days set by hand. */
    @PostMapping("/{scheduleId}/auto-assign-stores")
    public ScheduleResponse autoAssignStores(@PathVariable Long scheduleId) {
        return scheduleService.autoAssignStores(scheduleId);
    }

    @PostMapping("/{scheduleId}/move-for-week")
    public ScheduleResponse moveForWeek(@PathVariable Long scheduleId, @Valid @RequestBody MoveForWeekRequest request) {
        return scheduleService.moveForWeek(scheduleId, request.employeeId(), request.storeId());
    }

    /** "Save draft": marks everything up to now as saved, so the manager can carry on later from here. */
    @PostMapping("/{scheduleId}/save-draft")
    public ScheduleResponse saveDraft(@PathVariable Long scheduleId) {
        return scheduleService.saveDraft(scheduleId);
    }

    /** "Add people where needed": move someone who works that day to a store that is short. */
    @PostMapping("/{scheduleId}/add-to-store")
    public ScheduleResponse addToStore(@PathVariable Long scheduleId, @Valid @RequestBody AddToStoreRequest request) {
        return scheduleService.addToStore(scheduleId, request.entryId(), request.storeId());
    }

    @PostMapping("/{scheduleId}/publish")
    public ScheduleResponse publish(@PathVariable Long scheduleId) {
        return scheduleService.publish(scheduleId);
    }

    /** Throws the week's schedule away so it can be generated again from scratch. */
    @org.springframework.web.bind.annotation.DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> reset(@PathVariable Long scheduleId) {
        scheduleService.reset(scheduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{scheduleId}/revert-to-draft")
    public ScheduleResponse revertToDraft(@PathVariable Long scheduleId) {
        return scheduleService.revertToDraft(scheduleId);
    }
}
