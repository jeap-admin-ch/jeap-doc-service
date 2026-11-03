package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the site template's configuration makes of the files the generator writes into {@code content/}.
 * <p>
 * Read by the real Node, because a guard written in JavaScript is worth nothing until an interpreter has run
 * it - Node is a precondition of this build, in the way Docker is for the tests that need a database. The one
 * package the configuration imports is stubbed instead of installed: nothing here builds a site, and the real
 * build is {@link DocusaurusSiteBuilderIT}'s.
 */
class DocusaurusConfigTest {

    @TempDir
    Path workspace;

    private final NodeProcess node = new NodeProcess(new BuildProperties());

    /**
     * The generator always writes {@code part.environments}, so this is about what a failure says rather than
     * about a state a build reaches: every other read of the generated JSON in that file names the field it
     * missed, and a {@code TypeError} out of the middle of a configuration names nothing.
     */
    @Test
    void config_whenThePartNamesNoEnvironments_thenItSaysWhichFieldIsMissing() throws IOException {
        installTemplate();
        writeSite("""
                {"id": "default", "title": "Documentation", "baseUrl": "/",
                 "part": {"id": "shell", "shell": true, "carriesWholeEnvironments": true, "tree": ""}}
                """);
        writeEnvironments("""
                {"environments": [{"id": "prod", "label": "Production", "main": true, "hasSystems": false}]}
                """);

        assertThatThrownBy(() -> node.runAndCapture(workspace, "probe.js"))
                .isInstanceOf(SiteBuildException.class)
                .hasMessageContaining("part.environments")
                // Unguarded it is a TypeError out of the middle of the configuration, which names the field
                // only because Node happens to print the line it threw on.
                .hasMessageNotContaining("TypeError");
    }

    /**
     * The same for the flag that says whether this build wrote the site's own pages. A missing boolean reads as
     * false, so an unguarded one would build a shell whose sidebar lists no system and whose own links all
     * leave the broken-link check - a wrong site with nothing to fail on.
     */
    @Test
    void config_whenThePartDoesNotSayWhatItCarries_thenItSaysWhichFieldIsMissing() throws IOException {
        installTemplate();
        writeSite("""
                {"id": "default", "title": "Documentation", "baseUrl": "/",
                 "part": {"id": "shell", "shell": true, "tree": "", "environments": ["prod"]}}
                """);
        writeEnvironments("""
                {"environments": [{"id": "prod", "label": "Production", "main": true, "hasSystems": false}]}
                """);

        assertThatThrownBy(() -> node.runAndCapture(workspace, "probe.js"))
                .isInstanceOf(SiteBuildException.class)
                .hasMessageContaining("part.carriesWholeEnvironments")
                .hasMessageNotContaining("TypeError");
    }

    /**
     * A sidebar link is built from the label and the path of a system, and it leaves the broken-link check -
     * so an entry missing one of them would publish a dead link that no build could have caught.
     */
    @Test
    void config_whenASystemOfTheLandscapeHasNoPath_thenItIsLeftOutOfTheSidebar() throws IOException {
        installTemplate();
        writeSite("""
                {"id": "default", "title": "Documentation", "baseUrl": "/",
                 "part": {"id": "shell", "shell": true, "carriesWholeEnvironments": true, "tree": "",
                          "environments": ["prod"]}}
                """);
        writeEnvironments("""
                {"environments": [{"id": "prod", "label": "Production", "main": true, "hasSystems": true,
                  "systems": [{"label": "orders", "path": "/systems/orders/"}, {"label": "shipping"}]}]}
                """);

        String sidebar = node.runAndCapture(workspace, "probe.js");

        assertThat(sidebar)
                .contains("pathname:///systems/orders/")
                .describedAs("an entry with no path is left out rather than linked to undefined")
                .doesNotContain("undefined");
    }

    /**
     * A site without environments is one docs instance, mounted at the site root from the one tree - with no
     * switcher in the navbar and no environments in the footer.
     */
    @Test
    void config_whenTheSiteHasNoEnvironments_thenTheOneTreeIsServedAtTheRootWithNothingToSwitch()
            throws IOException {
        installTemplate();
        writeSite("""
                {"id": "handbook", "title": "Handbook", "baseUrl": "/site/handbook/", "perEnvironment": false,
                 "part": {"id": "shell", "shell": true, "carriesWholeEnvironments": true, "tree": "",
                          "environments": []}}
                """);
        writeContent("environments.json", """
                {"environments": []}
                """);
        Files.createDirectories(workspace.resolve("content/" + SiteSources.SINGLE_TREE));
        Files.writeString(workspace.resolve("layout.js"), """
                const config = require('./docusaurus.config.js');
                const docs = config.plugins
                    .filter((plugin) => Array.isArray(plugin))
                    .filter((plugin) => plugin[0] === '@docusaurus/plugin-content-docs')
                    .map((plugin) => ({id: plugin[1].id, path: plugin[1].path, route: plugin[1].routeBasePath}));
                console.log(JSON.stringify({
                    docs,
                    navbar: config.themeConfig.navbar.items.map((item) => item.type),
                    footer: config.themeConfig.footer.links.map((group) => group.title),
                }));
                """, StandardCharsets.UTF_8);

        String layout = node.runAndCapture(workspace, "layout.js");

        assertThat(layout).contains("""
                "docs":[{"id":"tree","path":"content/tree","route":"/"}]""".strip());
        assertThat(layout).contains("""
                "navbar":["search"]""".strip());
        assertThat(layout).contains("""
                "footer":["Documentation"]""".strip());
    }

