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
    private final com.example.schedulingsoftware.dayoff.DayOffService dayOffService;

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

    @PostMapping("/generate")
    public ScheduleResponse generate(@Valid @RequestBody GenerateScheduleRequest request) {
        return scheduleService.generate(request.areaId(), request.weekStart());
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

    /** One person's work day and day off trade places (moving their day off within the week). */
    @PostMapping("/{scheduleId}/swap-days")
    public ScheduleResponse swapDays(@PathVariable Long scheduleId, @Valid @RequestBody SwapRequest request) {
        return scheduleService.swapDays(scheduleId, request.entryIdA(), request.entryIdB());
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

    @PostMapping("/{scheduleId}/revert-to-draft")
    public ScheduleResponse revertToDraft(@PathVariable Long scheduleId) {
        return scheduleService.revertToDraft(scheduleId);
    }
}
