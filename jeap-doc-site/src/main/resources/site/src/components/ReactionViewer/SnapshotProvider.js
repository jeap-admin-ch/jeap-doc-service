import React, {createContext, useEffect, useMemo} from 'react';
import {useLocation} from '@docusaurus/router';
import {createAddressUpdates} from './addressUpdates.mjs';

export const SnapshotRequests = createContext(null);
export const AddressUpdates = createContext(null);

/** Share snapshots within one page visit, including variants mounted later. */
export default function SnapshotProvider({children}) {
    const {pathname} = useLocation();
    const requests = useMemo(() => new Map(), [pathname]);
    const address = useMemo(() => createAddressUpdates(), [pathname]);
    useEffect(() => () => address.dispose(), [address]);
    return <SnapshotRequests.Provider value={requests}>
        <AddressUpdates.Provider value={address}>{children}</AddressUpdates.Provider>
    </SnapshotRequests.Provider>;
}
