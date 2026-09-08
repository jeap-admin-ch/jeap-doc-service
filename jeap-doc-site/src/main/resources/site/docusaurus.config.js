// @ts-check
/**
 * The Docusaurus application of the jEAP Doc Service.
 *
 * Nothing in this file is written by hand for a particular documentation site. Everything that differs between
 * one site and the next is read from two files the site generator writes into `content/` before the build:
 *
 *   content/site.json          which site this is - title, tagline, colour scheme, logo, url and base url
 *   content/environments.json  the environments of that site, one of which is `main` and one `latest`
 *
 * The site generator owns `content/` and nothing else; this file and everything beside it are copied over the
 * workspace afterwards, so generated content can never replace part of the application.
 */
const fs = require('node:fs');
const path = require('node:path');
const {themes: prismThemes} = require('prism-react-renderer');

const CONTENT_DIR = path.join(__dirname, 'content');

/** Reads one of the two files the generator writes, with a message that says what was expected where. */
function readGenerated(name) {
    const file = path.join(CONTENT_DIR, name);
    if (!fs.existsSync(file)) {
        throw new Error(
            `${name} is missing in ${CONTENT_DIR}. The site generator writes it before the build; when running ` +
            `this application by hand, the fixture under src/main/resources/site/content is what it stands in for.`);
    }
    return JSON.parse(fs.readFileSync(file, 'utf8'));
}

const site = readGenerated('site.json');
const {environments} = readGenerated('environments.json');

const mainEnvironment = environments.find((environment) => environment.main);
if (!mainEnvironment) {
    throw new Error('No environment is marked as the main one; the site generator guarantees exactly one.');
}

/** `/dev`, and the empty string for the environment served at the site root. */
const routePrefixOf = (environment) => (environment.main ? '' : `/${environment.id}`);

/**
 * Which part of the site this build is.
 *
 * A site is published as several Docusaurus builds - a part per system, and a shell part for the site's own
 * pages - and each of them mounts its content where that content belongs in the site's URLs. The generator
 * writes this into `site.json`; the fallback is one part carrying the whole site, which is what `npm start`
 * runs against the fixture.
 */
const part = site.part || {
    id: 'shell',
    shell: true,
    tree: '',
    environments: environments.map((environment) => environment.id),
};

/** The path within an environment's tree that this part carries: '' for a whole tree. */
const partTree = part.tree ? `/${part.tree}` : '';

/**
 * The environments this part actually has content for. The switcher still offers every one of them.
 *
 * Filtered by whether the generator wrote anything, and not only by what the part carries: an environment
 * that reads no architecture model has no tree at all, and a system that is not deployed on a stage has none
 * in that stage's tree. A docs plugin instance pointed at a directory that is not there is a build that fails
 * for a legitimate state of the landscape.
 */
const carriedEnvironments = environments
    .filter((environment) => part.environments.includes(environment.id))
    .filter((environment) => fs.existsSync(path.join(CONTENT_DIR, environment.id + partTree)));
if (carriedEnvironments.length === 0) {
    throw new Error(
        `The part ${part.id} has no content: none of the environments it carries (${part.environments}) has a ` +
        `directory ${partTree || '/'} under ${CONTENT_DIR}. The site generator writes them before the build.`);
}

/**
 * A link to a page of the site that this part may not own.
 *
 * Docusaurus checks every link against the routes of its own build, and with `onBrokenLinks: 'throw'` a link
 * into another part's routes would fail the build. `pathname://` renders a plain anchor and leaves the check,
 * which is what the environment switcher has always done. The shell part owns these pages, so there the links
 * stay checked - and that is where a broken one would be a defect.
 */
function siteLink(label, path) {
    return part.shell
        ? {label, to: path}
        // target: '_self' because `pathname://` is a protocol and Docusaurus reads any protocol as "not this
        // site", adding target="_blank" - so a footer link to another part of the same site opened a new tab.
        // The footer's link item passes an item's own extra keys through to Link, so this is enough there.
        : {label, href: `pathname://${site.baseUrl.replace(/\/$/, '')}${path}`, target: '_self'};
}

