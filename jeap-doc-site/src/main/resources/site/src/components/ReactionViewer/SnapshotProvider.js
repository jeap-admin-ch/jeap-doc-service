import React, {createContext, useMemo} from 'react';
import {useLocation} from '@docusaurus/router';

export const SnapshotRequests = createContext(null);

/** Share snapshots within one page visit, including variants mounted later. */
export default function SnapshotProvider({children}) {
    const {pathname} = useLocation();
    const requests = useMemo(() => new Map(), [pathname]);
    return <SnapshotRequests.Provider value={requests}>{children}</SnapshotRequests.Provider>;
}
