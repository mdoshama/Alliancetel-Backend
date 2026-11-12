package com.example.schedulingsoftware.store;

import com.example.schedulingsoftware.common.InputRules;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class StoreDtos {

    private StoreDtos() {
    }

    public record StoreRequest(
            @NotNull Long areaId,
            @NotBlank @Size(max = 100) @Pattern(regexp = InputRules.PLACE_NAME, message = InputRules.PLACE_NAME_MSG) String name,
            @Size(max = 12) @Pattern(regexp = InputRules.CODE, message = InputRules.CODE_MSG) String code,
            @Size(max = 255) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String address,
            /** Defaults to 1 when left out. */
            @Min(0) @Max(50) Integer minStaffPerDay,
            Boolean active,
            /** Opening hours for any weekdays being set; days left out use 10:00–20:00. Null keeps the current hours. */
            @Valid @Size(max = 7, message = "can list each weekday only once") List<DayHours> hours,
            /** Hex color like "#2563EB". Null keeps the current color (new stores get an unused palette color). */
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a color like #2563EB") String color,
            /** Most floaters auto-fill sends here on one day (0 = no limit). Null keeps the current limit. */
            @Min(0) @Max(50) Integer maxFloatersPerDay) {

        /** Without a floater limit (keeps the current one). */
        public StoreRequest(Long areaId, String name, String code, String address, Integer minStaffPerDay,
                Boolean active, List<DayHours> hours, String color) {
            this(areaId, name, code, address, minStaffPerDay, active, hours, color, null);
        }
    }

    /**
     * One day's hours. When {@code closed} is true the times are ignored. {@code minStaff} is the fewest people needed
     * that day when it differs from the store's usual minimum; null uses the store's minimum.
     */
    public record DayHours(@NotNull DayOfWeek day, LocalTime opensAt, LocalTime closesAt, boolean closed,
            @Min(0) @Max(50) Integer minStaff) {

        /** Without a day-specific minimum (the store's usual one applies). */
        public DayHours(DayOfWeek day, LocalTime opensAt, LocalTime closesAt, boolean closed) {
            this(day, opensAt, closesAt, closed, null);
        }

        public static DayHours from(StoreDayHours h) {
            return new DayHours(h.getDayOfWeek(), h.getOpensAt(), h.getClosesAt(), h.isClosed(), h.getMinStaff());
        }

        /** All seven days, Monday first, with defaults filled in. */
        public static List<DayHours> weekOf(Store s) {
            return Arrays.stream(DayOfWeek.values()).map(d -> from(s.hoursOn(d))).toList();
        }
    }

    public record StoreResponse(
            Long id,
            Long areaId,
            String areaName,
            String name,
            String code,
            String label,
            String address,
            int minStaffPerDay,
            boolean active,
            long employeeCount,
            List<DayHours> hours,
            String color,
            /** Most floaters auto-fill sends here on one day; 0 = no limit. */
            int maxFloatersPerDay) {

        public static StoreResponse from(Store s, long employeeCount) {
            return new StoreResponse(s.getId(), s.getArea().getId(), s.getArea().getName(), s.getName(), s.getCode(),
                    s.getLabel(), s.getAddress(), s.getMinStaffPerDay(), s.isActive(), employeeCount,
                    DayHours.weekOf(s), s.getDisplayColor(),
                    s.getMaxFloatersPerDay());
        }
    }
}
