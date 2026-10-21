// System / light / dark, remembered per browser. index.html applies it before first paint too.

const KEY = 'elfdesk.theme';

/** 'system', 'light' or 'dark'. */
export function currentTheme() {
    try {
        const t = localStorage.getItem(KEY);
        return t === 'light' || t === 'dark' ? t : 'system';
    } catch (e) {
        return 'system';
    }
}

export function setTheme(theme) {
    const root = document.documentElement;
    if (theme === 'light' || theme === 'dark') root.dataset.theme = theme;
    else delete root.dataset.theme;
    try {
        if (theme === 'system') localStorage.removeItem(KEY);
        else localStorage.setItem(KEY, theme);
    } catch (e) {
        // no storage: the choice lasts for this page only
    }
    window.dispatchEvent(new CustomEvent('elfdesk-theme', { detail: theme }));
}

