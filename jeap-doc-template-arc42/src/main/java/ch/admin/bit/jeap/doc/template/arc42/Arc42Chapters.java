package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.template.StructureChapter;

import java.util.List;

/**
 * The twelve chapters of arc42, and the one place their folder names are written.
 * <p>
 * These names are what an upload has to carry, what the structural validation checks, and what the generator
 * writes. Chapter 9 is written out as {@code 9-architecture-decision-records}: it is the only chapter whose
 * short form is an acronym, and a path segment is read by people who have not met it before.
 * <p>
 * <b>arc42</b> (<a href="https://arc42.org">arc42.org</a>) is by Gernot Starke and Peter Hruschka, licensed
 * under <a href="https://creativecommons.org/licenses/by-sa/4.0/">CC BY-SA 4.0</a>. The section names are used
 * in English, the folders carry a number prefix and a shortened slug, and this service implements the section
 * structure rather than shipping arc42's own text. See {@code NOTICE} in the root of this repository.
 */
public final class Arc42Chapters {

    public static final StructureChapter INTRODUCTION =
            StructureChapter.numbered(1, "1-intro", "Introduction and Goals");
    public static final StructureChapter CONSTRAINTS =
            StructureChapter.numbered(2, "2-constraints", "Architecture Constraints");
    public static final StructureChapter CONTEXT_AND_SCOPE =
            StructureChapter.numbered(3, "3-context-and-scope", "Context and Scope");
    public static final StructureChapter SOLUTION_STRATEGY =
            StructureChapter.numbered(4, "4-solution-strategy", "Solution Strategy");
    public static final StructureChapter BUILDING_BLOCK_VIEW =
            StructureChapter.numbered(5, "5-building-block-view", "Building Block View");
    public static final StructureChapter RUNTIME_VIEW =
            StructureChapter.numbered(6, "6-runtime-view", "Runtime View");
    public static final StructureChapter DEPLOYMENT_VIEW =
            StructureChapter.numbered(7, "7-deployment-view", "Deployment View");
    public static final StructureChapter CROSSCUTTING_CONCEPTS =
            StructureChapter.numbered(8, "8-crosscutting-concepts", "Cross-cutting Concepts");
    public static final StructureChapter ARCHITECTURE_DECISIONS =
            StructureChapter.numbered(9, "9-architecture-decision-records", "Architecture Decisions");
    public static final StructureChapter QUALITY_REQUIREMENTS =
            StructureChapter.numbered(10, "10-quality-requirements", "Quality Requirements");
    public static final StructureChapter RISKS =
            StructureChapter.numbered(11, "11-risks", "Risks and Technical Debt");
    public static final StructureChapter GLOSSARY =
            StructureChapter.numbered(12, "12-glossary", "Glossary");

    /** All twelve, in order. */
    public static final List<StructureChapter> ALL = List.of(
            INTRODUCTION, CONSTRAINTS, CONTEXT_AND_SCOPE, SOLUTION_STRATEGY, BUILDING_BLOCK_VIEW, RUNTIME_VIEW,
            DEPLOYMENT_VIEW, CROSSCUTTING_CONCEPTS, ARCHITECTURE_DECISIONS, QUALITY_REQUIREMENTS, RISKS,
            GLOSSARY);

    /** What each chapter answers, for the landing page of the structure. */
    static String summaryOf(StructureChapter chapter) {
        return switch (chapter.number()) {
            case 1 -> "What the system is, and who is responsible for it.";
            case 2 -> "What was fixed before the first design decision.";
            case 3 -> "Who the system talks to, and about what.";
            case 4 -> "The decisions that shape everything below.";
            case 5 -> "How the system is decomposed, and what flows between the parts.";
            case 6 -> "How the system behaves while it runs.";
            case 7 -> "Where it runs.";
            case 8 -> "The concepts that apply everywhere rather than in one place.";
            case 9 -> "Why the architecture is the way it is.";
            case 10 -> "The quality goals, as measurable scenarios.";
            case 11 -> "What is known to be risky, and what is owed.";
            case 12 -> "The terms, and what they mean here.";
            default -> "";
        };
    }

    private Arc42Chapters() {
    }
}
