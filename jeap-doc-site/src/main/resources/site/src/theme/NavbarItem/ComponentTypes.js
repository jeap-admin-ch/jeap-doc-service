import ComponentTypes from '@theme-original/NavbarItem/ComponentTypes';
import EnvironmentSwitcher from '@site/src/components/EnvironmentSwitcher';
import SiteTabs from '@site/src/components/SiteTabs';

/**
 * The navbar item types this application adds: the environment switcher, whose environment comes from the path,
 * and the tabs of the sites of an instance, which `docusaurus.config.js` hands over from `site.json`.
 */
export default {
    ...ComponentTypes,
    'custom-environmentSwitcher': EnvironmentSwitcher,
    'custom-siteTabs': SiteTabs,
};
