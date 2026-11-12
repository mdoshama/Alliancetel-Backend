package com.example.schedulingsoftware.area;

import jakarta.validation.constraints.Pattern;
import com.example.schedulingsoftware.common.InputRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AreaDtos {

    private AreaDtos() {
    }

    public record AreaRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = InputRules.PLACE_NAME, message = InputRules.PLACE_NAME_MSG) String name,
            @Size(max = 255) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String description,
            Boolean active) {
    }

    public record AreaResponse(
            Long id,
            String name,
            String description,
            boolean active,
            long employeeCount,
            /** Employees may ask for days off up to this date (opened further ahead); null = the usual window. */
            java.time.LocalDate requestsOpenUntil,
            String requestsOpenedBy) {

        public AreaResponse(Long id, String name, String description, boolean active, long employeeCount) {
            this(id, name, description, active, employeeCount, null, null);
        }
    }

    /** Opens day-off requests for an area up to a date, or back to the usual window with {@code until = null}. */
    public record RequestsOpenUntilRequest(java.time.LocalDate until) {
    }
}