    /**
     * An instance of several sites: the tabs go left in the header bar, the instance's title beside the logo,
     * and the site's own title stays what the page titles are built from.
     */
    @Test
    void config_whenTheInstanceHasSeveralSites_thenTheTabsAndTheInstanceTitleAreInTheHeaderBar()
            throws IOException {
        installTemplate();
        writeSite("""
                {"id": "deployments", "title": "Deployments", "baseUrl": "/site/deployments/",
                 "navigation": {"title": "Example Documentation", "tabs": [
                   {"label": "Architecture", "url": "/", "current": false},
                   {"label": "Deployments", "url": "/site/deployments/", "current": true}]},
                 "part": {"id": "shell", "shell": true, "carriesWholeEnvironments": true, "tree": "",
                          "environments": ["prod"]}}
                """);
        writeEnvironments("""
                {"environments": [{"id": "prod", "label": "Production", "main": true, "hasSystems": false}]}
                """);
        Files.writeString(workspace.resolve("header.js"), """
                const config = require('./docusaurus.config.js');
                const navbar = config.themeConfig.navbar;
                console.log(JSON.stringify({
                    title: config.title,
                    navbarTitle: navbar.title,
                    tabs: navbar.items.filter((item) => item.type === 'custom-siteTabs')
                        .map((item) => ({position: item.position, labels: item.tabs.map((tab) => tab.label)})),
                }));
                """, StandardCharsets.UTF_8);

        String header = node.runAndCapture(workspace, "header.js");

        assertThat(header).contains("""
                "title":"Deployments","navbarTitle":"Example Documentation\"""".strip());
        assertThat(header).contains("""
                "tabs":[{"position":"left","labels":["Architecture","Deployments"]}]""".strip());
    }

    /** Without the navigation, the header bar is what it always was: the site's title and no tabs. */
    @Test
    void config_whenThereIsNoNavigation_thenTheHeaderBarShowsTheSiteAndNoTabs() throws IOException {
        installTemplate();
        writeSite("""
                {"id": "default", "title": "Documentation", "baseUrl": "/",
                 "part": {"id": "shell", "shell": true, "carriesWholeEnvironments": true, "tree": "",
                          "environments": ["prod"]}}
                """);
        writeEnvironments("""
                {"environments": [{"id": "prod", "label": "Production", "main": true, "hasSystems": false}]}
                """);
        Files.writeString(workspace.resolve("header.js"), """
                const config = require('./docusaurus.config.js');
                console.log(JSON.stringify({navbarTitle: config.themeConfig.navbar.title,
                    types: config.themeConfig.navbar.items.map((item) => item.type)}));
                """, StandardCharsets.UTF_8);

        assertThat(node.runAndCapture(workspace, "header.js")).contains("""
                {"navbarTitle":"Documentation","types":["search","custom-environmentSwitcher"]}""".strip());
    }

    /**
     * The template as a build installs it, a stub of the one package its configuration imports, and a script
     * that loads the configuration and prints the sidebar it built.
     */
    private void installTemplate() throws IOException {
        new SiteTemplate().installInto(workspace);
        Path prismThemes = workspace.resolve("node_modules/prism-react-renderer");
        Files.createDirectories(prismThemes);
        Files.writeString(prismThemes.resolve("package.json"), "{\"main\": \"index.js\"}",
                StandardCharsets.UTF_8);
        Files.writeString(prismThemes.resolve("index.js"),
                "module.exports = {themes: {github: {}, dracula: {}}};", StandardCharsets.UTF_8);
        // The items of one environment's sidebar, with the default generator standing in for Docusaurus':
        // what the configuration adds to them is the systems of the landscape.
        Files.writeString(workspace.resolve("probe.js"), """
                const config = require('./docusaurus.config.js');
                const docs = config.plugins
                    .filter((plugin) => Array.isArray(plugin))
                    .filter((plugin) => plugin[0] === '@docusaurus/plugin-content-docs')
                    .map((plugin) => plugin[1]);
                const category = {type: 'category', customProps: {systemsIndex: true}, items: []};
                Promise.all(docs.map((options) => options.sidebarItemsGenerator({
                    defaultSidebarItemsGenerator: async () => [category],
                }))).then((items) => console.log(JSON.stringify(items)));
                """, StandardCharsets.UTF_8);
    }

    private void writeSite(String json) throws IOException {
        writeContent("site.json", json);
    }

    /** The environments, and the tree of the one they name - a docs instance reads a directory. */
    private void writeEnvironments(String json) throws IOException {
        writeContent("environments.json", json);
        Files.createDirectories(workspace.resolve("content/prod"));
    }

    private void writeContent(String name, String json) throws IOException {
        Path content = workspace.resolve("content");
        Files.createDirectories(content);
        Files.writeString(content.resolve(name), json, StandardCharsets.UTF_8);
    }
}
