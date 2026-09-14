package ch.admin.bit.jeap.doc.domain.upload.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a published microsite may be made of. It follows no structure template, so the rule is the domain's
 * and not a copy per template - and it is a denylist: a build emits file types nobody listed in advance.
 */
class MicrositeRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"exe", "cmd", "bat", "msi", "ps1", "vbs", "sh", "bash", "jar", "dll", "dmg",
            "apk", "deb"})
    void whatAWorkstationRunsIsRefused(String extension) {
        assertThat(MicrositeRules.allows(extension, MicrositeRules.REFUSED_BY_DEFAULT)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXE", "Sh", "JaR"})
    void theExtensionIsComparedWithoutItsCase(String extension) {
        assertThat(MicrositeRules.allows(extension, MicrositeRules.REFUSED_BY_DEFAULT)).isFalse();
    }

    /**
     * <b>The file types a real build emits are carried.</b> {@code md} and {@code csv} among them: a
     * generated report links to its own data, and refusing it would refuse the upload rather than protect
     * anybody - what a microsite may do is bounded by its sandbox, not by this list.
     */
    @ParameterizedTest
    @ValueSource(strings = {"html", "css", "js", "map", "json", "png", "svg", "woff2", "pdf", "md", "csv",
            "eot", "wasm"})
    void whatABuildEmitsIsCarried(String extension) {
        assertThat(MicrositeRules.allows(extension, MicrositeRules.REFUSED_BY_DEFAULT)).isTrue();
    }

    /** A LICENSE or a CNAME has no extension, and a build writes both. */
    @Test
    void aFileWithNoExtensionIsCarried() {
        assertThat(MicrositeRules.allows(null, MicrositeRules.REFUSED_BY_DEFAULT)).isTrue();
        assertThat(MicrositeRules.allows("", MicrositeRules.REFUSED_BY_DEFAULT)).isTrue();
    }

    /** An instance decides the list, so what it configures is what applies - not the default beside it. */
    @Test
    void theRefusedListIsTheOneTheCallerPassesIn() {
        assertThat(MicrositeRules.allows("exe", java.util.Set.of())).isTrue();
        assertThat(MicrositeRules.allows("js", java.util.Set.of("js"))).isFalse();
    }

    @Test
    void theEntryPointIsIndexHtml() {
        assertThat(MicrositeRules.ENTRY_POINT).isEqualTo("index.html");
    }
}
