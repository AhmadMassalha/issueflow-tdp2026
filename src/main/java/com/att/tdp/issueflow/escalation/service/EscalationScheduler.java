package com.att.tdp.issueflow.escalation.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin {@code @Scheduled} wrapper around {@link EscalationService}
 * (spec 13 §"Trigger" — slice 14).
 *
 * <p><b>Cadence:</b> {@code fixedDelayString = "${escalation.fixed-delay-ms:300000}"} —
 * default 5 minutes between the END of one pass and the START of the
 * next ({@code fixedDelay}, not {@code fixedRate}: avoids overlapping
 * runs if a pass ever takes longer than the interval). The test
 * profile overrides this to ~16 minutes so the cron never fires
 * during a ~30s test suite run (Session 14 D3).
 *
 * <p><b>Why a separate bean from {@link EscalationService}:</b>
 * Session 14 D9. Two reasons:
 * <ol>
 *   <li>Tests can invoke the LOGIC ({@link EscalationService#runEscalationPass()})
 *       synchronously and deterministically by bypassing this scheduler
 *       entirely — they import the service bean and call its method.
 *       No clock manipulation required to test the algorithm.</li>
 *   <li>The {@code @Scheduled} method runs in a system thread context
 *       where {@code SecurityContextHolder} is empty. {@link EscalationService}
 *       writes SYSTEM audit rows (slice-7 {@code logSystem}) which require
 *       no principal anyway, so this is fine — but the separation makes
 *       it explicit that the LOGIC could also be invoked from a regular
 *       request thread (e.g., a future admin "trigger now" endpoint per
 *       D10 — not built this slice, but the seam is clean).</li>
 * </ol>
 *
 * <p>The {@code @Scheduled} method itself catches and logs every
 * exception to prevent a thrown exception from blocking subsequent
 * passes — Spring's scheduler abandons future runs of a
 * {@code @Scheduled} method that throws. The {@link EscalationService}
 * already swallows per-ticket optimistic-lock conflicts; this is the
 * safety net for everything else.
 */
@Component
@RequiredArgsConstructor
public class EscalationScheduler {

    private static final Logger log = LoggerFactory.getLogger(EscalationScheduler.class);

    private final EscalationService escalation;

    @Scheduled(fixedDelayString = "${escalation.fixed-delay-ms:300000}")
    public void run() {
        try {
            escalation.runEscalationPass();
        } catch (Exception ex) {
            // NEVER let an exception escape — Spring's default behavior
            // when a @Scheduled method throws is to log + continue, but
            // historically there have been versions where a thrown
            // exception suppresses future runs. Belt-and-braces logging
            // here makes the failure mode obvious in ops logs while
            // guaranteeing the cron keeps ticking.
            log.error("Escalation pass failed — swallowing to keep the schedule alive.", ex);
        }
    }
}
