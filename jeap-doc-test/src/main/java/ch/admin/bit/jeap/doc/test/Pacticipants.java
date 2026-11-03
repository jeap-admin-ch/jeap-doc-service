package ch.admin.bit.jeap.doc.test;

import lombok.experimental.UtilityClass;

/**
 * The names the doc service is known by in the Pact broker.
 * <p>
 * An instance is called what its system and its role are called, so the name is not this module's to know: the
 * instance sets it before the verification starts, the way the other services' instances do.
 */
@UtilityClass
public class Pacticipants {

    public static final String DOC_SERVICE_PROPERTY_NAME = "doc-service";
    public static final String DOC_SERVICE = "${" + DOC_SERVICE_PROPERTY_NAME + "}";

    public static void setDocServicePacticipant(String docServicePacticipantName) {
        System.setProperty(DOC_SERVICE_PROPERTY_NAME, docServicePacticipantName);
    }
}
