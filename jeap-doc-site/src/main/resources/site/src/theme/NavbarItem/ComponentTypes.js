import ComponentTypes from '@theme-original/NavbarItem/ComponentTypes';
import EnvironmentSwitcher from '@site/src/components/EnvironmentSwitcher';

/**
 * The navbar item types this application adds. The switcher is the only one: everything else in the navbar is a
 * plain link written into `docusaurus.config.js`, and the environment a reader is in comes from the path.
 */
export default {
    ...ComponentTypes,
    'custom-environmentSwitcher': EnvironmentSwitcher,
};
