import React, {useLayoutEffect, useRef} from 'react';
import styles from './styles.module.css';
import {pinPath} from './appearance.mjs';

const iconPaths = {
    documentation: 'M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M14 3v6h6 M8 13h8 M8 17h5',
    expand: 'M8 3H3v5 M3 3l6 6 M16 21h5v-5 M21 21l-6-6',
    collapse: 'M3 9h6V3 M9 9L3 3 M21 15h-6v6 M15 15l6 6',
    pin: pinPath,
    unpin: 'M3 3l18 18 M9 3h6 M14 3v6l4 4v2h-3 M9 9l-3 4v2h5 M12 17v4',
    hide: 'M3 3l18 18 M10 5a11 11 0 0 1 11 7 17 17 0 0 1-4 5 M6 6a17 17 0 0 0-3 6c4 8 12 8 15 5 M10 10a3 3 0 0 0 4 4',
    outgoingHide: 'M3 12h11 M10 8l4 4-4 4 M18 8l4 8 M22 8l-4 8',
    connectedHide: 'M4 12h16 M8 8l-4 4 4 4 M16 8l4 4-4 4 M10 3l4 4 M14 3l-4 4',
    focus: 'M8 3H3v5 M16 3h5v5 M21 16v5h-5 M8 21H3v-5 M9 12h6 M12 9v6',
    incoming: 'M21 12H3 M9 6l-6 6 6 6',
    outgoing: 'M3 12h18 M15 6l6 6-6 6',
    paths: 'M4 5h5l6 14h5 M4 19h5l6-14h5 M17 2l3 3-3 3 M17 16l3 3-3 3',
};

function MenuIcon({name}) {
    return <svg className={styles.menuIcon} viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
        <path d={iconPaths[name]}/>
    </svg>;
}

function MenuSection({label, children}) {
    return <><div role="separator" className={styles.menuSeparator}/>
        <div role="group" aria-label={label}>
            <div className={styles.menuCategory} aria-hidden="true">{label}</div>
            {children}
        </div>
    </>;
}

export default function NodeMenu({menu, href, linkLabel, canExpand, canCollapse, pinned, groupPinned, onExplore, onAction, onChange, onClose}) {
    const ref = useRef(null);
    useLayoutEffect(() => {
        const element = ref.current, parent = element.parentElement;
        element.style.left = Math.max(8, Math.min(menu.x, parent.clientWidth - element.offsetWidth - 8)) + 'px';
        element.style.top = Math.max(8, Math.min(menu.y, parent.clientHeight - element.offsetHeight - 8)) + 'px';
        (element.querySelector('[role="menuitem"]:not(:disabled)') ?? element).focus();
    }, [menu]);
    const onKeyDown = event => {
        if (event.key === 'Escape') { event.preventDefault(); onClose(); }
        if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
            event.preventDefault();
            const buttons = [...ref.current.querySelectorAll('[role="menuitem"]:not(:disabled)')];
            const current = buttons.indexOf(document.activeElement);
            const index = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
                : (current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
            buttons[index]?.focus();
        }
    };
    return <div ref={ref} role="menu" tabIndex={-1} aria-label={menu.label} className={styles.groupMenu}
        onKeyDown={onKeyDown} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) onClose(false); }}>
        <div className={styles.menuTitle}>{menu.label}</div>
        {(href || !menu.group) && <MenuSection label="Documentation">
            {href ? <a role="menuitem" href={href}><MenuIcon name="documentation"/>{linkLabel}</a>
                : <button type="button" role="menuitem" disabled><MenuIcon name="documentation"/>Documentation unavailable</button>}
        </MenuSection>}
        <MenuSection label="Visibility">
            {menu.group && <button type="button" role="menuitem" disabled={!canCollapse && !canExpand}
                onClick={() => onChange(!canCollapse)}>
                <MenuIcon name={canCollapse ? 'collapse' : 'expand'}/>{canCollapse ? 'Collapse group' : 'Expand group'}
            </button>}
            <button type="button" role="menuitem" onClick={() => onAction('pin')}>
                <MenuIcon name={pinned ? 'unpin' : 'pin'}/>{pinned ? 'Unpin' : 'Pin'}</button>
            <button type="button" role="menuitem" onClick={() => onAction('hide')}>
                <MenuIcon name="hide"/>{menu.kind === 'group' ? groupPinned ? 'Unpin members and hide group' : 'Hide group'
                    : pinned ? 'Unpin and hide' : 'Hide'}</button>
            <button type="button" role="menuitem" onClick={() => onAction('outgoing')}>
                <MenuIcon name="outgoingHide"/>Hide outgoing paths</button>
            <button type="button" role="menuitem" onClick={() => onAction('connected')}>
                <MenuIcon name="connectedHide"/>Hide connected subgraph</button>
        </MenuSection>
        <MenuSection label="Exploration">
            <button type="button" role="menuitem" onClick={() => onAction('focus')}><MenuIcon name="focus"/>Focus</button>
            <button type="button" role="menuitem" onClick={() => onExplore('incoming')}>
                <MenuIcon name="incoming"/>Expand incoming paths…</button>
            <button type="button" role="menuitem" onClick={() => onExplore('outgoing')}>
                <MenuIcon name="outgoing"/>Expand outgoing paths…</button>

        </MenuSection>
    </div>;
}
