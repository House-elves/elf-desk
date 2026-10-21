import { LitElement, html, css } from 'lit';
import { shared, spinner, calendarText } from './shared.js';
import { api, errorMessage } from './rpc.js';
import { setInstalledElves } from './tabs.js';

const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const SHORT = { mon: 'Mon', tue: 'Tue', wed: 'Wed', thu: 'Thu', fri: 'Fri', sat: 'Sat', sun: 'Sun' };
const RULE = /^(?:(\S+) )?\*-\*-\* (\*|[\d,.]+):(\d{2})(?::00)?$/;

/** "Mon..Fri,Sun" as [mon, tue, wed, thu, fri, sun]; none as every day. */
function daysOf(text) {
    if (!text) return [...DAYS];
    const out = new Set();
    for (const part of text.toLowerCase().split(',')) {
        const [from, to] = part.split('..');
        const a = DAYS.indexOf(from), b = DAYS.indexOf(to ?? from);
        if (a < 0 || b < a) return null;
        for (let i = a; i <= b; i++) out.add(DAYS[i]);
    }
    return DAYS.filter(d => out.has(d));
}

/** The days as the calendar writes them: nothing for every day, runs of three or more as Mon..Fri. */
function daysText(days) {
    if (days.length === 7) return '';
    const parts = [];
    let i = 0;
    while (i < DAYS.length) {
        if (!days.includes(DAYS[i])) { i++; continue; }
        let j = i;
        while (j + 1 < DAYS.length && days.includes(DAYS[j + 1])) j++;
        const name = d => SHORT[DAYS[d]];
        if (j - i >= 2) parts.push(`${name(i)}..${name(j)}`);
        else for (let k = i; k <= j; k++) parts.push(name(k));
        i = j + 1;
    }
    return parts.join(',');
}

/**
 * A calendar time as the form shows it: {mode: off | times | hourly | other, days, times: ["07:00"], from, to}.
 * "other" is anything the form can't draw (set by hand), kept as it is until you pick something else.
 */
function model(cal) {
    const base = { days: ['mon', 'tue', 'wed', 'thu', 'fri'], times: ['07:00'], from: 8, to: 18 };
    if (!cal) return { ...base, mode: 'off' };
    const rules = cal.split(';').map(r => RULE.exec(r.trim()));
    if (rules.some(r => !r)) return { ...base, mode: 'other' };
    const days = daysOf(rules[0][1]);
    if (!days || rules.some(r => (r[1] ?? '') !== (rules[0][1] ?? ''))) return { ...base, mode: 'other' };
    const range = /^(\d{1,2})\.\.(\d{1,2})$/.exec(rules[0][2]);
    if (rules.length === 1 && (range || rules[0][2] === '*') && rules[0][3] === '00') {
        return { ...base, mode: 'hourly', days, from: range ? +range[1] : 0, to: range ? +range[2] : 23 };
    }
    const times = [];
    for (const r of rules) {
        if (!/^\d{1,2}(,\d{1,2})*$/.test(r[2])) return { ...base, mode: 'other' };
        r[2].split(',').forEach(h => times.push(`${h.padStart(2, '0')}:${r[3]}`));
    }
    return { ...base, mode: 'times', days, times: [...new Set(times)].sort() };
}

/** The form back as a calendar time, or null for "only when I start it". */
function calendar(m) {
    if (m.mode === 'off') return null;
    const days = daysText(m.days);
    const prefix = days ? `${days} ` : '';
    if (m.mode === 'hourly') return `${prefix}*-*-* ${m.from}..${m.to}:00`;
    return m.times.map(t => `${prefix}*-*-* ${t}`).join('; ');
}

