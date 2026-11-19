package com.example.schedulingsoftware.store;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreDtos.StoreResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;
    private final com.example.schedulingsoftware.schedule.ScheduleService scheduleService;

    /** Holiday closures in an area, from today on. */
    @GetMapping("/closures")
    public List<StoreDtos.ClosureResponse> closures(@RequestParam(required = false) Long areaId) {
        return storeService.closures(areaId);
    }

    /** Close stores on a date; schedules not yet published move people off those stores that day. */
    @PostMapping("/closures")
    public List<StoreDtos.ClosureResponse> close(@jakarta.validation.Valid @RequestBody StoreDtos.ClosureRequest request) {
        storeService.close(request);
        scheduleService.storesClosed(request.areaId(), request.date());
        return storeService.closures(request.areaId());
    }

    /** Open again on a date: one store, or the whole area when no store is given. */
    @DeleteMapping("/closures")
    public List<StoreDtos.ClosureResponse> reopen(@RequestParam Long areaId, @RequestParam java.time.LocalDate date,
            @RequestParam(required = false) Long storeId) {
        storeService.reopen(areaId, date, storeId);
        return storeService.closures(areaId);
    }

    @GetMapping
    public List<StoreResponse> list(@RequestParam(required = false) Long areaId) {
        return storeService.list(areaId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoreResponse create(@Valid @RequestBody StoreRequest request) {
        return storeService.create(request);
    }

    @PutMapping("/{id}")
    public StoreResponse update(@PathVariable Long id, @Valid @RequestBody StoreRequest request) {
        return storeService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        storeService.delete(id);
    }
}
