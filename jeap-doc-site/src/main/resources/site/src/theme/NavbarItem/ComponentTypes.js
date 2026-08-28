import ComponentTypes from '@theme-original/NavbarItem/ComponentTypes';
import EnvironmentSwitcher from '@site/src/components/EnvironmentSwitcher';
import EnvNavLink from '@site/src/components/EnvNavLink';

/**
 * The navbar item types this application adds. Documentation links go through `EnvNavLink` so that the navbar
 * keeps the reader inside their environment.
 */
export default {
    ...ComponentTypes,
    'custom-environmentSwitcher': EnvironmentSwitcher,
    'custom-envNavLink': EnvNavLink,
};
