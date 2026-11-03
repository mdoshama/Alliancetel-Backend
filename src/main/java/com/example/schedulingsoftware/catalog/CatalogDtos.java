package com.example.schedulingsoftware.catalog;

import java.math.BigDecimal;
import java.util.List;

import com.example.schedulingsoftware.common.InputRules;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record BrandRequest(
            @NotBlank @Size(max = 60) @Pattern(regexp = InputRules.PLACE_NAME, message = InputRules.PLACE_NAME_MSG) String name,
            Boolean active) {
    }

    public record DeviceRequest(
            @NotNull Long brandId,
            @NotBlank @Size(max = 80) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String name,
            @NotNull @DecimalMin("0") @DecimalMax("10000") @Digits(integer = 5, fraction = 2) BigDecimal retailPrice,
            Boolean active) {
    }

    public record DeviceResponse(Long id, Long brandId, String brandName, String name, String displayName,
            BigDecimal retailPrice, boolean active) {

        static DeviceResponse from(Device d) {
            return new DeviceResponse(d.getId(), d.getBrand().getId(), d.getBrand().getName(), d.getName(),
                    d.getDisplayName(), d.getRetailPrice(), d.isActive());
        }
    }

    public record BrandResponse(Long id, String name, boolean active, List<DeviceResponse> devices) {
    }
}
