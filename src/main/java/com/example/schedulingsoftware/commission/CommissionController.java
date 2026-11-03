package com.example.schedulingsoftware.commission;

import java.time.YearMonth;
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

import com.example.schedulingsoftware.commission.CommissionDtos.AdjustmentRequest;
import com.example.schedulingsoftware.commission.CommissionDtos.AdjustmentResponse;
import com.example.schedulingsoftware.commission.CommissionDtos.AdjustmentTypeOption;
import com.example.schedulingsoftware.commission.CommissionDtos.MonthResponse;
import com.example.schedulingsoftware.commission.CommissionDtos.PlanDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Commission: admins only (SecurityConfig and the service both check). */
@RestController
@RequestMapping("/api/commission")
@RequiredArgsConstructor
public class CommissionController {

    private final CommissionService commissionService;

    @GetMapping("/plan")
    public PlanDto plan() {
        return commissionService.plan();
    }

    @PutMapping("/plan")
    public PlanDto updatePlan(@Valid @RequestBody PlanDto plan) {
        return commissionService.updatePlan(plan);
    }

    @GetMapping("/adjustment-types")
    public List<AdjustmentTypeOption> adjustmentTypes() {
        return commissionService.adjustmentTypes();
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public AdjustmentResponse addAdjustment(@Valid @RequestBody AdjustmentRequest request) {
        return commissionService.addAdjustment(request);
    }

    @DeleteMapping("/adjustments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdjustment(@PathVariable Long id) {
        commissionService.deleteAdjustment(id);
    }

    /** {@code month} like 2026-11. */
    @GetMapping("/months/{month}")
    public MonthResponse month(@PathVariable YearMonth month, @RequestParam(required = false) Long areaId) {
        return commissionService.month(month, areaId);
    }

    @PostMapping("/months/{month}/finalize")
    public MonthResponse finalizeMonth(@PathVariable YearMonth month) {
        return commissionService.finalizeMonth(month);
    }

    @PostMapping("/months/{month}/reopen")
    public MonthResponse reopenMonth(@PathVariable YearMonth month) {
        return commissionService.reopenMonth(month);
    }
}
