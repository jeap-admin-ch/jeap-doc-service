package ch.admin.bit.jeap.doc.web.api.docs;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Removing the documentation a team uploaded: one set, or a whole subject.
 */
class CustomDocsRemovalIT extends DocServiceIntegrationTestBase {

    /** Its own system, because this class removes what it wrote and shares a database with every other. */
    private static final String SYSTEM = "removal-system";
    private static final String COMPONENT = "removal-component";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomDocumentationRepository documentation;

    @Autowired
    private DocumentationBuildRequestRepository requests;

    private static CustomSetKey key(SubjectKind kind, String name, String template) {
        return new CustomSetKey("default", kind, SYSTEM, name, SourceFormat.MARKDOWN, template, null, null);
    }

    private void given(CustomSetKey key, long revision) {
        documentation.replace(new CustomSet(null, key, revision,
                "current/docs/removal/%d/bundle.zip".formatted(revision), "abc", 10,
                new CustomProvenance("docs", "main", "cafe", Instant.EPOCH, null, Instant.EPOCH),
                List.of(new CustomPage("1-intro", "goals.md", "Goals", 1, false))));
    }

    private MockHttpServletRequestBuilder removalOfTheSet(String type, String name, String template) {
        MockHttpServletRequestBuilder request = delete(DocsPaths.SETS)
                .param("type", type)
                .param("system", SYSTEM)
                .param("template", template)
                .param("source-format", "markdown");
        if ("component-docs".equals(type)) {
            request.param("component", name);
        } else if ("library-docs".equals(type)) {
            request.param("library", name);
        }
        return request.with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write"))));
    }

    @Test
    void removeSet_thenTheSetAndItsPagesAreGone() throws Exception {
        given(key(SubjectKind.SYSTEM, null, "arc42"), 1);

        mockMvc.perform(removalOfTheSet("system-docs", null, "arc42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setsRemoved").value(1))
                .andExpect(jsonPath("$.buildAsked").value(true));

        assertThat(documentation.find(key(SubjectKind.SYSTEM, null, "arc42"))).isEmpty();
    }

    @Test
    void removeSet_thenTheOtherTemplatesSetStands() throws Exception {
        given(key(SubjectKind.SYSTEM, null, "arc42"), 2);
        given(key(SubjectKind.SYSTEM, null, "something-else"), 3);

        mockMvc.perform(removalOfTheSet("system-docs", null, "arc42")).andExpect(status().isOk());

        assertThat(documentation.find(key(SubjectKind.SYSTEM, null, "arc42"))).isEmpty();
        assertThat(documentation.find(key(SubjectKind.SYSTEM, null, "something-else")))
                .describedAs("this is how a team gets rid of the set a template switch left behind")
                .isPresent();
    }

