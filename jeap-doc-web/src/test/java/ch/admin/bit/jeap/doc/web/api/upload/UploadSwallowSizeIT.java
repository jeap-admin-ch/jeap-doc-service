package ch.admin.bit.jeap.doc.web.api.upload;

import ch.admin.bit.jeap.doc.domain.UploadProperties;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The size the servlet container swallows before it answers a rejected upload is derived from the accepted size
 * while the service starts.
 * <p>
 * Tested against a running service rather than on the post processor alone, because the derivation hangs on a
 * registration in {@code META-INF/spring.factories} that nothing else would notice failing - and if it did fail,
 * a rejected upload would see a closed connection instead of the problem document saying what to fix.
 */
class UploadSwallowSizeIT extends DocServiceIntegrationTestBase {

    @Autowired
    private Environment environment;

    @Autowired
    private UploadProperties uploadProperties;

    @Test
    void theServiceTellsTheContainerToSwallowWhatItAcceptsAndSome() {
        long accepted = uploadProperties.getMaxSize().toBytes();

        assertThat(environment.getProperty(UploadSwallowSizeEnvironmentPostProcessor.MAX_SWALLOW_SIZE_PROPERTY,
                Long.class))
                .isEqualTo(accepted + UploadSwallowSizeEnvironmentPostProcessor.MARGIN.toBytes());
    }
}
