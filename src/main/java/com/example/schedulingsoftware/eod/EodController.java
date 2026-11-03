package com.example.schedulingsoftware.eod;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.schedulingsoftware.eod.EodDtos.CategoryOption;
import com.example.schedulingsoftware.eod.EodDtos.ClosingRequest;
import com.example.schedulingsoftware.eod.EodDtos.DayOverview;
import com.example.schedulingsoftware.eod.EodDtos.LineRequest;
import com.example.schedulingsoftware.eod.EodDtos.MonthSummary;
import com.example.schedulingsoftware.eod.EodDtos.ReportResponse;
import com.example.schedulingsoftware.eod.EodDtos.TodayResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** End of day (replaces the store's daily sheet). Who may do what is checked in {@link EodService}. */
@RestController
@RequestMapping("/api/eod")
@RequiredArgsConstructor
public class EodController {

    private final EodService eodService;

    @GetMapping("/categories")
    public List<CategoryOption> categories() {
        return eodService.categories();
    }

    /** Staff a sale at this store can be logged for or shared with. */
    @GetMapping("/colleagues")
    public List<EodDtos.Colleague> colleagues(@RequestParam Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return eodService.colleagues(storeId, date);
    }

    /** Splits the day's accessories by hand at close (an empty list undoes it). */
    @PutMapping("/accessory-split")
    public ReportResponse saveSplit(@Valid @RequestBody EodDtos.SplitRequest request) {
        return eodService.saveSplit(request);
    }

    /** The signed-in person's store today and its EOD. */
    @GetMapping("/today")
    public TodayResponse today() {
        return eodService.today();
    }

    @GetMapping
    public ReportResponse find(@RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return eodService.find(storeId, date);
    }

    @GetMapping("/day")
    public DayOverview day(@RequestParam(required = false) Long areaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return eodService.day(areaId, date);
    }

    /** {@code month} like 2026-11. */
    @GetMapping("/month")
    public MonthSummary month(@RequestParam(required = false) Long areaId, @RequestParam YearMonth month,
            @RequestParam(required = false) Long storeId) {
        return eodService.month(areaId, month, storeId);
    }

    @PostMapping("/lines")
    public ReportResponse addLine(@Valid @RequestBody LineRequest request) {
        return eodService.addLine(request);
    }

    @PutMapping("/lines/{lineId}")
    public ReportResponse updateLine(@PathVariable Long lineId, @Valid @RequestBody LineRequest request) {
        return eodService.updateLine(lineId, request);
    }

    @DeleteMapping("/lines/{lineId}")
    public ReportResponse deleteLine(@PathVariable Long lineId) {
        return eodService.deleteLine(lineId);
    }

    @PutMapping("/closing")
    public ReportResponse saveClosing(@Valid @RequestBody ClosingRequest request) {
        return eodService.saveClosing(request);
    }

    @PostMapping("/submit")
    public ReportResponse submit(@Valid @RequestBody ClosingRequest request) {
        return eodService.submit(request);
    }

    @PostMapping("/{reportId}/approve")
    public ReportResponse approve(@PathVariable Long reportId) {
        return eodService.approve(reportId);
    }

    @PostMapping("/{reportId}/reopen")
    public ReportResponse reopen(@PathVariable Long reportId) {
        return eodService.reopen(reportId);
    }
}
