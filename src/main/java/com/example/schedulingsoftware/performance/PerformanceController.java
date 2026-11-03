package com.example.schedulingsoftware.performance;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.performance.PerformanceDtos.Leaderboard;
import com.example.schedulingsoftware.performance.PerformanceDtos.Profile;

import lombok.RequiredArgsConstructor;

/** Sales performance by employee (from the EOD credit ledger). */
@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
public class PerformanceController {

    private final PerformanceService performanceService;
    private final AccessPolicy accessPolicy;

    @GetMapping("/leaderboard")
    public Leaderboard leaderboard(@RequestParam(required = false) Long areaId,
            @RequestParam(required = false) Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return performanceService.leaderboard(areaId, storeId, from, to);
    }

    @GetMapping("/employees/{employeeId}")
    public Profile profile(@PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return performanceService.profile(employeeId, from, to);
    }

    /** The signed-in person's own profile. */
    @GetMapping("/me")
    public Profile me(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return performanceService.profile(accessPolicy.current().id(), from, to);
    }
}