/** "Thu 23 Oct, 07:00". */
function when(iso) {
    return new Date(iso).toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

/**
 * When an elf refreshes on its own: never, at set times or every hour, on the days you pick. The runner keeps it
 * and runs the elf's refresh command then (the same one its Refresh button runs). Fires "elf-schedule-saved".
 */
class ElfSchedule extends LitElement {

    static properties = {
        elf: { attribute: false },     // the installed elf, as the runner lists it (its refresh: {calendar, default, chosen, next})
        _form: { state: true },
        _busy: { state: true },
        _saved: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        :host { display: block; }
        fieldset { border: 0; margin: 0; padding: 0; display: grid; gap: 12px; min-width: 0; }
        legend { font-weight: 600; padding: 0; margin-bottom: 4px; }
        .modes { display: flex; flex-wrap: wrap; gap: 6px 18px; }
        .modes label, .days label { display: inline-flex; align-items: center; gap: 7px; font-size: 14px; }
        input[type="radio"], input[type="checkbox"] { accent-color: var(--accent); margin: 0; }
        .days { display: flex; flex-wrap: wrap; gap: 6px; }
        .days label { padding: 4px 10px; border: 1px solid var(--line); border-radius: 999px; background: var(--surface-2); }
        .days label:has(input:checked) { background: var(--accent-soft); border-color: color-mix(in srgb, var(--accent) 40%, var(--line)); }
        .days input { position: absolute; opacity: 0; pointer-events: none; }
        .days label:has(input:focus-visible) { outline: 2px solid var(--accent); outline-offset: 2px; }
        .times { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        .times input { width: 7.5rem; }
        .hours { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; font-size: 14px; }
        .now { font-size: 13.5px; color: var(--muted); margin: 0; }
        .acts { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
        .saved { font-size: 14px; color: var(--ok); }
    `];

    willUpdate(changed) {
        if (changed.has('elf') && !this._dirty) this._form = model(this.elf?.refresh?.calendar ?? null);
    }

    _set(change) {
        this._form = { ...this._form, ...change };
        this._dirty = true;
        this._saved = false;
    }

    _day(d, on) {
        const days = on ? DAYS.filter(x => x === d || this._form.days.includes(x)) : this._form.days.filter(x => x !== d);
        if (days.length) this._set({ days });
    }

    _time(i, value) {
        if (!/^\d{2}:\d{2}$/.test(value)) return;
        const times = [...this._form.times];
        times[i] = value;
        this._set({ times });
    }

    async _save(cal) {
        this._busy = true;
        this._error = null;
        try {
            const r = await (await api()).scheduleElf({ elf: this.elf.name, calendar: cal });
            setInstalledElves(r.elves ?? []);
            this._dirty = false;
            this.elf = (r.elves ?? []).find(e => e.name === this.elf.name) ?? this.elf;
            this._saved = true;
            this.dispatchEvent(new CustomEvent('elf-schedule-saved', { detail: r, bubbles: true, composed: true }));
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = false;
        }
    }

    render() {
        const r = this.elf?.refresh;
        if (!r) return '';
        const f = this._form;
        const id = `sch-${this.elf.name}`;
        const next = f.mode === 'other' ? r.calendar : calendar(f);
        const changed = this._dirty && next !== (r.calendar ?? null);
        return html`<fieldset>
            <legend>Refreshes on its own</legend>
            <p class="now">${r.calendar ? html`Now: ${calendarText(r.calendar)}${r.next ? html`, next ${when(r.next)}` : ''}.`
                : 'Now: only when you refresh it.'} A run missed while this machine slept happens once when it wakes.</p>
            <div class="modes" role="radiogroup" aria-label="When it refreshes">
                ${[['off', 'Only when I refresh it'], ['times', 'At set times'], ['hourly', 'Every hour']].map(([m, label]) => html`
                    <label><input type="radio" name=${id} .checked=${f.mode === m} @change=${() => this._set({ mode: m })}>${label}</label>`)}
                ${f.mode === 'other' ? html`<label><input type="radio" name=${id} checked>As set: ${calendarText(r.calendar)}</label>` : ''}
            </div>
            ${f.mode === 'times' || f.mode === 'hourly' ? html`
                <div class="days" role="group" aria-label="On these days">
                    ${DAYS.map(d => html`<label><input type="checkbox" .checked=${f.days.includes(d)}
                        @change=${e => this._day(d, e.target.checked)}>${SHORT[d]}</label>`)}
                </div>` : ''}
            ${f.mode === 'times' ? html`<div class="times">
                ${f.times.map((t, i) => html`<input type="time" aria-label="Time ${i + 1}" .value=${t} @change=${e => this._time(i, e.target.value)}>
                    ${f.times.length > 1 ? html`<button type="button" class="sm quiet" aria-label="Remove ${t}"
                        @click=${() => this._set({ times: f.times.filter((_, k) => k !== i) })}>Remove</button>` : ''}`)}
                ${f.times.length < 6 ? html`<button type="button" class="sm" @click=${() => this._set({ times: [...f.times, '13:00'] })}>Add a time</button>` : ''}
            </div>` : ''}
            ${f.mode === 'hourly' ? html`<div class="hours">From
                <select aria-label="From" @change=${e => this._set({ from: +e.target.value, to: Math.max(+e.target.value, f.to) })}>
                    ${[...Array(24).keys()].map(h => html`<option value=${h} ?selected=${h === f.from}>${String(h).padStart(2, '0')}:00</option>`)}
                </select> to
                <select aria-label="To" @change=${e => this._set({ to: +e.target.value, from: Math.min(+e.target.value, f.from) })}>
                    ${[...Array(24).keys()].map(h => html`<option value=${h} ?selected=${h === f.to}>${String(h).padStart(2, '0')}:00</option>`)}
                </select></div>` : ''}
            ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            <div class="acts">
                <button type="button" class="primary sm" ?disabled=${!changed || this._busy} @click=${() => this._save(next)}>
                    ${this._busy ? spinner : ''}Save the schedule</button>
                ${r.chosen && r.default !== undefined ? html`<button type="button" class="sm quiet" ?disabled=${this._busy}
                    @click=${() => { this._dirty = false; this._save('default'); }}>Back to its own
                    (${r.default ? calendarText(r.default) : 'only when you refresh it'})</button>` : ''}
                ${this._saved && !this._dirty ? html`<span class="saved" role="status">Saved</span>` : ''}
            </div>
        </fieldset>`;
    }
}

customElements.define('elf-schedule', ElfSchedule);
