package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.custom.CustomPages;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The chapters a team filled itself, in a tree this template owns.
 * <p>
 * <b>Only a template may name a chapter</b>, which is why this is here rather than in the site generator: a
 * chapter folder needs the label and the position of a {@link StructureChapter}, and those are the template's.
 * What goes into the folder is the writer's - the front matter and the ordering of an uploaded page are one
 * implementation for every methodology.
 */
final class Arc42CustomChapters {

    private Arc42CustomChapters() {
    }

    /**
     * The chapters of this template that the given folders name, in the order the navigation shows them.
     * <p>
     * A folder this template does not have is left out rather than refused: the upload API refuses such a
     * set, and a set stored before a rule existed costs one page and not a build.
     */
    static List<StructureChapter> of(StructureTemplate template, Set<String> folders) {
        List<StructureChapter> chapters = new ArrayList<>();
        for (StructureChapter chapter : template.orderedChapters()) {
            if (folders.contains(chapter.folder())) {
                chapters.add(chapter);
            }
        }
        return List.copyOf(chapters);
    }

    /** Both lists as one, in the template's order and without a chapter appearing twice. */
    static List<StructureChapter> merged(StructureTemplate template, List<StructureChapter> generated,
                                         List<StructureChapter> uploaded) {
        Set<StructureChapter> both = new LinkedHashSet<>(generated);
        both.addAll(uploaded);
        return template.orderedChapters().stream().filter(both::contains).toList();
    }

    /**
     * Writes the uploaded pages of one subject into the chapters they belong to, creating each chapter folder
     * with its label and its place in the navigation.
     * <p>
     * A generated chapter is written into as well: the generator owns a chapter's index page and an upload
     * owns the named pages beside it, so a team writes into chapter 1 whether or not the service generates
     * one.
     */
    static void write(StructureTemplate template, CustomPages pages, CustomSubject subject,
                      GenerationContext context, Path structureDirectory,
                      List<StructureChapter> chapters, boolean fromTheModel) throws IOException {
        for (StructureChapter chapter : chapters) {
            Path directory = Arc42Pages.chapterDirectory(template, structureDirectory, chapter);
            pages.writeInto(subject, chapter.folder(), directory);
            // Whatever was written, and even when nothing was. The landing page listing this chapter is
            // written before this runs, so it has already promised the chapter exists - and every page of a
            // set can be dropped, by a name the template generates or by a file its bundle no longer holds.
            // A link to a chapter with no landing page fails the whole site build.
            Arc42ChapterIndexPage.writeUnlessGenerated(chapter, context, directory, fromTheModel);
        }
    }
}
