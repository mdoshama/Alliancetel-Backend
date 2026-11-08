package com.example.schedulingsoftware.timeclock;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
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

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/time-clock")
@RequiredArgsConstructor
public class TimeClockController {

    private final TimeClockService service;

    /** The day's sheet (default today); admins and back office: all areas or one; managers: their own. */
    @GetMapping
    public TimeClockDtos.Day day(@RequestParam(required = false) Long areaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.day(areaId, date);
    }

    @PostMapping("/clock-in")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeClockDtos.Row clockIn(@Valid @RequestBody TimeClockDtos.ClockInRequest request) {
        return service.clockIn(request);
    }

    @PostMapping("/{id}/clock-out")
    public TimeClockDtos.Row clockOut(@PathVariable Long id, @RequestBody(required = false) TimeClockDtos.ClockOutRequest body) {
        return service.clockOut(id, body == null ? null : body.time());
    }

    @PutMapping("/{id}")
    public TimeClockDtos.Row correct(@PathVariable Long id, @Valid @RequestBody TimeClockDtos.CorrectRequest request) {
        return service.correct(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