/**
 * The way out of a part that carries one subtree of the site: a link back to the systems index of the
 * environment the reader is in.
 *
 * <b>Per environment, and not once for the part.</b> `sidebars.js` is one module shared by every docs
 * instance of a part, so a sidebar built there cannot know which environment a reader is browsing: someone in
 * the dev tree of a system clicked "All systems" and landed in the main environment's index, which is exactly
 * the failure `plugins/remark-env-links` exists to prevent. Here the environment is in scope.
 *
 * Only where that environment has a systems index at all. The shell writes one when the environment's
 * landscape has a system in it, and `pathname://` is outside `onBrokenLinks`, so a link to one nobody wrote
 * is a 404 no build could have caught.
 */
function escapePointOf(environment) {
    if (part.shell || !environment.hasSystems) {
        return [];
    }
    return [{
        type: 'link',
        label: 'All systems',
        href: `pathname://${site.baseUrl.replace(/\/$/, '')}${routePrefixOf(environment)}/systems/`,
    }];
}

/**
 * One sidebar link per system of an environment, for the shell part's own sidebar.
 *
 * A reader landing on the root page sees the site's own pages and a "Systems" category holding nothing but
 * its own index - because **every system is built as a part of its own**, so its pages are in no tree this
 * build can see. The generator therefore names them in `environments.json`, and they are hung here as
 * unchecked links, the way the escape point out of a part is.
 */
function systemLinksOf(environment) {
    return (environment.systems || []).map((system) => ({
        type: 'link',
        label: system.label,
        href: `pathname://${site.baseUrl.replace(/\/$/, '')}${routePrefixOf(environment)}${system.path}`,
    }));
}

/**
 * The sidebar of a part, with the systems listed after the shell's systems index.
 *
 * Only the shell has one. A part that carries a single system has no systems index in its tree at all, and
 * its way out is `escapePointOf`.
 *
 * **Found by the custom property the generator writes into its `_category_.json`**, not by its label - a
 * label is exactly what someone changes. The category holds only its own index page, so what is added to it
 * is everything under it.
 */
function withSystemsListed(environment, items) {
    if (!part.shell) {
        return items;
    }
    const links = systemLinksOf(environment);
    if (links.length === 0) {
        return items;
    }
    return items.map((item) => (item.type === 'category' && item.customProps && item.customProps.systemsIndex
        ? {...item, collapsed: false, items: [...item.items, ...links]}
        : item));
}

/**
 * The documentation options of one environment of this part. Every environment is a docs plugin instance
 * reading its own composed tree: they are peers, and the main environment is served at the site root so its
 * URLs stay stable.
 */
function docsOptions(environment) {
    return {
        id: environment.id,
        path: `content/${environment.id}${partTree}`,
        // Where this part's content belongs in the site's URLs: the environment's own prefix, and below it the
        // subtree the part carries. A part per system is mounted at /dev/systems/orders, and its pages then
        // have the URLs they would have had in a site built whole.
        routeBasePath: `${routePrefixOf(environment)}${partTree}` || '/',
        sidebarPath: require.resolve('./sidebars.js'),
        breadcrumbs: true,
        // The trees are generated, so "last updated" would say when the generator ran rather than when anyone
        // changed anything. Pages carry their own provenance instead.
        showLastUpdateTime: false,
        editUrl: undefined,
        // Before Docusaurus' own remark plugins, not after them. Its `resolveMarkdownLinks` rewrites a relative
        // `./other.md` into the resolved permalink, which already carries the route base path - prefixing that a
        // second time would produce /dev/dev/other and fail the build, since onBrokenLinks is 'throw'.
        beforeDefaultRemarkPlugins: [
            [require('./plugins/remark-env-links'), {prefix: routePrefixOf(environment)}],
        ],
        // The folder layout the generator writes is the information architecture, so the items themselves are
        // the default generator's - what is added is the way out of a part, which needs this environment.
        sidebarItemsGenerator: async ({defaultSidebarItemsGenerator, ...args}) => [
            ...escapePointOf(environment),
            ...withSystemsListed(environment, await defaultSidebarItemsGenerator(args)),
        ],
    };
}

const colorScheme = site.colorScheme || 'jeap';

/**
 * The footer link groups. `to` links are internal and get the site's base URL prepended, so they resolve into
 * the main environment served at the site root; the Sites group crosses base URLs and uses absolute `href`s the
 * generator computed. The Sites group is left out when the generator named no sites (an older fixture).
 */
