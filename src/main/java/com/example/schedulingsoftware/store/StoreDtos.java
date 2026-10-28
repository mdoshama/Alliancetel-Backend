package com.example.schedulingsoftware.store;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class StoreDtos {

    private StoreDtos() {
    }

    public record StoreRequest(
            @NotNull Long areaId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 12) String code,
            @Size(max = 255) String address,
            /** Defaults to 1 when left out. */
            @Min(0) @Max(50) Integer minStaffPerDay,
            Boolean active) {
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
            long employeeCount) {

        public static StoreResponse from(Store s, long employeeCount) {
            return new StoreResponse(s.getId(), s.getArea().getId(), s.getArea().getName(), s.getName(), s.getCode(),
                    s.getLabel(), s.getAddress(), s.getMinStaffPerDay(), s.isActive(), employeeCount);
        }
    }
}
