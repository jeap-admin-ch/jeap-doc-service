package ch.admin.bit.jeap.doc.web.api.upload.docs;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The path tree to validate.
 * <p>
 * Every path is relative to the root of the documentation set - the directory the doc workflow's {@code path}
 * points at - written with {@code /} and exactly as it would be entered in the ZIP. <b>Directories are not
 * listed</b>: one that holds no file publishes nothing, and one that does is implied by its files.
 *
 * @param paths the files of the set
 */
@Schema(description = "The paths of a documentation set, relative to its root")
record PathTreeDto(
        @Schema(description = "The files of the documentation set, relative to its root, separated by '/'",
                example = "[\"1-intro/goals.md\", \"5-building-block-view/design.md\"]")
        List<String> paths) {

    List<String> pathsOrEmpty() {
        return paths == null ? List.of() : paths;
    }
}
