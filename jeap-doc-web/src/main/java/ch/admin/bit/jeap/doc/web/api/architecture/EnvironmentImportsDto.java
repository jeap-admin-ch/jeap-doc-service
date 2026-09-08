package ch.admin.bit.jeap.doc.web.api.architecture;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One environment's architecture repository, and what its imports have been doing.
 *
 * @param environment the environment identifier, which is what an import is asked for by
 * @param sourceUrl   the architecture repository this environment is read from, empty where the instance
 *                    knows no URL for it
 * @param imports     one entry per kind, the model first
 */
@Schema(description = "An environment's architecture repository and the state of its imports")
record EnvironmentImportsDto(String environment, String sourceUrl, List<ImportStateDto> imports) {
}