    @Test
    void removeSet_whenThereIsNoSuchSet_thenNotFound() throws Exception {
        mockMvc.perform(removalOfTheSet("component-docs", "never-documented", "arc42"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeSet_thenABuildOfThePartIsAskedFor() throws Exception {
        given(key(SubjectKind.COMPONENT, COMPONENT, "arc42"), 4);

        mockMvc.perform(removalOfTheSet("component-docs", COMPONENT, "arc42")).andExpect(status().isOk());

        assertThat(requests.pending()).extracting(BuildRequest::part).extracting(PartKey::part)
                .describedAs("nothing takes a page off a published part, so a build has to be asked for")
                .contains("system-" + SYSTEM);
    }

    @Test
    void removeSubject_takesEverySetOfIt() throws Exception {
        given(key(SubjectKind.COMPONENT, "many-sets", "arc42"), 5);
        given(key(SubjectKind.COMPONENT, "many-sets", "something-else"), 6);
        given(key(SubjectKind.SYSTEM, null, "arc42"), 7);

        mockMvc.perform(delete(DocsPaths.SUBJECTS)
                        .param("type", "component-docs")
                        .param("system", SYSTEM)
                        .param("component", "many-sets")
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setsRemoved").value(2));

        assertThat(documentation.of("default", SYSTEM).documentedComponents())
                .extracting(CustomSubject::name).doesNotContain("many-sets");
        assertThat(documentation.find(key(SubjectKind.SYSTEM, null, "arc42")))
                .describedAs("the system's own documentation is another subject").isPresent();
    }

    /**
     * A subject that was documented and is not any more is still a subject: nothing is inferred from the
     * absence of a set, so removing it is a call of its own and answers what it found.
     */
    @Test
    void removeSubject_whenNothingIsDocumentedForIt_thenNoneRemoved() throws Exception {
        mockMvc.perform(delete(DocsPaths.SUBJECTS)
                        .param("type", "library-docs")
                        .param("system", SYSTEM)
                        .param("library", "never-documented")
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setsRemoved").value(0));
    }

    @Test
    void removeSet_withoutTheWriteRoleOfThatSystem_isForbidden() throws Exception {
        given(key(SubjectKind.SYSTEM, null, "arc42"), 8);

        mockMvc.perform(delete(DocsPaths.SETS)
                        .param("type", "system-docs")
                        .param("system", SYSTEM)
                        .param("template", "arc42")
                        .param("source-format", "markdown")
                        .with(authentication(tokenWithRoles(uploadsRole("another-system", "write")))))
                .andExpect(status().isForbidden());

        assertThat(documentation.find(key(SubjectKind.SYSTEM, null, "arc42"))).isPresent();
    }

    @Test
    void removeSet_withoutAToken_isUnauthorized() throws Exception {
        mockMvc.perform(delete(DocsPaths.SETS)
                        .param("type", "system-docs")
                        .param("system", SYSTEM)
                        .param("template", "arc42")
                        .param("source-format", "markdown"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * <b>The endpoint that wipes a whole subject, and it had no negative test.</b> The role is on each method
     * separately, on package-private methods of a package-private class, so a dropped or mis-scoped
     * annotation on this one would have been caught by nothing.
     */
    @Test
    void removeSubject_withoutTheWriteRoleOfThatSystem_isForbidden() throws Exception {
        given(key(SubjectKind.COMPONENT, "guarded", "arc42"), 20);

        mockMvc.perform(delete(DocsPaths.SUBJECTS)
                        .param("type", "component-docs")
                        .param("system", SYSTEM)
                        .param("component", "guarded")
                        .with(authentication(tokenWithRoles(uploadsRole("another-system", "write")))))
                .andExpect(status().isForbidden());

        assertThat(documentation.find(key(SubjectKind.COMPONENT, "guarded", "arc42")))
                .describedAs("and it took nothing away")
                .isPresent();
    }

    @Test
    void removeSubject_withoutAToken_isUnauthorized() throws Exception {
        mockMvc.perform(delete(DocsPaths.SUBJECTS)
                        .param("type", "component-docs")
                        .param("system", SYSTEM)
                        .param("component", "guarded"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * <b>A typo has to be answered as a typo.</b> These endpoints speak the doc workflow's vocabulary and are
     * called by a workflow, which gets it wrong in exactly the ways an upload does - and without an advice of
     * their own every one of them surfaced as a 500 with no {@code code} in it, reading as a doc service that
     * is broken rather than as a configuration that is.
     */
    @Test
    void removeSet_withAMisspeltType_isAnsweredWithTheProblemDocument() throws Exception {
        mockMvc.perform(delete(DocsPaths.SETS)
                        .param("type", "componet-docs")
                        .param("system", SYSTEM)
                        .param("template", "arc42")
                        .param("source-format", "markdown")
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    @Test
    void removeSet_withAMisspeltSourceFormat_isAnsweredWithTheProblemDocument() throws Exception {
        mockMvc.perform(delete(DocsPaths.SETS)
                        .param("type", "system-docs")
                        .param("system", SYSTEM)
                        .param("template", "arc42")
                        .param("source-format", "md")
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    /** A location belongs to a microsite, and Markdown documents are in chapters rather than a section. */
    @Test
    void removeSet_withALocationOnMarkdown_isAnsweredWithTheProblemDocument() throws Exception {
        mockMvc.perform(delete(DocsPaths.SETS)
                        .param("type", "system-docs")
                        .param("system", SYSTEM)
                        .param("template", "arc42")
                        .param("source-format", "markdown")
                        .param("location", "6-runtime-view")
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    /** Component documentation names a component; the rules are the upload's, in one place. */
    @Test
    void removeSubject_withoutTheComponentItsTypeNeeds_isAnsweredWithTheProblemDocument() throws Exception {
        mockMvc.perform(delete(DocsPaths.SUBJECTS)
                        .param("type", "component-docs")
                        .param("system", SYSTEM)
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    @Test
    void removeSet_withAnUnknownSite_isAnsweredWithTheProblemDocument() throws Exception {
        mockMvc.perform(delete(DocsPaths.SETS)
                        .param("site", "no-such-site")
                        .param("type", "system-docs")
                        .param("system", SYSTEM)
                        .param("template", "arc42")
                        .param("source-format", "markdown")
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SITE"));
    }

    /**
     * <b>An unknown parameter deletes the wrong thing.</b> {@code sitte=governance} would fall through to the
     * default site and take that site's set away - so the check that exists because a typo in a workflow must
     * fail loudly guards these endpoints too.
     */
    @Test
    void removeSet_withAMisspeltSite_isRejectedRatherThanAppliedToTheDefaultSite() throws Exception {
        given(key(SubjectKind.SYSTEM, null, "arc42"), 21);

        mockMvc.perform(delete(DocsPaths.SETS)
                        .param("sitte", "governance")
                        .param("type", "system-docs")
                        .param("system", SYSTEM)
                        .param("template", "arc42")
                        .param("source-format", "markdown")
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PARAMETER"));

        assertThat(documentation.find(key(SubjectKind.SYSTEM, null, "arc42")))
                .describedAs("and nothing of the default site was removed")
                .isPresent();
    }

    /**
     * <b>Documentation nobody's pipeline can reach any more still has to be removable.</b> A repository that
     * has been archived or a team that has been disbanded leaves its set current with no pipeline holding the
     * write role of that system, so an administrator of the sites is accepted beside it.
     */
    @Test
    void removeSet_withTheSitesAdminRole_isAllowed() throws Exception {
        given(key(SubjectKind.SYSTEM, null, "arc42"), 30);

        mockMvc.perform(delete(DocsPaths.SETS)
                        .param("type", "system-docs")
                        .param("system", SYSTEM)
                        .param("template", "arc42")
                        .param("source-format", "markdown")
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setsRemoved").value(1));

        assertThat(documentation.find(key(SubjectKind.SYSTEM, null, "arc42"))).isEmpty();
    }

    @Test
    void removeSubject_withTheSitesAdminRole_isAllowed() throws Exception {
        given(key(SubjectKind.COMPONENT, "by-the-admin", "arc42"), 31);

        mockMvc.perform(delete(DocsPaths.SUBJECTS)
                        .param("type", "component-docs")
                        .param("system", SYSTEM)
                        .param("component", "by-the-admin")
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setsRemoved").value(1));
    }

    /**
     * Everything documented for one system, in one call: its own documentation and that of all its components
     * and libraries. One call rather than one per subject, because an upload landing halfway through a
     * sequence of removals would leave the system half documented.
     */
    @Test
    void removeSystem_takesEverythingDocumentedForIt() throws Exception {
        given(key(SubjectKind.SYSTEM, null, "arc42"), 32);
        given(key(SubjectKind.COMPONENT, "one-component", "arc42"), 33);
        given(key(SubjectKind.COMPONENT, "one-component", "something-else"), 34);
        given(key(SubjectKind.LIBRARY, "one-library", "arc42"), 35);

        mockMvc.perform(delete(DocsPaths.SYSTEMS)
                        .param("system", SYSTEM)
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setsRemoved").value(4))
                .andExpect(jsonPath("$.buildAsked").value(true));

        assertThat(documentation.of("default", SYSTEM).isEmpty())
                .describedAs("nothing of that system is documented any more")
                .isTrue();
    }

    /** A system nothing was ever documented for is not an error: it answers what it found, which is none. */
    @Test
    void removeSystem_whenNothingIsDocumentedForIt_thenNoneRemoved() throws Exception {
        mockMvc.perform(delete(DocsPaths.SYSTEMS)
                        .param("system", "never-documented-system")
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setsRemoved").value(0));
    }

    /**
     * <b>The write role of the system is not enough here.</b> Removing every set of a system is the one
     * removal that is not a pipeline's own business: it takes away the documentation of every component and
     * library of that system, which several teams may own.
     */
    @Test
    void removeSystem_withOnlyTheWriteRoleOfThatSystem_isForbidden() throws Exception {
        given(key(SubjectKind.SYSTEM, null, "arc42"), 36);

        mockMvc.perform(delete(DocsPaths.SYSTEMS)
                        .param("system", SYSTEM)
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isForbidden());

        assertThat(documentation.find(key(SubjectKind.SYSTEM, null, "arc42"))).isPresent();
    }

    @Test
    void removeSystem_withoutAToken_isUnauthorized() throws Exception {
        mockMvc.perform(delete(DocsPaths.SYSTEMS).param("system", SYSTEM))
                .andExpect(status().isUnauthorized());
    }

    /** It carries no placement to check it, so the slug rule is checked where the parameter is read. */
    @Test
    void removeSystem_withASystemThatIsNotASlug_isAnsweredWithTheProblemDocument() throws Exception {
        mockMvc.perform(delete(DocsPaths.SYSTEMS)
                        .param("system", "Not A Slug")
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    @Test
    void removeSystem_withoutTheSystem_isAnsweredWithTheProblemDocument() throws Exception {
        mockMvc.perform(delete(DocsPaths.SYSTEMS)
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    @Test
    void removeSystem_withAnUnknownSite_isAnsweredWithTheProblemDocument() throws Exception {
        mockMvc.perform(delete(DocsPaths.SYSTEMS)
                        .param("site", "no-such-site")
                        .param("system", SYSTEM)
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SITE"));
    }

    /** It narrows nothing, so it takes nothing that would narrow it - not even a type. */
    @Test
    void removeSystem_withAParameterItDoesNotApply_isRejected() throws Exception {
        given(key(SubjectKind.SYSTEM, null, "arc42"), 37);

        mockMvc.perform(delete(DocsPaths.SYSTEMS)
                        .param("system", SYSTEM)
                        .param("type", "system-docs")
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PARAMETER"));

        assertThat(documentation.find(key(SubjectKind.SYSTEM, null, "arc42"))).isPresent();
    }

    /**
     * Removing a subject removes every set of it, whatever their template and format - so a caller passing
     * one in the belief that it narrows the call is misunderstanding it, and is told so rather than losing
     * its microsite.
     */
    @Test
    void removeSubject_withAParameterItDoesNotApply_isRejected() throws Exception {
        given(key(SubjectKind.COMPONENT, "narrowed", "arc42"), 22);

        mockMvc.perform(delete(DocsPaths.SUBJECTS)
                        .param("type", "component-docs")
                        .param("system", SYSTEM)
                        .param("component", "narrowed")
                        .param("template", "arc42")
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PARAMETER"));

        assertThat(documentation.find(key(SubjectKind.COMPONENT, "narrowed", "arc42"))).isPresent();
    }
}
