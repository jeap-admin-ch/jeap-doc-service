package ch.admin.bit.jeap.doc.domain.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the webhook call does once a token has been checked: ignore a repeated event ID, otherwise ask for an
 * import.
 */
@ExtendWith(MockitoExtension.class)
class GovernanceWebhookTest {

    @Mock
    private GovernanceImportPickup pickup;

    private GovernanceWebhook webhook;

    @BeforeEach
    void setUp() {
        webhook = new GovernanceWebhook(pickup);
        lenient().when(pickup.whenNotifiedByWebhook()).thenReturn(true);
    }

    @Test
    void notifyOfAScoringRun_thenTheImportIsPickedUp() {
        boolean accepted = webhook.notifyOfAScoringRun("event-1");

        assertThat(accepted).isTrue();
        verify(pickup).whenNotifiedByWebhook();
    }

    @Test
    void notifyOfAScoringRun_whenTheEventIdWasAlreadyAccepted_thenItIsIgnored() {
        webhook.notifyOfAScoringRun("event-1");

        boolean accepted = webhook.notifyOfAScoringRun("event-1");

        assertThat(accepted).isFalse();
        verify(pickup, times(1)).whenNotifiedByWebhook();
    }

    @Test
    void notifyOfAScoringRun_whenANewEventIdFollows_thenItIsAcceptedToo() {
        webhook.notifyOfAScoringRun("event-1");

        boolean accepted = webhook.notifyOfAScoringRun("event-2");

        assertThat(accepted).isTrue();
        verify(pickup, times(2)).whenNotifiedByWebhook();
    }

    /** Only the last event ID is remembered, not a history of them - see the class's own javadoc for why. */
    @Test
    void notifyOfAScoringRun_whenTheFirstEventIdIsRepeatedAfterASecondOne_thenItIsAcceptedAgain() {
        webhook.notifyOfAScoringRun("event-1");
        webhook.notifyOfAScoringRun("event-2");

        boolean accepted = webhook.notifyOfAScoringRun("event-1");

        assertThat(accepted).isTrue();
    }

    /** A stopping instance cannot import it, so a redelivery of the same event must still be heard. */
    @Test
    void notifyOfAScoringRun_whenThePickupCouldNotTakeIt_thenARedeliveryIsNotIgnored() {
        when(pickup.whenNotifiedByWebhook()).thenReturn(false).thenReturn(true);
        webhook.notifyOfAScoringRun("event-1");

        webhook.notifyOfAScoringRun("event-1");

        verify(pickup, times(2)).whenNotifiedByWebhook();
    }

    @Test
    void notifyOfAScoringRun_beforeAnything_thenNothingHasBeenAccepted() {
        verify(pickup, never()).whenNotifiedByWebhook();
    }
}
