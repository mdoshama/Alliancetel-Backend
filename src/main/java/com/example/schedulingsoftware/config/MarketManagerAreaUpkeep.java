package com.example.schedulingsoftware.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Market Managers have no home area any more. On startup, anyone who still has one keeps managing it (it's added to
 * the areas they manage) and loses the home area and home store; they're no longer on the schedule. Runs every start
 * and does nothing once everyone is moved.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketManagerAreaUpkeep implements ApplicationRunner {

    private final EmployeeRepository employees;
    private final TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(status -> {
            for (Employee e : employees.findByRoleAndAreaIsNotNull(Role.MARKET_MANAGER)) {
                e.getManagedAreas().add(e.getArea());
                e.setArea(null);
                e.setHomeStore(null);
                e.setPrimaryAtStore(false);
                e.getFloatStores().clear();
                e.setOnSchedule(false);
                log.info("Market Manager {}: home area moved to the areas they manage", e.getEmployeeCode());
            }
        });
    }
}
