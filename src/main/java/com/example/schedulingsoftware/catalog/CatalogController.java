package com.example.schedulingsoftware.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.schedulingsoftware.catalog.CatalogDtos.BrandRequest;
import com.example.schedulingsoftware.catalog.CatalogDtos.BrandResponse;
import com.example.schedulingsoftware.catalog.CatalogDtos.DeviceRequest;
import com.example.schedulingsoftware.catalog.CatalogDtos.DeviceResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Device list. Everyone signed in can read it; only admins change it (see SecurityConfig). */
@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/brands")
    public List<BrandResponse> brands() {
        return catalogService.brands();
    }

    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    public BrandResponse createBrand(@Valid @RequestBody BrandRequest request) {
        return catalogService.createBrand(request);
    }

    @PutMapping("/brands/{id}")
    public BrandResponse updateBrand(@PathVariable Long id, @Valid @RequestBody BrandRequest request) {
        return catalogService.updateBrand(id, request);
    }

    @DeleteMapping("/brands/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBrand(@PathVariable Long id) {
        catalogService.deleteBrand(id);
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse createDevice(@Valid @RequestBody DeviceRequest request) {
        return catalogService.createDevice(request);
    }

    @PutMapping("/devices/{id}")
    public DeviceResponse updateDevice(@PathVariable Long id, @Valid @RequestBody DeviceRequest request) {
        return catalogService.updateDevice(id, request);
    }

    @DeleteMapping("/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDevice(@PathVariable Long id) {
        catalogService.deleteDevice(id);
    }
}
