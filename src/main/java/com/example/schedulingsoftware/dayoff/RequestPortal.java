package com.example.schedulingsoftware.dayoff;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which weeks people can ask for days off right now. Normally (AUTO) that's next week until the end of Friday, and
 * from Saturday the week after. An admin can open it (next week takes requests even after Friday) or close it.
 */
@Service
public class RequestPortal {

    private final RequestPortalRepository repository;
    private final ZoneId zone;

    public RequestPortal(RequestPortalRepository repository,
            @Value("${alliancetel.scheduling.zone:America/Los_Angeles}") String zone) {
        this.repository = repository;
        this.zone = ZoneId.of(zone);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    @Transactional(readOnly = true)
    public PortalMode mode() {
        return repository.findById(RequestPortalSetting.ID).map(RequestPortalSetting::getMode).orElse(PortalMode.AUTO);
    }

    @Transactional(readOnly = true)
    public RequestPortalSetting setting() {
        return repository.findById(RequestPortalSetting.ID).orElseGet(RequestPortalSetting::new);
    }

    @Transactional
    public RequestPortalSetting save(RequestPortalSetting setting) {
        return repository.save(setting);
    }

    public RequestPortalSetting setMode(PortalMode mode, String by) {
        RequestPortalSetting s = repository.findById(RequestPortalSetting.ID).orElseGet(RequestPortalSetting::new);
        s.setMode(mode);
        s.setChangedBy(by);
        s.setChangedAt(Instant.now());
        return repository.save(s);
    }

    /** Monday of the week the normal cycle is taking requests for: next week until Friday, then the week after. */
    public static LocalDate openWeek(LocalDate today) {
        int ahead = today.getDayOfWeek().getValue() >= DayOfWeek.SATURDAY.getValue() ? 2 : 1;
        return DayOffService.weekStart(today).plusWeeks(ahead);
    }

    /** The weeks people can ask for right now (empty when closed). */
    @Transactional(readOnly = true)
    public List<LocalDate> openWeeks() {
        return openWeeks(mode(), today());
    }

    static List<LocalDate> openWeeks(PortalMode mode, LocalDate today) {
        LocalDate auto = openWeek(today);
        return switch (mode) {
            case AUTO -> List.of(auto);
            case CLOSED -> List.of();
            case OPEN -> {
                List<LocalDate> weeks = new ArrayList<>();
                LocalDate next = DayOffService.weekStart(today).plusWeeks(1);
                weeks.add(next);
                if (!auto.equals(next)) {
                    weeks.add(auto);
                }
                yield weeks;
            }
        };
    }

    @Transactional(readOnly = true)
    public boolean isOpenFor(LocalDate weekStart) {
        return openWeeks().contains(weekStart);
    }

    /** Weeks that get reserved days off: next week and the week taking requests (whatever the portal's mode). */
    public List<LocalDate> reservableWeeks() {
        LocalDate today = today();
        LocalDate next = DayOffService.weekStart(today).plusWeeks(1);
        LocalDate auto = openWeek(today);
        return next.equals(auto) ? List.of(next) : List.of(next, auto);
    }
}
