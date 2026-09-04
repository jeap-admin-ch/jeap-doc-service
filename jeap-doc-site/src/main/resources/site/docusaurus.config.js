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
 * The environments in the order the search plugin wants them: the main one first, because the plugin skips the
 * site's front page unless the first route base path it is given is the empty one.
 */
const searchOrder = [mainEnvironment, ...environments.filter((environment) => !environment.main)];

/**
 * The documentation options of one environment. Every environment is a docs plugin instance reading its own
 * composed tree: they are peers, and the main environment is served at the site root so its URLs stay stable.
 */
function docsOptions(environment) {
    return {
        path: `content/${environment.id}`,
        routeBasePath: routePrefixOf(environment) || '/',
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
            {label: 'Root Page', to: '/'},
            // Only when the main environment has a systems page - a footer link to one that was not written
            // fails the whole build, since onBrokenLinks is 'throw'.
            ...(site.hasSystems ? [{label: 'Systems', to: '/systems/'}] : []),
            // Unconditional: unlike the systems tree, this page is written into every environment tree of
            // every site, so the link can never point at a page nobody wrote.
            {label: 'About This Documentation', to: '/about-this-documentation/'},
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
        // One link per environment, to its own root. Every environment has a root page, so these never break;
        // and each is an internal `to`, resolved against the environment's route base path.
        title: 'Environments',
        items: [...environments]
            .sort((one, other) => (one.order || 0) - (other.order || 0))
            .map((environment) => ({label: environment.label, to: `${routePrefixOf(environment)}/`})),
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
        // Offline search, indexed at build time and served statically, as in the jEAP documentation.
        [
            '@easyops-cn/docusaurus-search-local',
            {
                hashed: true,
                language: ['en'],
                indexDocs: true,
                indexBlog: false,
                indexPages: false,
                // The `noindex` meta the banner puts on every page of a non-main environment is aimed at web
                // crawlers, not at the site's own search. Without this the plugin treats those pages as
                // unlisted and indexes none of them - the DEV, REF and ABN trees would offer a search bar
                // that finds nothing at all.
                forceIgnoreNoIndex: true,
                // One docs instance per environment, so every route base path has to be listed - and so does
                // every source directory, which is what `hashed: true` hashes the index filename from.
                //
                // The main environment comes first, and that is not cosmetic: the plugin skips the site's
                // front page unless the first entry is the empty route base path, and the front page is a
                // real page here.
                // '/' for the main environment, which the plugin normalises to the empty base path - it
                // rejects an empty string outright, so this is how the site root is expressed.
                docsRouteBasePath: searchOrder.map((environment) => routePrefixOf(environment) || '/'),
                docsDir: searchOrder.map((environment) => `content/${environment.id}`),
                // One index per environment, so a reader searches the tree they are in rather than being
                // offered the same page once per environment. The plugin sorts each page into the bucket of
                // the first path it matches and skips the leftover bucket for it, and the navbar picks the
                // bucket from the URL - so this needs nothing from the reader.
                //
                // The main environment is deliberately not in this list. It is served at the site root, so its
                // pages match none of these paths and fall into the leftover bucket, which is then exactly the
                // main environment. The paths are relative to the base URL and carry no leading slash.
                //
                // The two options this pairs with are left at their defaults on purpose, and each of them
                // would undo this quietly:
                //   useAllContextsWithNoSearchContext would put every page into the leftover bucket as well,
                //     and the main environment would be back to one hit per environment;
                //   hideSearchBarWithNoSearchContext would stop the leftover bucket being written at all, and
                //     the main environment would have no search box.
                searchContextByPaths: searchOrder
                    .filter((environment) => !environment.main)
                    .map((environment) => ({label: environment.label, path: environment.id})),
                highlightSearchTermsOnTargetPage: true,
                searchBarShortcut: true,
                searchBarPosition: 'auto',
            },
        ],
        // One docs instance per non-main environment; the main one is the preset's instance below.
        ...environments
            .filter((environment) => !environment.main)
            .map((environment) => ['@docusaurus/plugin-content-docs', {id: environment.id, ...docsOptions(environment)}]),
    ],

    themes: ['@docusaurus/theme-mermaid'],

    presets: [
        [
            'classic',
            /** @type {import('@docusaurus/preset-classic').Options} */
            ({
                // Docs-only: the documentation tree is the site.
                docs: docsOptions(mainEnvironment),
                blog: false,
                pages: false,
                theme: {
                    customCss: [
                        require.resolve('./src/css/custom.css'),
                        require.resolve(`./src/css/schemes/${colorScheme}.css`),
                    ],
                },
                sitemap: {
                    // Read from the tree rather than from the file's own timestamps: the content is generated,
                    // so a modification time says when the generator ran.
                    lastmod: null,
                    changefreq: null,
                    priority: null,
                    // Only the main environment. Every page of the others carries `noindex`, so submitting
                    // them would spend a crawler's budget on pages it is then told to discard.
                    ignorePatterns: environments
                        .filter((environment) => !environment.main)
                        .map((environment) => `/${environment.id}/**`),
                },
            }),
        ],
    ],

    // Runs on every page and does something on exactly one: the page describing the documentation, where it
    // fills in what the run that produced this site cost. The numbers cannot be generated into the page - see
    // the module - and all the JavaScript of this site lives in the template rather than in generated content.
    clientModules: [require.resolve('./src/clientModules/publicationNumbers.js')],
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
