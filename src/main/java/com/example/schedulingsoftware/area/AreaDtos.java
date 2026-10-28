package com.example.schedulingsoftware.area;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AreaDtos {

    private AreaDtos() {
    }

    public record AreaRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String description,
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
