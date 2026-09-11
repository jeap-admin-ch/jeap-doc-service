package ch.admin.bit.jeap.doc.domain.custom;

import java.nio.file.Path;

/**
 * Writes the uploaded pages of one chapter into the tree a build is generating.
 * <p>
 * Handed to a structure template, which calls it while it walks its own chapters: the template creates the
 * chapter folder, because only a template may name a chapter, and this writes what was uploaded into it. One
 * pass, and the front matter and the ordering are decided in one place for every methodology rather than once
 * per template.
 * <p>
 * It is a collaborator of one build, not a bean and not a driven port.
 */
public interface CustomPages {

    /**
     * Writes the pages and assets of one chapter of one subject.
     *
     * @param subject          whose documentation to write
     * @param chapterFolder    the chapter, as {@link CustomDocumentation#chapterFoldersOf} answers it
     * @param chapterDirectory the directory of that chapter, which already exists
     * @return how many pages were written, assets not counted
     */
    int writeInto(CustomSubject subject, String chapterFolder, Path chapterDirectory);
}
