package ch.admin.bit.jeap.doc.governance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceConfigurationTest {

    /** An instance with no governance site has no OAuth2 client either, so a blank URL must ask for none. */
    @Test
    void whenTheUrlIsBlank_thenTheInstanceStartsWithNoGovernanceServiceConfigured() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GovernanceConfiguration.class))
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("jeap.doc.governance.url=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GovernanceClients.class).isConfigured()).isFalse();
                    assertThat(context.getBean(GovernanceClients.class).url()).isEmpty();
                });
    }
}
