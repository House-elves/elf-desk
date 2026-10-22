// Which tabs there are: GitHub, then one per installed elf that has a tab, named by its title (Mail, Zulip, Weekly
// status), in the order you added them. Adding an elf adds its tab; removing it takes the tab away. GitHub is the
// desk's own board until the github elf is installed: then that elf's tab is GitHub, first as before.

const EVENT = 'elfdesk-tabs';

export const GITHUB = { id: 'github', label: 'GitHub' };

/** The installed elves (as the runner lists them), once known; null until then. */
let installed = null;
let elfTabs = [];

/**
 * Every tab: GitHub's, then each installed elf's, as {id, label, elf, card, refresh, missing}. A tab's id is its
 * elf's name (#mail-triage). refresh: the command that brings it up to date; missing: settings it needs first.
 */
export function tabs() {
    return githubElf() ? elfTabs : [GITHUB, ...elfTabs];
}

/** The github elf's tab, once it's installed: it takes the place of the desk's own board. */
export function githubElf() {
    return elfTabs.find(t => t.elf === GITHUB.id) ?? null;
}

export function findTab(id) {
    return tabs().find(t => t.id === id);
}

/** An elf's tab: from its elf.yaml's tab (a runner before 0.32 lists tabs, and the first is it). */
function tabOf(e) {
    return e.tab ?? e.tabs?.[0] ?? null;
}

/** What the Elves page or the shell learnt is installed: the runner's list of elves. */
export function setInstalledElves(elves) {
    const next = [...(elves ?? [])]
        .filter(e => tabOf(e) && e.name !== 'elves')
        .sort((a, b) => (b.name === GITHUB.id) - (a.name === GITHUB.id)
            || (a.installedAt ?? '').localeCompare(b.installedAt ?? '') || a.name.localeCompare(b.name))
        .map(e => ({
            id: e.name, label: e.title || e.name, elf: e.name, card: !!tabOf(e).card,
            refresh: e.refresh?.command ?? tabOf(e).refresh ?? null, missing: e.missing ?? [],
        }));
    const same = installed && JSON.stringify(next) === JSON.stringify(elfTabs)
        && JSON.stringify(elves) === JSON.stringify(installed);
    installed = elves ?? [];
    elfTabs = next;
    if (!same) window.dispatchEvent(new CustomEvent(EVENT));
}

/** The installed elves, or null before the runner has said. */
export function installedElves() {
    return installed;
}

export function installedElf(name) {
    return installed?.find(e => e.name === name) ?? null;
}

/** Calls back when tabs come and go with elves, or an elf's setup or schedule changes; returns the unsubscribe. */
export function onTabsChange(callback) {
    window.addEventListener(EVENT, callback);
    return () => window.removeEventListener(EVENT, callback);
}
