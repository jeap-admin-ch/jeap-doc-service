package ch.admin.bit.jeap.doc.domain.governance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * What the Governance Service's webhook call does: ignores a repeated event ID, and otherwise asks for the
 * governance data to be imported at once.
 * <p>
 * <b>Only the last event ID is remembered</b>, not a history of them. The call reports that a scoring run
 * completed, and an import reads the Governance Service's current state rather than one run's worth of it -
 * so an event ID older than the last one accepted can never be the one a reader is still waiting for, and
 * remembering more of them would answer nothing a single value does not.
 * <p>
 * This is this instance's own memory, not a durable one. Two instances behind a load balancer may each accept
 * the same event ID once; the import it starts twice is the same conditional request either way, and the
 * second of the two reads every index unconditionally for nothing - which is cheap, and never wrong.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceWebhook {

    private final GovernanceImportPickup pickup;

    private final AtomicReference<String> lastAcceptedEventId = new AtomicReference<>();

    /**
     * @param eventId the event ID the call reported, never null
     * @return whether this was a new event - the caller may use this for its own log line, but answers
     * {@code 202} either way: the caller does not have to tell a duplicate from a first delivery
     */
    public boolean notifyOfAScoringRun(String eventId) {
        if (eventId.equals(lastAcceptedEventId.get())) {
            log.debug("The governance webhook event {} was already accepted; it is ignored.", eventId);
            return false;
        }
        // An event this instance can no longer import is not remembered, so a redelivery is not ignored.
        if (pickup.whenNotifiedByWebhook()) {
            lastAcceptedEventId.set(eventId);
        } else {
            log.debug("This instance is stopping; the governance webhook event {} is not imported here.", eventId);
        }
        return true;
    }
}
