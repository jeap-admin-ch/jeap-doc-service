import React, {useEffect, useState} from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {
    TIMEOUT_MS, contextPathOf, governanceUrl, scoreMarker, trendLabel, shortenComment, formatDate, formatDateTime,
    systemRows, componentEntry, complianceLine, sparkline,
} from './model.mjs';
import styles from './styles.module.css';

/**
 * Reads one excerpt once the page has mounted. Until an answer arrives, and on a 204 - nothing to show - any
 * other status, an error or after {@link TIMEOUT_MS}, it hands back null - and the excerpt renders nothing: no frame, no spinner, no message.
 */
function useExcerpt(url) {
    const [excerpt, setExcerpt] = useState(null);
    useEffect(() => {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
        fetch(url, {signal: controller.signal, credentials: 'same-origin', cache: 'no-cache',
            headers: {Accept: 'application/json'}})
            .then(response => (response.status === 200 ? response.json() : null))
            .then(payload => setExcerpt(payload && typeof payload === 'object' ? payload : null))
            .catch(() => setExcerpt(null))
            .finally(() => clearTimeout(timeout));
        return () => {
            clearTimeout(timeout);
            controller.abort();
        };
    }, [url]);
    return excerpt;
}

function Link({href, className, children}) {
    return href ? <a className={className} href={href}>{children}</a> : <span className={className}>{children}</span>;
}

function Frame({title, href, source, children, footer}) {
    return (
        <section className={styles.excerpt} data-pagefind-ignore="all" data-jeap-excerpt={title.toLowerCase()}>
            <header className={styles.header}>
                <h3 className={styles.title}>
                    <Link href={href}>{title}{href ? ' ↗' : ''}</Link>
                </h3>
                <span className={styles.source}><span className={styles.live} aria-hidden="true"/>{source}</span>
            </header>
            {children}
            <footer className={styles.footer}>{footer}</footer>
        </section>
    );
}

function Score({score, trend, history}) {
    const label = trendLabel(trend);
    const points = sparkline(history, 120, 24);
    return (
        <div className={styles.score}>
            <span className={styles.scoreValue}>
                {scoreMarker(score)} {typeof score === 'number' ? `${score} %` : '–'}
            </span>
            {label && <span className={styles.trend}>{label.arrow} {label.text}</span>}
            {points && (
                <span className={styles.history}>
                    <svg width="120" height="24" viewBox="-1 -1 122 26" role="img" aria-label="score of the last 14 days">
                        <polyline points={points} fill="none" className={styles.historyLine}/>
                    </svg>
                    <span className={styles.historyLabel}>last 14 days</span>
                </span>
            )}
        </div>
    );
}

function sourceLine(excerpt) {
    const scored = formatDateTime(excerpt.scoredAt);
    return scored ? `live · scoring run of ${scored}` : 'live';
}

function SystemGovernance({excerpt, entry}) {
    const rows = systemRows(excerpt, entry.components);
    return (
        <Frame title="Governance" href={excerpt.link} source={sourceLine(excerpt)} footer={<>
            <Link className={styles.bottomLink} href={excerpt.link}>{excerpt.system} on the governance site →</Link>
            {excerpt.rulesOverview && <> · <a href={excerpt.rulesOverview}>Rules overview →</a></>}
        </>}>
            <Score score={excerpt.score} trend={excerpt.trend} history={excerpt.history}/>
            {rows.length > 0 && <table className={styles.table}>
                <thead><tr><th>Component</th><th>Score</th><th>Failing rules</th></tr></thead>
                <tbody>
                {rows.map(component => (
                    <tr key={component.name}>
                        <td><Link href={component.link}>{component.name}</Link></td>
                        <td className={styles.nowrap}>
                            {scoreMarker(component.score)} {typeof component.score === 'number' ? component.score : '–'}
                        </td>
                        <td>
                            {component.failingRules.length}
                            {component.failingRules.length > 0 && ' · '}
                            {component.failingRules.map((rule, index) => (
                                <React.Fragment key={rule.ruleId ?? index}>
                                    {index > 0 && ', '}
                                    <Link className={styles.rule} href={rule.link}>{rule.label}</Link>
                                </React.Fragment>
                            ))}
                        </td>
                    </tr>
                ))}
                </tbody>
            </table>}
        </Frame>
    );
}

function ComponentGovernance({excerpt, entry}) {
    const component = componentEntry(excerpt, entry.components[0]);
    if (!component) return null;
    return (
        <Frame title="Governance" href={component.link} source={sourceLine(excerpt)} footer={<>
            <Link className={styles.bottomLink} href={component.link}>{component.name} on the governance site →</Link>
        </>}>
            <Score score={component.score} trend={component.trend} history={component.history}/>
            {component.failingRules.length > 0 && (
                <table className={styles.table}>
                    <thead><tr><th>Failing rule</th><th>State</th><th>Comment</th><th>Since</th></tr></thead>
                    <tbody>
                    {component.failingRules.map((rule, index) => {
                        const comment = shortenComment(rule.comment, rule.details ?? []);
                        return (
                            <tr key={rule.ruleId ?? index}>
                                <td><Link className={styles.rule} href={rule.link}>{rule.label}</Link></td>
                                <td className={styles.nowrap}>❌ Non-compliant</td>
                                <td className={styles.comment}>
                                    {comment.text}{comment.more && <> <span className={styles.more}>{comment.more}</span></>}
                                </td>
                                <td className={styles.nowrap}>{formatDate(rule.since)}</td>
                            </tr>
                        );
                    })}
                    </tbody>
                </table>
            )}
            <p className={styles.compliance}>{complianceLine(component)}</p>
        </Frame>
    );
}

/**
 * The live excerpts of a system's or a component's page, filled in from the placeholder the generator wrote.
 * Today that is the governance excerpt; the deployment excerpt joins it here.
 */
export default function Excerpts({entry}) {
    const {siteConfig: {baseUrl, customFields: {docSite}}} = useDocusaurusContext();
    const governance = useExcerpt(governanceUrl(contextPathOf(baseUrl, docSite), entry.system));
    if (!governance) return null;
    return entry.page === 'system'
        ? <SystemGovernance excerpt={governance} entry={entry}/>
        : <ComponentGovernance excerpt={governance} entry={entry}/>;
}
