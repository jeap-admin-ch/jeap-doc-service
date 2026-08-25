package ch.admin.bit.jeap.doc.web.api.upload;

import ch.admin.bit.jeap.doc.domain.UploadProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

import static ch.admin.bit.jeap.doc.web.api.upload.UploadSwallowSizeEnvironmentPostProcessor.MARGIN;
import static ch.admin.bit.jeap.doc.web.api.upload.UploadSwallowSizeEnvironmentPostProcessor.MAX_SIZE_PROPERTY;
import static ch.admin.bit.jeap.doc.web.api.upload.UploadSwallowSizeEnvironmentPostProcessor.MAX_SWALLOW_SIZE_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much of a rejected upload the container reads before answering is not something an instance should have to
 * know about - it follows the size an upload may have.
 */
class UploadSwallowSizeEnvironmentPostProcessorTest {

    private final UploadSwallowSizeEnvironmentPostProcessor postProcessor =
            new UploadSwallowSizeEnvironmentPostProcessor();

    @Test
    void postProcessEnvironment_whenTheInstanceConfiguresASize_thenTheSwallowSizeFollowsIt() {
        MockEnvironment environment = new MockEnvironment().withProperty(MAX_SIZE_PROPERTY, "20MB");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(MAX_SWALLOW_SIZE_PROPERTY))
                .isEqualTo(Long.toString(DataSize.ofMegabytes(20).toBytes() + MARGIN.toBytes()));
    }

    @Test
    void postProcessEnvironment_whenTheInstanceConfiguresNothing_thenTheSwallowSizeFollowsTheDefault() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(MAX_SWALLOW_SIZE_PROPERTY))
                .isEqualTo(Long.toString(UploadProperties.DEFAULT_MAX_SIZE.toBytes() + MARGIN.toBytes()));
    }

    /**
     * Derived, not dictated: an instance that has a reason to say something else still wins.
     */
    @Test
    void postProcessEnvironment_whenTheInstanceSaysSomethingElse_thenThatIsKept() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(MAX_SIZE_PROPERTY, "20MB")
                .withProperty(MAX_SWALLOW_SIZE_PROPERTY, "-1");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(MAX_SWALLOW_SIZE_PROPERTY)).isEqualTo("-1");
    }
}
