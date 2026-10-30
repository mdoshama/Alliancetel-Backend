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
            long employeeCount) {
    }
}
