package com.example.schedulingsoftware.area;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.area.AreaDtos.AreaResponse;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.InputRules;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.employee.EmployeeRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AreaService {

    private final AreaRepository areaRepository;
    private final EmployeeRepository employeeRepository;
    private final AccessPolicy accessPolicy;

    @Transactional(readOnly = true)
    public List<AreaResponse> findAll() {
        Map<Long, Long> counts = employeeRepository.countActiveByArea();
        // Managers and employees only see their own area.
        Long onlyArea = accessPolicy.restrictToArea(null);
        return areaRepository.findAllByOrderByNameAsc().stream()
                .filter(a -> onlyArea == null || a.getId().equals(onlyArea))
                .map(a -> toResponse(a, counts.getOrDefault(a.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public AreaResponse findById(Long id) {
        accessPolicy.requireArea(id);
        Area area = getArea(id);
        return toResponse(area, employeeRepository.countByAreaIdAndActiveTrue(id));
    }

    @Transactional
    public AreaResponse create(AreaRequest request) {
        String name = InputRules.clean(request.name());
        if (areaRepository.existsByNameIgnoreCase(name)) {
            throw new BusinessRuleException("An area named '" + name + "' already exists.");
        }
        Area area = new Area(name, InputRules.clean(request.description()));
        if (request.active() != null) {
            area.setActive(request.active());
        }
        return toResponse(areaRepository.save(area), 0);
    }

    @Transactional
    public AreaResponse update(Long id, AreaRequest request) {
        Area area = getArea(id);
        String name = InputRules.clean(request.name());
        if (areaRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new BusinessRuleException("An area named '" + name + "' already exists.");
        }
        area.setName(name);
        area.setDescription(InputRules.clean(request.description()));
        if (request.active() != null) {
            area.setActive(request.active());
        }
        return toResponse(area, employeeRepository.countByAreaIdAndActiveTrue(id));
    }

    @Transactional
    public void delete(Long id) {
        Area area = getArea(id);
        if (employeeRepository.existsByAreaId(id)) {
            throw new BusinessRuleException(
                    "Area '" + area.getName() + "' still has employees. Move them or deactivate the area instead.");
        }
        areaRepository.delete(area);
    }

    public Area getArea(Long id) {
        return areaRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Area", id));
    }

    private AreaResponse toResponse(Area area, long employeeCount) {
        return new AreaResponse(area.getId(), area.getName(), area.getDescription(), area.isActive(), employeeCount);
    }
}
