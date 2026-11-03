package com.example.schedulingsoftware.catalog;

import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.catalog.CatalogDtos.BrandRequest;
import com.example.schedulingsoftware.catalog.CatalogDtos.BrandResponse;
import com.example.schedulingsoftware.catalog.CatalogDtos.DeviceRequest;
import com.example.schedulingsoftware.catalog.CatalogDtos.DeviceResponse;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.InputRules;
import com.example.schedulingsoftware.common.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * The device list: brands and their devices with retail prices. Admins keep it; reps pick from it when logging a sale
 * so they don't have to type the device or its price. Changing a price only affects sales logged afterwards.
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final BrandRepository brandRepository;
    private final DeviceRepository deviceRepository;
    private final AccessPolicy accessPolicy;

    /** Every brand with its devices. Only admins see hidden ones (reps get what they can sell). */
    @Transactional(readOnly = true)
    public List<BrandResponse> brands() {
        boolean all = accessPolicy.current().isAdmin();
        return brandRepository.findAllByOrderByNameAsc().stream()
                .filter(b -> all || b.isActive())
                .map(b -> new BrandResponse(b.getId(), b.getName(), b.isActive(), b.getDevices().stream()
                        .filter(d -> all || d.isActive())
                        .map(DeviceResponse::from)
                        .toList()))
                .toList();
    }

    @Transactional
    public BrandResponse createBrand(BrandRequest request) {
        String name = InputRules.clean(request.name());
        if (brandRepository.existsByNameIgnoreCase(name)) {
            throw new BusinessRuleException("There's already a brand called " + name + ".");
        }
        Brand brand = new Brand(name);
        brand.setActive(request.active() == null || request.active());
        return toResponse(brandRepository.save(brand));
    }

    @Transactional
    public BrandResponse updateBrand(Long id, BrandRequest request) {
        Brand brand = brand(id);
        String name = InputRules.clean(request.name());
        if (brandRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new BusinessRuleException("There's already a brand called " + name + ".");
        }
        brand.setName(name);
        if (request.active() != null) {
            brand.setActive(request.active());
        }
        return toResponse(brand);
    }

    /** Removes a brand and its devices. Sales already logged keep the device name. */
    @Transactional
    public void deleteBrand(Long id) {
        brandRepository.delete(brand(id));
    }

    @Transactional
    public DeviceResponse createDevice(DeviceRequest request) {
        Brand brand = brand(request.brandId());
        String name = InputRules.clean(request.name());
        if (deviceRepository.existsByBrandIdAndNameIgnoreCase(brand.getId(), name)) {
            throw new BusinessRuleException(brand.getName() + " already has " + name + ".");
        }
        Device device = new Device(brand, name, request.retailPrice().setScale(2, RoundingMode.HALF_UP));
        device.setActive(request.active() == null || request.active());
        brand.getDevices().add(device);
        return DeviceResponse.from(deviceRepository.save(device));
    }

    @Transactional
    public DeviceResponse updateDevice(Long id, DeviceRequest request) {
        Device device = device(id);
        Brand brand = brand(request.brandId());
        String name = InputRules.clean(request.name());
        if (deviceRepository.existsByBrandIdAndNameIgnoreCaseAndIdNot(brand.getId(), name, id)) {
            throw new BusinessRuleException(brand.getName() + " already has " + name + ".");
        }
        if (!device.getBrand().getId().equals(brand.getId())) {
            device.getBrand().getDevices().remove(device);
            brand.getDevices().add(device);
            device.setBrand(brand);
        }
        device.setName(name);
        device.setRetailPrice(request.retailPrice().setScale(2, RoundingMode.HALF_UP));
        if (request.active() != null) {
            device.setActive(request.active());
        }
        return DeviceResponse.from(device);
    }

    /** Removes a device. Sales already logged keep its name and the price they were sold at. */
    @Transactional
    public void deleteDevice(Long id) {
        Device device = device(id);
        device.getBrand().getDevices().remove(device);
        deviceRepository.delete(device);
    }

    /** A device reps can sell (for logging a sale). */
    @Transactional(readOnly = true)
    public Device sellable(Long id) {
        Device device = device(id);
        if (!device.isActive() || !device.getBrand().isActive()) {
            throw new BusinessRuleException(device.getDisplayName() + " is no longer on the device list.");
        }
        return device;
    }

    private Brand brand(Long id) {
        return brandRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Brand", id));
    }

    private Device device(Long id) {
        return deviceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }

    private static BrandResponse toResponse(Brand b) {
        return new BrandResponse(b.getId(), b.getName(), b.isActive(),
                b.getDevices().stream().map(DeviceResponse::from).toList());
    }
}
