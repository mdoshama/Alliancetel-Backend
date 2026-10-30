package com.example.schedulingsoftware.notify;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.Area;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.auth.CurrentUser;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.InputRules;
import com.example.schedulingsoftware.common.PageResponse;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.notify.NotificationDtos.AnnounceResult;
import com.example.schedulingsoftware.notify.NotificationDtos.NotificationResponse;
import com.example.schedulingsoftware.notify.NotificationDtos.TemplateInfo;
import com.example.schedulingsoftware.notify.NotificationDtos.WhatsAppStatus;

import lombok.RequiredArgsConstructor;

/** The Messages page: what was sent to whom (email and WhatsApp), announcements, and trying a message again. */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;
    private final EmployeeRepository employeeRepository;
    private final AreaService areaService;
    private final AccessPolicy accessPolicy;
    private final WhatsAppSettings settings;
    private final ScheduleNotifier notifier;
    private final EmailSettings emailSettings;

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> page(Long requestedAreaId, NotificationStatus status,
            NotificationChannel channel, Integer page, Integer size) {
        Long areaId = accessPolicy.restrictToArea(requestedAreaId);
        return PageResponse.from(repository.search(areaId, status, channel,
                PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "id"))), NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public WhatsAppStatus status() {
        Long areaId = accessPolicy.restrictToArea(null);
        List<TemplateInfo> templates = Arrays.stream(NotificationKind.values())
                .map(k -> new TemplateInfo(k, settings.template(k), ScheduleNotifier.TEMPLATE_TEXT.get(k)))
                .toList();
        List<Employee> people = areaId == null ? employeeRepository.findAll().stream().filter(Employee::isActive).toList()
                : employeeRepository.findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId,
                        EnumSet.of(Role.MANAGER, Role.EMPLOYEE));
        long noPhone = people.stream().filter(e -> e.getRole() != Role.ADMIN && e.getPhone() == null).count();
        long notAgreed = people.stream().filter(e -> e.getRole() != Role.ADMIN && e.getPhone() != null && !e.isWhatsappOptIn())
                .count();
        return new WhatsAppStatus(settings.isConnected(), settings.getApiVersion(), settings.getLanguage(),
                !settings.getAppUrl().isEmpty(), templates, count(areaId, NotificationStatus.QUEUED),
                count(areaId, NotificationStatus.FAILED), count(areaId, NotificationStatus.NOT_SENT), noPhone, notAgreed,
                emailSettings.isConnected(), emailSettings.getFrom(),
                people.stream().filter(e -> e.getRole() != Role.ADMIN && !e.isEmailOptIn()).count());
    }

    private long count(Long areaId, NotificationStatus status) {
        return areaId == null ? repository.countByStatus(status) : repository.countByAreaIdAndStatus(areaId, status);
    }

    /** A short message to everyone active in an area (managers can message their own area). */
    @Transactional
    public AnnounceResult announce(Long areaId, String message) {
        accessPolicy.requireManagerOf(areaId);
        Area area = areaService.getArea(areaId);
        CurrentUser user = accessPolicy.current();
        List<Employee> people = employeeRepository.findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(
                areaId, EnumSet.of(Role.MANAGER, Role.EMPLOYEE)).stream()
                .filter(e -> !e.getId().equals(user.id()))
                .toList();
        if (people.isEmpty()) {
            throw new BusinessRuleException("There's nobody else in " + area.getName() + " to message.");
        }
        int n = notifier.announce(area, people, user.fullName(), InputRules.clean(message));
        // Reachable: gets it by email, or by WhatsApp once that's connected.
        int reachable = (int) people.stream()
                .filter(e -> (e.getEmail() != null && e.isEmailOptIn())
                        || (settings.isConnected() && e.getPhone() != null && e.isWhatsappOptIn()))
                .count();
        return new AnnounceResult(n, reachable);
    }

    /** Queues a failed or unsent message again, with the person's current phone number. */
    @Transactional
    public NotificationResponse retry(Long id) {
        Notification n = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Message", id));
        accessPolicy.requireManagerOf(n.getAreaId());
        if (n.getStatus() == NotificationStatus.SENT || n.getStatus() == NotificationStatus.QUEUED) {
            throw new BusinessRuleException("This message was already " + (n.getStatus() == NotificationStatus.SENT
                    ? "sent." : "queued."));
        }
        Employee e = n.getEmployee();
        if (e == null) {
            throw new BusinessRuleException("This person no longer exists.");
        }
        if (n.getChannel() == NotificationChannel.EMAIL) {
            if (!e.isEmailOptIn()) {
                throw new BusinessRuleException(e.getFirstName() + " turned off email updates.");
            }
            n.setEmail(e.getEmail());
        } else {
            if (e.getPhone() == null) {
                throw new BusinessRuleException("Add a phone number for " + e.getFirstName() + " first.");
            }
            if (!e.isWhatsappOptIn()) {
                throw new BusinessRuleException(e.getFirstName() + " hasn't agreed to WhatsApp messages yet.");
            }
            n.setPhone(e.getPhone());
        }
        n.setStatus(NotificationStatus.QUEUED);
        n.setAttempts(0);
        n.setError(null);
        n.setNextAttemptAt(java.time.Instant.now());
        return NotificationResponse.from(n);
    }
}
