package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.InvalidUploadException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which status a rejected upload is answered with. Every reason the domain can give has to map to one - a code
 * without a status would fail at the moment it is first raised, which is the moment nobody is watching.
 */
class UploadExceptionHandlerTest {

    private static final String UPLOAD_ID = "8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77";

    private final UploadExceptionHandler handler = new UploadExceptionHandler();

    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void captureTheLog() {
        log = new ListAppender<>();
        log.start();
        Logger logger = (Logger) LoggerFactory.getLogger(UploadExceptionHandler.class);
        logger.setLevel(Level.DEBUG);
        logger.addAppender(log);
    }

    @AfterEach
    void releaseTheLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(UploadExceptionHandler.class);
        logger.detachAppender(log);
        logger.setLevel(null);
    }

    /**
     * What a caller got wrong is logged at warn and never at error: it is nothing the operators of the doc
     * service can act on, while the team whose pipeline sent it has to be able to find it - by the upload id it
     * quotes. Error is reserved for what the operators do have to react to.
     */
    @ParameterizedTest
    @CsvSource({
            "MISSING_PARAMETER, WARN",
            "UNKNOWN_PARAMETER, WARN",
            "INVALID_PARAMETER_VALUE, WARN",
            "CONTENT_LENGTH_MISMATCH, WARN",
            "LENGTH_REQUIRED, WARN",
            "SIZE_LIMIT_EXCEEDED, WARN",
            "UPLOAD_ID_CONFLICT, WARN",
            "UPLOAD_IN_PROGRESS, INFO",
            "STORAGE_FAILED, DEBUG"
    })
    void handleInvalidUpload_thenLoggedAtTheLevelItsCauseDeserves(InvalidUploadException.Code code, String level) {
        handler.handleInvalidUpload(new InvalidUploadException(code, "rejected"), request());

        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo(level);
            assertThat(event.getFormattedMessage()).contains(UPLOAD_ID).contains(code.name());
        });
    }

    /**
     * The rejection is logged before anything checked that the system is a slug, and what the caller sent is
     * already decoded by then - a line break in it must not be able to look like a second log entry.
     */
    @Test
    void handleInvalidUpload_whenTheSystemCarriesALineBreak_thenItCannotForgeALogEntry() {
        MockHttpServletRequest request = request();
        request.setParameter("system", "wvs\nWARN  the upload was fine actually");

        handler.handleInvalidUpload(new InvalidUploadException(
                InvalidUploadException.Code.MISSING_PARAMETER, "rejected"), request);

        assertThat(log.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("\n").contains("wvs_WARN"));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/uploads/docs/" + UPLOAD_ID);
        request.setParameter("system", "wvs");
        return request;
    }

    @ParameterizedTest
    @CsvSource({
            "MISSING_PARAMETER, 400",
            "UNKNOWN_PARAMETER, 400",
            "INVALID_PARAMETER_VALUE, 400",
            "CONTENT_LENGTH_MISMATCH, 400",
            "UPLOAD_IN_PROGRESS, 409",
            "UPLOAD_ID_CONFLICT, 409",
            "LENGTH_REQUIRED, 411",
            "SIZE_LIMIT_EXCEEDED, 413",
            "STORAGE_FAILED, 500"
    })
    void handleInvalidUpload_thenAnsweredWithTheStatusOfTheReason(InvalidUploadException.Code code, int status) {
        ResponseEntity<ProblemDetail> response = handler.handleInvalidUpload(new InvalidUploadException(code, "rejected"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(status);
        assertThat(response.getBody().getProperties()).containsEntry("code", code.name());
        assertThat(response.getBody().getType()).hasToString(UploadExceptionHandler.PROBLEM_TYPE);
        assertThat(response.getBody().getDetail()).isEqualTo("rejected");
    }

    @Test
    void handleInvalidUpload_whenAnotherAttemptIsInFlight_thenTheAnswerSaysWhenToRetry() {
        ResponseEntity<ProblemDetail> response = handler.handleInvalidUpload(
                InvalidUploadException.inProgress("being received", Duration.ofSeconds(90)), request());

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("90");
    }

    @Test
    void handleInvalidUpload_whenWaitingDoesNotHelp_thenNoRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleInvalidUpload(InvalidUploadException.tooLarge(1024), request());

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void handleMissingParameter_thenNamesTheParameter() {
        ProblemDetail problem = handler.handleMissingParameter(
                new MissingServletRequestParameterException("system", "String"), request());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("code", "MISSING_PARAMETER");
        assertThat(problem.getDetail()).contains("system");
    }

    @Test
    void handleTypeMismatch_thenNamesTheParameterAndTheExpectedType() {
        ProblemDetail problem = handler.handleTypeMismatch(new MethodArgumentTypeMismatchException(
                "yesterday", java.time.OffsetDateTime.class, "source-timestamp", null, null), request());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_PARAMETER_VALUE");
        assertThat(problem.getDetail()).contains("source-timestamp").contains("OffsetDateTime");
    }
}