const footerLinks = [
    {
        title: 'Documentation',
        items: [
            siteLink('Root Page', '/'),
            // Only when the main environment has a systems page - a footer link to one that was not written
            // fails the whole build, since onBrokenLinks is 'throw'.
            ...(site.hasSystems ? [siteLink('Systems', '/systems/')] : []),
            // Unconditional: unlike the systems tree, this page is written into every environment tree of
            // every site, so the link can never point at a page nobody wrote.
            siteLink('About This Documentation', '/about-this-documentation/'),
        ],
    },
    ...(Array.isArray(site.sites) && site.sites.length > 1
        // Shown only when this instance serves more than one site; the list includes the current site on
        // purpose, so a single-site instance would otherwise get a group linking only to itself.
        ? [{
            title: 'Sites',
            items: site.sites.map((each) => ({label: each.title, href: each.url})),
        }]
        : []),
    {
        // One link per environment, to its own root. Every environment has a root page, so these never break -
        // and they are the shell part's pages, so a part that is not the shell links them without the check.
        title: 'Environments',
        items: [...environments]
            .sort((one, other) => (one.order || 0) - (other.order || 0))
            .map((environment) => siteLink(environment.label, `${routePrefixOf(environment)}/`)),
    },
];

/** @type {import('@docusaurus/types').Config} */
const config = {
    title: site.title,
    tagline: site.tagline || undefined,
    favicon: site.favicon || 'img/favicon.svg',

    url: site.url,
    baseUrl: site.baseUrl,

    // Every route is emitted as <route>/index.html. The doc service serves the site from object storage and
    // resolves a directory to its index.html, so the two have to agree.
    trailingSlash: true,

    // A generated site with a dead link is a bug in the generator, and the build is the only place that will
    // ever notice it.
    onBrokenLinks: 'throw',
    onDuplicateRoutes: 'throw',

    // Anchors are not checked: the check does not hold together with the diagram plugin, which rewrites the
    // pages it renders. Left off rather than at 'warn', because a check that cries wolf on every build of a
    // page with a diagram on it is worse than no check - it teaches the reader to skip the build output. Links
    // between pages are still thrown on, and that is the half that costs a reader a dead end.
    onBrokenAnchors: 'ignore',

    // Docusaurus Faster, flag by flag rather than `faster: true`.
    //
    // `v4: true` implies `v4.fasterByDefault`, and Docusaurus then switches on every faster flag that is left
    // undefined - so the list has to stay complete. **A Docusaurus upgrade has to be checked against
    // DEFAULT_FASTER_CONFIG in @docusaurus/core/lib/server/configValidation.js**: a flag added in a later
    // version would arrive switched on, which is how the memory of a build changes without anyone deciding it.
    //
    // What Rspack costs: its memory is native and sits outside the Node heap, so
    // `jeap.doc.build.max-node-memory` (NODE_OPTIONS=--max-old-space-size) bounds the JS side of a build - MDX,
    // the plugin lifecycle, the static generation when it runs in-process - and not the bundle phase. The
    // container's limit is the only bound on that, and the `[site generator memory]` lines the doc service logs
    // while it builds are what a container is sized from.
    future: {
        v4: true,
        faster: {
            // Native, and the work leaves the Node heap: SWC instead of Babel and Terser, Lightning CSS
            // instead of cssnano. The HTML minimizer also strips attribute quotes, which is why every
            // assertion on generated HTML has to tolerate both forms.
            swcJsLoader: true,
            swcJsMinimizer: true,
            swcHtmlMinimizer: true,
            lightningCssMinimizer: true,
            // Compiles each page once for both environments instead of twice. It holds the compiled output
            // for the second consumer, so it is the first flag to turn off if the [PERF] lines show the MDX
            // phase retaining more heap than the container has room for.
            mdxCrossCompilerCache: true,
            // The bundler, and the reason for all of it: several times faster than webpack on a large site.
            rspackBundler: true,
            // Off: it needs ./node_modules/.cache kept between builds, and there is nothing to keep. Every
            // build gets a fresh workspace that is discarded afterwards, and node_modules is a symlink to the
            // image's read-only toolchain, so the cache could not be written even once.
            rspackPersistentCache: false,
            // Off unless an instance asks for it - `jeap.doc.build.ssg-worker-threads`. Each worker thread is
            // its own V8 isolate with its own heap, and --max-old-space-size bounds an isolate rather than the
            // process, so a pool of them multiplies what one build may hold. It needs
            // `v4.removeLegacyPostBuildHeadAttribute`, which `v4: true` above already gives.
            ssgWorkerThreads: site.ssgWorkerThreads === true,
            // Off: a build workspace is a plain directory with no repository in it, and the docs are generated
            // with `showLastUpdateTime: false`. There is no history to read eagerly.
            gitEagerVcs: false,
        },
    },

    i18n: {
        defaultLocale: 'en',
        locales: ['en'],
    },

    markdown: {
        // Every source file is read as CommonMark, whatever its extension. Not 'detect', which is the default
        // and would still compile a `.mdx` file as MDX: MDX is a programming language, and documentation the
        // doc service did not write itself is not trusted with one.
        format: 'md',
        mermaid: true,
        hooks: {
            onBrokenMarkdownLinks: 'throw',
            onBrokenMarkdownImages: 'throw',
        },
    },

    // The generator's own static files - a site's logo and favicon - land in content/static and are copied to
    // the site root like static/. They sit under branding/ there rather than under img/: the copy does not
    // overwrite, so anything named like a file the template already ships would be skipped without a word.
    staticDirectories: ['static', 'content/static'],

    plugins: [
        // Renders ```plantuml and ```dot fences in the reader's browser - no PlantUML server, no images.
        '@matfsw/docusaurus-plantuml-plugin',
        // One docs instance per environment this part carries. All of them are plugin instances and none is
        // the preset's: a part may carry any set of environments, and the preset's instance would be a
        // special case among them that has to be picked and named.
        ...carriedEnvironments
            .map((environment) => ['@docusaurus/plugin-content-docs', docsOptions(environment)]),
    ],

    themes: ['@docusaurus/theme-mermaid'],

    presets: [
        [
            'classic',
            /** @type {import('@docusaurus/preset-classic').Options} */
            ({
                // Docs-only, and every docs instance is a plugin of its own above - see the plugins list.
                docs: false,
                blog: false,
                pages: false,
                theme: {
                    customCss: [
                        require.resolve('./src/css/custom.css'),
                        require.resolve(`./src/css/schemes/${colorScheme}.css`),
                    ],
                },
                // No sitemap. A site is built one part at a time and the plugin writes `sitemap.xml` at the
                // root of the build that ran, so every part emitted one of its own and only the shell's was
                // ever served - a sitemap naming the environment root pages, the systems index and the about
                // pages, and none of the documentation. A shell-only sitemap that claims to be the site's is
                // worse than none, and the alternative is a sitemap index the shell would have to write from
                // what the other parts emitted, which is machinery for a crawler hint.
                sitemap: false,
            }),
        ],
    ],

    // Runs on every page and does something on exactly one: the page describing the documentation, where it
    // fills in what the run that produced this site cost. The numbers cannot be generated into the page - see
    // the module - and all the JavaScript of this site lives in the template rather than in generated content.
    clientModules: [
        require.resolve('./src/clientModules/publicationNumbers.js'),
        require.resolve('./src/clientModules/liveStatus.js'),
    ],
    themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
        ({
            colorMode: {
                defaultMode: 'light',
                respectPrefersColorScheme: true,
            },
            docs: {
                sidebar: {
                    hideable: true,
                    autoCollapseCategories: false,
                },
            },
            navbar: {
                title: site.title,
                logo: {
                    alt: site.title,
                    // The site's front page belongs to the shell part; every other part links it unchecked.
                    // With target, or the logo of a system's part opens the front page in a new tab - the
                    // navbar logo takes one of its own, which Logo passes to Link.
                    ...(part.shell ? {} : {href: `pathname://${site.baseUrl}`, target: '_self'}),
                    src: site.logo || 'img/logo.svg',
                    width: 28,
                    height: 28,
                },
                items: [
                    {type: 'custom-environmentSwitcher', position: 'right'},
                ],
            },
            footer: {
                style: 'light',
                links: footerLinks,
                // The generator hands the readable form of the timestamp over ready-made, so that the date
                // format has one definition rather than one here and one on every generated page.
                copyright: site.generatedAtDisplay
                    ? `Generated by the jEAP Doc Service on ${site.generatedAtDisplay}.`
                    : 'Generated by the jEAP Doc Service.',
            },
            prism: {
                theme: prismThemes.github,
                darkTheme: prismThemes.dracula,
                additionalLanguages: ['bash', 'json', 'yaml', 'java', 'sql'],
            },
            mermaid: {
                theme: {light: 'neutral', dark: 'dark'},
            },
            tableOfContents: {
                minHeadingLevel: 2,
                maxHeadingLevel: 3,
            },
        }),
};

module.exports = config;
