package com.example.schedulingsoftware.commission;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Makes sure the single commission plan row exists once the app has started, so opening the Commission page
 * never has to create it (two requests creating it at once used to clash).
 */
@Component
class CommissionPlanSetup {

    private final CommissionPlanRepository planRepository;

    CommissionPlanSetup(CommissionPlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ensurePlan() {
        if (!planRepository.existsById(CommissionPlan.ID)) {
            try {
                planRepository.save(new CommissionPlan());
            } catch (DataIntegrityViolationException alreadyThere) {
                // Another instance created it at the same moment.
            }
        }
    }
}
