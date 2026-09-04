package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * One operation a component provides over REST.
 */
public record RestApiOperation(String method, String path) {

    public String label() {
        return method == null || method.isBlank() ? path : method + " " + path;
    }
}
