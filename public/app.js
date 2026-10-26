'use strict';

(() => {
  // ================================================================ утилиты

  const h = (tag, attrs = {}, ...children) => {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') node.className = v;
      else if (k === 'text') node.textContent = v;
      else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v === true ? '' : v);
    }
    for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) node.append(c);
    return node;
  };

  // replaceChildren превращает null в текст «null» — отбрасываем пустые.
  const fill = (node, ...kids) => node.replaceChildren(...kids.flat().filter((k) => k !== null && k !== undefined && k !== false));

  function rub(kop) {
    const sign = kop < 0 ? '-' : '';
    const abs = Math.abs(kop);
    const r = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
    return `${sign}${r},${String(abs % 100).padStart(2, '0')}`;
  }

  const pad = (n) => String(n).padStart(2, '0');
  const iso = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const fromIso = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
  const ruDate = (s) => { const [y, m, d] = s.split('-'); return `${d}.${m}.${y}`; };
  const today = () => iso(new Date());
  const shift = (s, n) => { const d = fromIso(s); d.setDate(d.getDate() + n); return iso(d); };
  const MONTHS = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь'];
  const MONTHS_GEN = ['янв.', 'февр.', 'мар.', 'апр.', 'мая', 'июн.', 'июл.', 'авг.', 'сент.', 'окт.', 'нояб.', 'дек.'];
  const shortDate = (s) => { const d = fromIso(s); return `${d.getDate()} ${MONTHS_GEN[d.getMonth()]} ${d.getFullYear()} г.`; };
  const periodText = (p) => (p.from === p.to ? ruDate(p.from) : `${ruDate(p.from)} — ${ruDate(p.to)}`);

  // Время из WB показываем как есть (оно московское), без пересчёта в пояс браузера.
  function dt(s) {
    if (!s) return '—';
    const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):?(\d{2})?/.exec(s);
    if (!m) return s;
    if (/(?:Z|[+-]\d{2}:?\d{2})$/i.test(s)) {
      return new Date(s).toLocaleString('ru-RU', { timeZone: 'Europe/Moscow' });
    }
    return `${m[3]}.${m[2]}.${m[1]}, ${m[4]}:${m[5]}:${m[6] || '00'}`;
  }

  const STATUS_CLASS = { pending: 'warn', unconfirmed: 'warn', approved: 'bad', charged: '' };

  function fineStatusPill(t) {
    return t.fineStatus ? h('span', { class: `pill ${STATUS_CLASS[t.fineStatus] || ''}`, text: t.fineStatusText }) : null;
  }

  function fineTypeLines(parts) {
    return (parts || []).map((p) => h('div', { class: 'small kv' },
      h('span', { class: 'muted', text: p.type }), h('span', { text: rub(p.sum) })));
  }

  const localTime = (isoStr) => (isoStr ? new Date(isoStr).toLocaleString('ru-RU') : '—');

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      method: opts.method || 'GET',
      headers: opts.body ? { 'Content-Type': 'application/json' } : undefined,
      body: opts.body ? JSON.stringify(opts.body) : undefined,
      credentials: 'same-origin',
    });
    let data = null;
    try { data = await res.json(); } catch { /* пусто */ }
    if (!res.ok) {
      const err = new Error((data && data.error && data.error.message) || `Ошибка ${res.status}`);
      err.code = data && data.error && data.error.code;
      if (err.code === 'AUTH') { state.me = null; renderAuth(); }
      throw err;
    }
    return data;
  }

  function msg(text, kind = 'error') {
    return text ? h('div', { class: `msg ${kind}`, text }) : null;
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      const ta = h('textarea', { class: 'input' });
      ta.value = text;
      document.body.append(ta);
      ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    }
  }

  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* приватный режим */ } },
  };

  // ================================================================ состояние

  const state = {
    me: null,
    page: 'reports',
    period: store.get('period', null) || { from: shift(today(), -1), to: shift(today(), -1) },
    waysheetDate: today(),
    cabinet: store.get('cabinet', ''),
    employee: '',
    deductHolds: store.get('deductHolds', true),
  };
  const root = document.getElementById('root');

  // ================================================================ окно выбора периода

  function periodPicker(initial, onApply) {
    let from = initial.from;
    let to = initial.to;
    let picking = false; // выбрана первая дата, ждём вторую
    let view = fromIso(initial.to);
    view.setDate(1);

    const bg = h('div', { class: 'modal-bg', onclick: (e) => { if (e.target === bg) close(); } });
    const close = () => bg.remove();

    function preset(kind) {
      const t = fromIso(today());
      if (kind === 'today') { from = to = today(); }
      if (kind === 'yesterday') { from = to = shift(today(), -1); }
      if (kind === 'month') { from = iso(new Date(t.getFullYear(), t.getMonth(), 1)); to = today(); }
      if (kind === 'prev') {
        from = iso(new Date(t.getFullYear(), t.getMonth() - 1, 1));
        to = iso(new Date(t.getFullYear(), t.getMonth(), 0));
      }
      picking = false;
      view = fromIso(to); view.setDate(1);
      draw();
    }

    function pick(day) {
      if (!picking) { from = to = day; picking = true; } else {
        if (day < from) { to = from; from = day; } else { to = day; }
        picking = false;
      }
      draw();
    }

    function draw() {
      const y = view.getFullYear();
      const m = view.getMonth();
      const first = (new Date(y, m, 1).getDay() + 6) % 7;
      const days = new Date(y, m + 1, 0).getDate();
      const cells = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'].map((d) => h('div', { class: 'dow', text: d }));
      for (let i = 0; i < first; i++) cells.push(h('div'));
      for (let d = 1; d <= days; d++) {
        const s = iso(new Date(y, m, d));
        const cls = [s === from || s === to ? 'edge' : '', s > from && s < to ? 'in' : '', s === today() ? 'today' : ''].join(' ');
        cells.push(h('button', { type: 'button', class: cls, text: String(d), onclick: () => pick(s) }));
      }
      fill(bg, h('div', { class: 'modal' },
        h('div', { class: 'row between' },
          h('h2', { text: 'Выберите период' }),
          h('button', { class: 'icon-btn', type: 'button', text: '✕', onclick: close })),
        h('div', { class: 'row' },
          ...[['today', 'Сегодня'], ['yesterday', 'Вчера'], ['month', 'Этот месяц'], ['prev', 'Прошлый месяц']]
            .map(([k, t]) => h('button', { class: 'btn small', type: 'button', text: t, onclick: () => preset(k) }))),
        h('div', { class: 'grid2' },
          h('div', {}, h('div', { class: 'small muted', text: 'С даты' }), h('div', { class: 'input', text: shortDate(from) })),
          h('div', {}, h('div', { class: 'small muted', text: 'По дату включительно' }), h('div', { class: 'input', text: shortDate(to) }))),
        h('p', { class: 'small muted', text: picking
          ? 'Теперь выберите последнюю дату. Для одного дня нажмите «Применить».'
          : 'Нажмите первую, затем последнюю дату периода.' }),
        h('div', { class: 'cal-head' },
          h('button', { class: 'icon-btn', type: 'button', text: '‹', onclick: () => { view.setMonth(m - 1); draw(); } }),
          h('span', { text: `${MONTHS[m][0].toUpperCase()}${MONTHS[m].slice(1)} ${y} г.` }),
          h('button', { class: 'icon-btn', type: 'button', text: '›', onclick: () => { view.setMonth(m + 1); draw(); } })),
        h('div', { class: 'cal' }, cells),
        h('div', { class: 'row' },
          h('div', { class: 'grow' }),
          h('button', { class: 'btn', type: 'button', text: 'Отмена', onclick: close }),
          h('button', { class: 'btn blue', type: 'button', text: 'Применить',
            onclick: () => { close(); onApply({ from, to }); } }))));
      bg.querySelectorAll('.modal > .row, .modal > .grid2').forEach((n) => { n.classList.add('gap-row'); });
    }

    draw();
    document.body.append(bg);
  }

  function periodField(onChange) {
    return h('label', { class: 'field' },
      h('span', { text: 'Период' }),
      h('button', { class: 'input picker', type: 'button', text: periodText(state.period),
        onclick: () => periodPicker(state.period, (p) => {
          state.period = p;
          store.set('period', p);
          onChange();
        }) }));
  }

  // ================================================================ вход

  function renderAuth(needsSetup = false, needsCode = false) {
    const login = h('input', { class: 'input', autocomplete: 'username', required: true });
    const pass = h('input', { class: 'input', type: 'password', autocomplete: needsSetup ? 'new-password' : 'current-password', required: true });
    const name = h('input', { class: 'input', required: true });
    const code = h('input', { class: 'input', autocomplete: 'off', required: true });
    const out = h('div');
    const form = h('form', { class: 'card', onsubmit: async (e) => {
      e.preventDefault();
      fill(out, );
      try {
        const body = { login: login.value, password: pass.value };
        if (needsSetup) body.name = name.value;
        if (needsCode) body.code = code.value;
        const r = await api(needsSetup ? '/api/setup' : '/api/login', { method: 'POST', body });
        state.me = r.user;
        renderApp();
      } catch (err) { fill(out, msg(err.message)); }
    } },
    needsSetup ? h('p', { class: 'small muted', text: 'Первый запуск: создайте администратора. Он добавит кабинеты WB и сотрудников.' }) : null,
    needsCode ? h('label', { class: 'field' }, h('span', { text: 'Код установки (из вывода установки на сервере)' }), code) : null,
    needsSetup ? h('label', { class: 'field' }, h('span', { text: 'ФИО' }), name) : null,
    h('label', { class: 'field' }, h('span', { text: 'Логин' }), login),
    h('label', { class: 'field' }, h('span', { text: needsSetup ? 'Пароль (от 6 символов)' : 'Пароль' }), pass),
    out,
    h('button', { class: 'btn light block', type: 'submit', text: needsSetup ? 'Создать и войти' : 'Войти' }));
    root.replaceChildren(h('div', { class: 'auth' },
      h('div', { class: 'brand', text: 'LogisticaWB' }),
      h('p', { class: 'muted', text: 'Зарплата водителей по данным WB Логистики' }),
      form));
    (needsSetup ? name : login).focus();
  }

  // ================================================================ каркас

  const PAGES = [
    { id: 'reports', title: 'Отчеты', ico: '📄' },
    { id: 'holds', title: 'Удержания WB', ico: '🧾' },
    { id: 'remains', title: 'Остатки', ico: '📦' },
    { id: 'waysheets', title: 'Путевые листы', ico: '🗒' },
    { id: 'summary', title: 'Ведомость', ico: '📊', manager: true },
    { id: 'settings', title: 'Настройки', ico: '⚙' },
  ];

  const ROLE_NAMES = { admin: 'Администратор', owner: 'ИП', driver: 'Водитель' };
  // Администратор и ИП видят всех водителей (ИП — только своего кабинета).
  const isManager = () => state.me.role === 'admin' || state.me.role === 'owner';
  const canSee = (p) => !p.manager || isManager();

  function openMenu() {
    const bg = h('div', { class: 'drawer-bg', onclick: () => close() });
    const close = () => { bg.remove(); drawer.remove(); };
    const drawer = h('nav', { class: 'drawer' },
      h('div', { class: 'head' },
        h('button', { class: 'btn menu-btn', type: 'button', text: '☰ Меню', onclick: () => close() }),
        h('button', { class: 'icon-btn', type: 'button', text: '✕', onclick: () => close() })),
      h('div', { class: 'who' },
        h('div', { class: 'label', text: ROLE_NAMES[state.me.role] || 'Сотрудник' }),
        h('div', { class: 'n', text: state.me.name }),
        h('div', { class: 'small muted', text: 'LogisticaWB' })),
      ...PAGES.filter(canSee).map((p) => h('button', {
        class: `nav-item ${state.page === p.id ? 'on' : ''}`, type: 'button',
        onclick: () => { close(); go(p.id); },
      }, h('span', { text: p.ico }), p.title)),
      h('button', { class: 'nav-item', type: 'button', onclick: async () => {
        await api('/api/logout', { method: 'POST' }).catch(() => {});
        close();
        state.me = null;
        renderAuth();
      } }, h('span', { text: '⎋' }), 'Выйти'));
    document.body.append(bg, drawer);
  }

  function go(page) {
    state.page = page;
    location.hash = page;
    renderApp();
  }

  function renderApp() {
    const page = PAGES.find((p) => p.id === state.page && canSee(p)) || PAGES[0];
    state.page = page.id;
    const main = h('main', { class: `wrap ${page.id === 'summary' ? 'wide' : ''}` });
    root.replaceChildren(
      h('div', { class: 'topbar' }, h('button', { class: 'btn menu-btn', type: 'button', text: '☰ Меню', onclick: openMenu })),
      main);
    RENDER[page.id](main);
    window.scrollTo(0, 0);
  }

  function head(ico, title, sub) {
    return h('div', { class: 'page-head' },
      h('div', { class: 'ico', text: ico }),
      h('div', {}, h('h1', { text: title }), sub ? h('p', { text: sub }) : null));
  }

  // Администратор выбирает: себя, сотрудника или любого водителя из WB за период.
  // ИП — водителя своего кабинета из WB.
  async function employeeSelect(onChange) {
    if (!isManager()) return null;
    const admin = state.me.role === 'admin';
    const sel = h('select', { class: 'input', onchange: () => { state.employee = sel.value; onChange(); } },
      admin ? h('option', { value: '', text: `${state.me.name} (я)` }) : null);
    const [usersRes, driversRes] = await Promise.allSettled([
      admin ? api('/api/users') : Promise.resolve({ users: [] }),
      api(`/api/drivers?${new URLSearchParams({ from: state.period.from, to: state.period.to })}`),
    ]);
    if (usersRes.status === 'fulfilled') {
      const others = usersRes.value.users.filter((u) => u.id !== state.me.id);
      if (others.length) {
        sel.append(h('optgroup', { label: 'Сотрудники' },
          ...others.map((u) => h('option', { value: `u:${u.id}`, text: u.name, selected: `u:${u.id}` === state.employee }))));
      }
    }
    if (driversRes.status === 'fulfilled' && driversRes.value.drivers.length) {
      sel.append(h('optgroup', { label: 'Водители из WB за период' },
        ...driversRes.value.drivers.map((d) => {
          const v = `d:${d.driverId || ''}|${d.driverName}`;
          return h('option', { value: v, text: `${d.driverName} · рейсов ${d.trips}`, selected: v === state.employee });
        })));
    }
    if (![...sel.options].some((o) => o.value === state.employee)) state.employee = sel.options[0] ? sel.options[0].value : '';
    sel.value = state.employee;
    if (!sel.options.length) sel.append(h('option', { value: '', text: 'За период водителей нет' }));
    return h('label', { class: 'field' }, h('span', { text: admin ? 'Сотрудник' : 'Водитель' }), sel);
  }

  function employeeQuery(q) {
    const v = state.employee;
    if (v.startsWith('u:')) q.set('employee', v.slice(2));
    if (v.startsWith('d:')) {
      const [id, ...name] = v.slice(2).split('|');
      q.set('driver', name.join('|'));
      if (id) q.set('driverId', id);
    }
  }

  // ================================================================ Отчеты

  async function renderReports(main) {
    const body = h('div');
    const card = h('div', { class: 'card' }, head('📄', 'Отчеты', 'Период и прибыль'));
    main.append(card);
    const sel = await employeeSelect(load);
    card.append(...[sel, periodField(() => renderApp()), body].filter(Boolean));

    async function load() {
      fill(body, h('div', { class: 'status', text: 'Загружаю…' }));
      try {
        const q = new URLSearchParams({ from: state.period.from, to: state.period.to });
        employeeQuery(q);
        q.set('holds', state.deductHolds ? '1' : '0');
        const r = await api(`/api/report?${q}`);
        const copyBtn = h('button', { class: 'btn', type: 'button', text: 'Копировать отчет', onclick: async () => {
          copyBtn.textContent = (await copyText(r.text)) ? 'Скопировано' : 'Не удалось скопировать';
          setTimeout(() => { copyBtn.textContent = 'Копировать отчет'; }, 2000);
        } });
        fill(body,
          ...r.warnings.map((w) => msg(w, 'warn')),
          h('label', { class: 'row check' },
            h('input', { type: 'checkbox', checked: state.deductHolds, onchange: (e) => {
              state.deductHolds = e.target.checked;
              store.set('deductHolds', state.deductHolds);
              load();
            } }),
            h('span', { text: `Вычитать удержания WB${r.holds ? ` (${rub(r.holds)} ₽: ${r.holdsByType.map((x) => `${x.type.toLowerCase()} ${rub(x.sum)}`).join(', ')})` : ' — за период их нет'}` })),
          h('div', { class: 'stat' }, h('div', { class: 'label', text: 'Путевых записей' }), h('div', { class: 'value', text: String(r.tripsCount) })),
          h('div', { class: 'stat' }, h('div', { class: 'label', text: 'Прибыль' }), h('div', { class: 'value', text: rub(r.payout) })),
          !r.tripsCount && !r.inTransit ? msg(isManager() && !state.employee
            ? 'За период нет путевых с вашим ФИО. Выберите водителя в списке «Сотрудник» — там есть все водители из WB за этот период.'
            : `За период нет путевых водителя «${r.employee.driverName || r.employee.name}». Если в WB ФИО записано иначе, `
              + 'администратор может указать «ФИО в WB» или ID водителя в настройках сотрудника.', 'warn') : null,
          h('div', { class: 'report-text', text: r.text }),
          r.inTransit ? h('p', { class: 'small muted', text: `Ещё в пути (не закрыты, в расчёт не вошли): ${r.inTransit}` }) : null,
          r.holdsError ? msg(`Удержания WB не загрузились: ${r.holdsError}`, 'warn') : null,
          copyBtn,
          r.trips.length ? h('details', {},
            h('summary', { text: 'Путевые в отчёте' }),
            ...r.trips.map((t) => h('div', { class: 'list-item small' },
              h('div', { class: 'row between' }, h('span', { text: `№ ${t.id}` }), h('b', { text: rub(t.price) })),
              h('div', { class: 'muted', text: `Закрыт ${dt(t.closeDt)}${t.fine ? ` · штраф ${rub(t.fine)}` : ''}` }),
              t.fine ? fineStatusPill(t) : null))) : null);
      } catch (e) {
        fill(body, msg(e.message));
      }
    }
    load();
  }

  // ================================================================ Удержания WB

  async function renderHolds(main) {
    const tab = state.holdsTab || 'wb';
    const content = h('div');
    main.append(h('div', { class: 'card' },
      head('🧾', 'Удержания WB', tab === 'wb' ? 'Брак, недоставленные коробки и другие' : 'Штрафы по путевым листам'),
      periodField(() => renderApp()),
      h('div', { class: 'row' },
        h('button', { class: `btn small ${tab === 'wb' ? 'active' : ''}`, type: 'button', text: 'Удержания WB',
          onclick: () => { state.holdsTab = 'wb'; renderApp(); } }),
        h('button', { class: `btn small ${tab === 'trips' ? 'active' : ''}`, type: 'button', text: 'Штрафы по путевым',
          onclick: () => { state.holdsTab = 'trips'; renderApp(); } })),
      content));
    if (tab === 'trips') return renderTripFines(content);

    fill(content, h('div', { class: 'status', text: 'Загружаю удержания из WB…' }));
    try {
      const r = await api(`/api/holds?${new URLSearchParams({ from: state.period.from, to: state.period.to })}`);
      const kind = h('select', { class: 'input', onchange: () => draw() }, h('option', { value: '', text: 'Все типы' }),
        ...r.byType.map((t) => h('option', { value: t.type, text: t.type })));
      const list = h('div');
      const draw = () => fill(list, r.groups.filter((g) => !kind.value || g.typeName === kind.value).map((g) => h('div', { class: 'list-item' },
        h('div', { class: 'row between' },
          h('span', {}, h('span', { class: 'pill warn', text: g.typeName }), g.refunded ? h('span', { class: 'pill', text: 'возвращено' }) : null),
          h('span', { class: 'num' }, h('b', { text: `${rub(g.sum)} ₽` }), h('div', { class: 'small muted', text: `${g.count} шт.` }))),
        h('div', { text: `Путевой лист №${g.waysheetId || '—'}${g.reason ? ` (${g.reason})` : ''}` }),
        h('div', { class: 'small muted', text: [isManager() ? (g.driverName || 'водитель не найден') : null,
          ruDate(g.day), g.cabinetName].filter(Boolean).join(' · ') }))));
      fill(content,
        ...r.warnings.map((w) => msg(w, 'warn')),
        h('div', { class: 'stat' }, h('div', { class: 'label', text: 'Сумма удержаний' }), h('div', { class: 'value', text: rub(r.total) })),
        ...(r.balances || []).map((b) => h('div', { class: 'list-item small' },
          h('div', { class: 'label', text: `Баланс · ${b.cabinet}` }),
          h('div', { class: 'kv' },
            h('span', { class: 'muted', text: 'Баланс' }), h('span', { text: `${rub(b.sum)} ₽` }),
            h('span', { class: 'muted', text: 'Доступно' }), h('span', { text: `${rub(b.available)} ₽` }),
            h('span', { class: 'muted', text: 'Штрафы' }), h('span', { text: `${rub(b.fines)} ₽` }),
            h('span', { class: 'muted', text: 'Всего' }), h('span', { text: `${rub(b.all)} ₽` })))),
        r.byType.length ? h('div', { class: 'list-item' }, h('div', { class: 'label', text: 'По типу' }),
          ...r.byType.map((t) => h('div', { class: 'small kv' },
            h('span', { class: 'muted', text: `${t.type} · ${t.count} шт.` }), h('span', { text: rub(t.sum) })))) : null,
        isManager() && r.unmatched.count ? h('p', { class: 'small muted',
          text: `Не нашёл водителя для ${r.unmatched.count} шт. на ${rub(r.unmatched.sum)} ₽ — путевой старше месяца или из другого кабинета.` }) : null,
        h('p', { class: 'small muted', text: 'В отчёте удержания вычитаются из прибыли (по дате удержания); там же их можно отключить.' }),
        r.count ? h('div', { class: 'field' }, kind) : h('p', { class: 'muted', text: 'За период удержаний нет.' }),
        list);
      draw();
    } catch (e) {
      fill(content, msg(e.message));
    }
  }

  async function renderTripFines(main) {
    const body = h('div');
    main.append(body);
    body.append(h('div', { class: 'status', text: 'Загружаю…' }));
    try {
      const q = new URLSearchParams({ from: state.period.from, to: state.period.to });
      const r = await api(`/api/fines?${q}`);
      fill(body, 
        ...r.warnings.map((w) => msg(w, 'warn')),
        h('div', { class: 'stat' }, h('div', { class: 'label', text: 'Сумма удержаний' }), h('div', { class: 'value', text: rub(r.total) })),
        r.items.length ? h('div', {},
          h('div', { class: 'list-item' }, h('div', { class: 'label', text: 'По статусу' }),
            ...r.byStatus.map((x) => h('div', { class: 'small kv' },
              h('span', { class: 'muted', text: x.text }), h('span', { text: rub(x.sum) })))),
          h('div', { class: 'list-item' }, h('div', { class: 'label', text: 'По виду' }), ...fineTypeLines(r.byType))) : null,
        r.byStatus.some((x) => x.status === 'pending') ? h('p', { class: 'small muted', text: r.deductPending
          ? 'Штрафы на рассмотрении вычитаются из зарплаты (меняется в настройках).'
          : 'Штрафы на рассмотрении из зарплаты не вычитаются, пока WB их не подтвердит.' }) : null,
        r.items.length ? null : h('p', { class: 'muted', text: 'За период удержаний нет.' }),
        ...r.items.map((t) => h('div', { class: 'list-item' },
          h('div', { class: 'row between' }, h('span', { class: 't', text: `№ ${t.id}` }), h('b', { text: rub(t.fine) })),
          isManager() ? h('div', { text: t.driverName }) : null,
          h('div', { class: 'small muted', text: `${t.cabinetName} · ${t.closeDt ? `закрыт ${dt(t.closeDt)}` : `в пути с ${dt(t.openDt)}`}` }),
          h('div', {}, fineStatusPill(t)),
          ...fineTypeLines(t.fineParts))));
    } catch (e) {
      fill(body, msg(e.message));
    }
  }

  // ================================================================ Остатки

  async function renderRemains(main) {
    const body = h('div');
    const status = h('div', { class: 'status' });
    const carrier = h('select', { class: 'input', onchange: draw }, h('option', { value: '', text: 'Все перевозчики' }));
    const search = h('input', { class: 'input', type: 'search', placeholder: 'Маршрут или парковка', oninput: draw });
    let data = { items: [], carriers: [] };

    let upload = null;
    if (state.me.role === 'admin') {
      const file = h('input', { type: 'file', accept: '.xlsx,.csv', class: 'input' });
      const btn = h('button', { class: 'btn light', type: 'button', text: 'Загрузить', onclick: async () => {
        const f = file.files[0];
        if (!f) { fill(status, msg('Выберите файл выгрузки остатков из WB (.xlsx).')); return; }
        btn.disabled = true;
        status.textContent = 'Загружаю…';
        try {
          const res = await fetch(`/api/remains?${new URLSearchParams({ name: f.name })}`, {
            method: 'POST', body: f, headers: { 'Content-Type': 'application/octet-stream' }, credentials: 'same-origin' });
          const r = await res.json();
          if (!res.ok) throw new Error(r.error.message);
          fill(status, msg(`Загружено маршрутов: ${r.loaded}.`, 'ok'));
          file.value = '';
          await load();
        } catch (e) { fill(status, msg(e.message)); }
        btn.disabled = false;
      } });
      upload = h('details', {}, h('summary', { text: 'Загрузить выгрузку Excel вручную' }),
        h('p', { class: 'small muted', text: 'Скачайте на ol.wildberries.ru выгрузку остатков (Excel) и загрузите её сюда. '
          + 'Маршруты тех же перевозчиков заменятся, остальные останутся.' }),
        h('div', { class: 'field' }, file),
        h('div', { class: 'row' }, btn,
          h('button', { class: 'btn danger small', type: 'button', text: 'Очистить всё', onclick: async () => {
            if (!confirm('Удалить все загруженные остатки?')) return;
            await api('/api/remains', { method: 'DELETE' });
            load();
          } })));
    }

    const auto = h('div', { class: 'status' });
    const refresh = h('button', { class: 'btn light block', type: 'button', text: '⟳ Обновить из WB', onclick: async () => {
      refresh.disabled = true;
      refresh.textContent = '⟳ Загружаю…';
      try { show(await api('/api/remains/refresh', { method: 'POST' })); } catch (e) { fill(auto, msg(e.message)); }
      refresh.disabled = false;
      refresh.textContent = '⟳ Обновить из WB';
    } });

    main.append(h('div', { class: 'card' },
      head('📦', 'Остатки', 'Маршруты, коробки, КГТ и ШК'),
      refresh, auto, upload, status,
      h('div', { class: 'grid2' }, carrier, search)), body);

    function draw() {
      const q = search.value.trim().toLowerCase();
      const items = data.items.filter((i) => (!carrier.value || i.carrierList.some((c) => c.name === carrier.value))
        && (!q || String(i.routeId).includes(q) || String(i.parking || '').toLowerCase().includes(q)));
      const total = (k) => items.reduce((a, i) => a + (i[k] || 0), 0);
      fill(body,
        items.length ? h('div', { class: 'row' },
          h('span', { class: 'pill', text: `Маршрутов: ${items.length}` }),
          h('span', { class: 'pill', text: `ШК: ${total('shk')}` }),
          h('span', { class: 'pill', text: `Коробок: ${total('boxes')}` }),
          h('span', { class: 'pill', text: `КГТ: ${total('kgt')}` })) : h('p', { class: 'muted', text: data.items.length
          ? 'Ничего не найдено.' : 'Остатки ещё не загружены.' }),
        h('div', { class: 'remains' }, items.map(remainCard)));
    }

    async function load() {
      try {
        show(await api('/api/remains'));
      } catch (e) { fill(status, msg(e.message)); }
    }

    function show(r) {
      data = r;
      fill(auto,
        ...r.auto.map((a) => h('div', { class: a.error ? 'msg error' : '', text: a.error
          ? a.error : `${a.cabinet}: ${a.ok ? `обновлено ${localTime(a.ok)}, маршрутов ${a.count}` : 'ещё не обновлялось'}` })),
        h('div', { text: r.autoMinutes ? `Автообновление каждые ${r.autoMinutes} мин.` : 'Автообновление выключено.' }));
      const keep = carrier.value;
      carrier.replaceChildren(h('option', { value: '', text: 'Все перевозчики' }),
        ...data.carriers.map((c) => h('option', { value: c, text: c, selected: c === keep })));
      draw();
    }
    load();
  }

  function remainCard(i) {
    const ready = i.shk !== null && i.shkNorm !== null && i.shk >= i.shkNorm;
    const cell = (label, value) => h('div', { class: 'cell' }, h('div', { class: 'label', text: label }), h('div', { class: 'v', text: value ?? '—' }));
    return h('div', { class: 'card remain' },
      h('div', { class: 'title', text: `Маршрут - ${i.routeId}` }),
      ...i.carrierList.map((c) => h('div', { class: 'small', text: c.name })),
      h('div', { class: 'small muted', text: i.officeName || '' }),
      h('div', { class: `shk ${ready ? 'ready' : ''}` }, h('div', { class: 'label', text: 'ШК' }), h('div', { class: 'big', text: i.shk ?? '—' })),
      h('div', { class: 'cells' }, cell('Коробки', i.boxes), cell('КГТ', i.kgt)),
      h('div', { class: 'kv small' },
        h('span', { class: 'muted', text: 'Парковка' }), h('span', { text: i.parking || '—' }),
        h('span', { class: 'muted', text: 'Норма выезда' }), h('span', { text: i.shkNorm ?? '—' }),
        i.volume !== null && i.volume !== undefined ? h('span', { class: 'muted', text: 'Объём, л' }) : null,
        i.volume !== null && i.volume !== undefined ? h('span', { text: Math.round(i.volume) }) : null,
        i.distance !== null && i.distance !== undefined ? h('span', { class: 'muted', text: 'Дистанция, км' }) : null,
        i.distance !== null && i.distance !== undefined ? h('span', { text: String(i.distance).replace('.', ',') }) : null,
        i.arrivalTime ? h('span', { class: 'muted', text: 'Подача ТС' }) : null,
        i.arrivalTime ? h('span', { text: i.arrivalTime }) : null));
  }

  // ================================================================ Путевые листы

  async function renderWaysheets(main) {
    const status = h('div', { class: 'status', text: 'Выбери период и загрузи путевые.' });
    const list = h('div');
    const date = h('input', { class: 'input', type: 'date', value: state.waysheetDate,
      onchange: () => { state.waysheetDate = date.value; } });
    const cab = h('select', { class: 'input', onchange: () => { state.cabinet = cab.value; store.set('cabinet', cab.value); } },
      h('option', { value: '', text: 'Выбери кабинет' }));
    const btn = h('button', { class: 'btn light block', type: 'button', text: '⟳ Обновить', onclick: load });
    main.append(h('div', { class: 'card' },
      head('🗒', 'Путевые листы'),
      h('label', { class: 'field' }, h('span', { text: 'Период' }), date),
      h('label', { class: 'field' }, h('span', { text: 'Кабинет' }), cab),
      btn, status), list);

    try {
      const { cabinets } = await api('/api/cabinets');
      if (cabinets.length === 1) state.cabinet = cabinets[0].id;
      for (const c of cabinets) cab.append(h('option', { value: c.id, text: c.name, selected: c.id === state.cabinet }));
      if (!cabinets.some((c) => c.id === state.cabinet)) state.cabinet = '';
      if (!cabinets.length) status.textContent = 'Кабинеты ещё не добавлены. Администратор добавляет их в настройках.';
    } catch (e) { fill(status, msg(e.message)); }

    async function load() {
      if (!state.cabinet) { status.textContent = 'Выбери кабинет.'; return; }
      btn.disabled = true;
      btn.textContent = '⟳ Загружаю…';
      status.textContent = 'Ставлю запрос в очередь…';
      fill(list, );
      try {
        const r = await api(`/api/waysheets?${new URLSearchParams({ date: state.waysheetDate, cabinet: state.cabinet })}`);
        status.textContent = `Путевые загружены: ${r.items.length}.\nПоследняя успешная загрузка: ${localTime(r.fetchedAt)}`;
        list.append(h('div', { class: 'row' },
          h('div', { class: 'grow' }), h('span', { class: 'pill', text: `Всего путевых: ${r.items.length}` })));
        r.items.forEach((t, i) => list.append(tripCard(t, i + 1)));
      } catch (e) {
        fill(status, msg(e.message));
      } finally {
        btn.disabled = false;
        btn.textContent = '⟳ Обновить';
      }
    }
  }

  function tripCard(t, n) {
    const boxes = t.boxCountTotal !== null && t.boxCountTotal !== undefined
      ? `${t.boxCountTotal} / ${t.boxCountDelivered ?? 0}` : '—';
    const route = t.routePoints.length ? t.routePoints.map((p) => (p.id ? `${p.name} (${p.id})` : p.name)).join(' → ') : '';
    return h('div', { class: 'card trip' },
      h('div', { class: 'label', text: `Путевой ${n}` }),
      h('div', { class: 'name', text: t.driverName }),
      h('div', { class: 'small muted' }, 'Номер путевого: ', h('b', { text: `№ ${t.id}` })),
      h('div', {},
        t.parking ? h('span', { class: 'pill', text: `Парковка ${t.parking}` }) : null,
        t.srcOfficeName ? h('span', { class: 'pill', text: t.srcOfficeName }) : null,
        t.vehicleNumberPlate ? h('span', { class: 'pill', text: t.vehicleNumberPlate }) : null,
        t.cabinetName ? h('span', { class: 'pill', text: t.cabinetName }) : null,
        t.fineStatus ? h('span', { class: `pill ${STATUS_CLASS[t.fineStatus] || ''}`, text: `Штраф: ${t.fineStatusText.toLowerCase()}` }) : null),
      route ? h('div', { class: 'route-line', text: route }) : null,
      h('div', { class: 'box' }, h('div', { class: 'label', text: 'Открыл' }), h('div', { class: 'v', text: dt(t.openDt) })),
      h('div', { class: 'box' }, h('div', { class: 'label', text: 'Закрыл' }), h('div', { class: 'v', text: t.closeDt ? dt(t.closeDt) : '—' })),
      h('div', { class: 'box lines' },
        h('div', {}, 'ШК: ', h('b', { text: t.shksCount ?? '—' })),
        h('div', {}, 'Коробки: ', h('b', { text: boxes })),
        t.planMileage !== null && t.planMileage !== undefined ? h('div', {}, 'Пробег (план): ', h('b', { text: `${t.planMileage} км` })) : null),
      h('div', { class: 'box lines' },
        h('div', {}, 'Сумма путевого листа: ', h('b', { text: `${rub(t.price)} руб.` })),
        h('div', {}, 'Сумма возвратов: ', h('b', { text: `${rub(t.totalSumReturn || 0)} руб.` })),
        h('div', {}, 'Сумма штрафов: ', h('b', { text: `${rub(t.fine)} руб.` })),
        t.fineParts.length > 1 || (t.fineParts[0] && t.fineParts[0].type !== 'Без вида') ? fineTypeLines(t.fineParts) : null,
        h('div', {}, 'Итог: ', h('b', { text: `${rub(t.total)} руб.` }))));
  }

  // ================================================================ Ведомость (администратор)

  async function renderSummary(main) {
    const body = h('div');
    const pct = h('input', { class: 'input', type: 'number', min: '0', max: '100', step: '0.01' });
    main.append(h('div', { class: 'card' },
      head('📊', 'Ведомость', 'Все водители за период'),
      h('div', { class: 'grid2' }, periodField(() => renderApp()),
        h('label', { class: 'field' }, h('span', { text: 'Комиссия, %' }), pct)),
      h('button', { class: 'btn light', type: 'button', text: 'Рассчитать', onclick: load })), body);
    try { pct.value = (await api('/api/settings')).percent; } catch { pct.value = 0; }

    async function load() {
      fill(body, h('div', { class: 'status', text: 'Загружаю…' }));
      try {
        const r = await api(`/api/summary?${new URLSearchParams({ from: state.period.from, to: state.period.to,
          percent: pct.value, holds: state.deductHolds ? '1' : '0' })}`);
        const t = r.totals;
        const tbody = h('tbody');
        for (const d of r.drivers) {
          const tr = h('tr', { class: 'click' },
            h('td', { text: d.driverName }),
            h('td', { class: 'num', text: d.tripsCount }),
            h('td', { class: 'num', text: rub(d.sum) }),
            h('td', { class: 'num', text: rub(d.retention) }),
            h('td', { class: 'num', text: d.fines ? rub(d.fines) : '—' }),
            h('td', { class: 'num', text: d.finesPending ? rub(d.finesPending) : '—' }),
            h('td', { class: `num ${r.deductHolds ? '' : 'muted'}`, text: d.holds ? rub(d.holds) : '—' }),
            h('td', { class: 'num', text: rub(d.payout) }));
          const det = h('tr', { hidden: true }, h('td', { colspan: '8' },
            d.holdsByType.length ? h('div', { class: 'small', text: `Удержания WB: ${d.holdsByType.map((x) => `${x.type.toLowerCase()} ${rub(x.sum)}`).join(', ')}` }) : null,
            ...d.trips.map((x) => h('div', { class: 'small muted', text: `№ ${x.id} · ${x.vehicleNumberPlate || ''} · закрыт ${dt(x.closeDt)} · ${rub(x.price)}${x.fine ? ` · штраф ${rub(x.fine)}` : ''}` }))));
          tr.addEventListener('click', () => { det.hidden = !det.hidden; });
          tbody.append(tr, det);
        }
        const csvBtn = h('button', { class: 'btn', type: 'button', text: 'Скачать CSV', onclick: () => downloadCsv(r) });
        const holdsToggle = h('label', { class: 'row check' },
          h('input', { type: 'checkbox', checked: state.deductHolds, onchange: (e) => {
            state.deductHolds = e.target.checked;
            store.set('deductHolds', state.deductHolds);
            load();
          } }),
          h('span', { text: `Вычитать удержания WB${t.holds ? ` (${rub(t.holds)} ₽)` : ' — за период их нет'}` }));
        fill(body, 
          ...r.warnings.map((w) => msg(w, 'warn')),
          holdsToggle,
          h('div', { class: 'grid2' },
            h('div', { class: 'stat' }, h('div', { class: 'label', text: 'Сумма' }), h('div', { class: 'value', text: rub(t.sum) })),
            h('div', { class: 'stat' }, h('div', { class: 'label', text: 'Прибыль' }), h('div', { class: 'value', text: rub(t.payout) }))),
          h('div', { class: 'table-wrap' }, h('table', {},
            h('thead', {}, h('tr', {}, ...['Водитель', 'Рейсов', 'Сумма', 'Комиссия', 'Штрафы', 'На рассм.', 'Удерж. WB', 'Прибыль']
              .map((x, i) => h('th', { class: i ? 'num' : '', text: x })))),
            tbody,
            h('tfoot', {}, h('tr', {},
              h('td', { text: 'Итого' }), h('td', { class: 'num', text: t.tripsCount }), h('td', { class: 'num', text: rub(t.sum) }),
              h('td', { class: 'num', text: rub(t.retention) }), h('td', { class: 'num', text: rub(t.fines) }),
              h('td', { class: 'num', text: rub(t.finesPending) }),
              h('td', { class: `num ${r.deductHolds ? '' : 'muted'}`, text: rub(t.holds) }),
              h('td', { class: 'num', text: rub(t.payout) }))))),
          r.holdsUnmatched.count ? h('p', { class: 'small muted', text: `Удержания WB без водителя (путевой не найден): `
            + `${r.holdsUnmatched.count} шт. на ${rub(r.holdsUnmatched.sum)} ₽ — в ведомость не вошли.` }) : null,
          h('p', { class: 'small muted', text: `В пути (не вошли): ${r.inTransit.length}. Данные WB на ${localTime(r.fetchedAt)}.` }),
          csvBtn);
      } catch (e) { fill(body, msg(e.message)); }
    }
    load();
  }

  function downloadCsv(r) {
    const money = (k) => rub(k).replace(/ /g, '');
    const cell = (v) => { const s = String(v ?? ''); return /[";\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
    const holdsCol = r.deductHolds ? 'Удержания WB' : 'Удержания WB (не вычтены)';
    const lines = [['Водитель', 'ID водителя', 'Рейсов', 'Сумма', 'Комиссия', 'Штрафы', holdsCol, 'Прибыль']];
    for (const d of r.drivers) lines.push([d.driverName, d.driverId ?? '', d.tripsCount, money(d.sum), money(d.retention), money(d.fines), money(d.holds), money(d.payout)]);
    const t = r.totals;
    lines.push(['Итого', '', t.tripsCount, money(t.sum), money(t.retention), money(t.fines), money(t.holds), money(t.payout)]);
    const csv = `﻿${lines.map((l) => l.map(cell).join(';')).join('\r\n')}`;
    const a = h('a', { href: URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' })), download: `vedomost_${r.from}_${r.to}.csv` });
    document.body.append(a); a.click(); a.remove();
  }

  // ================================================================ Настройки

  async function renderSettings(main) {
    main.append(head('⚙', 'Настройки'));
    if (state.me.role === 'admin') {
      const cabs = h('div');
      const users = h('div');
      const common = h('div');
      main.append(cabs, users, common);
      await Promise.all([settingsCabinets(cabs), settingsUsers(users), settingsCommon(common)]);
    }
    main.append(settingsPassword());
  }

  function keyText(k) {
    if (!k || !k.configured) return k && k.broken ? 'ключ не читается — вставьте заново' : 'ключ не задан';
    if (k.expired) return 'ключ устарел — обновите';
    if (!k.expiresAt) return 'ключ задан (срок неизвестен)';
    return `ключ до ${new Date(k.expiresAt).toLocaleDateString('ru-RU')} (${k.daysLeft} дн.)`;
  }

  async function settingsCabinets(box) {
    const out = h('div');
    const list = h('div');
    const name = h('input', { class: 'input', placeholder: 'ИП Иванов Иван' });
    const key = h('input', { class: 'input', type: 'password', placeholder: 'https://ol.wildberries.ru/…bearer=…', autocomplete: 'off', spellcheck: 'false' });
    const sup = h('input', { class: 'input', inputmode: 'numeric', placeholder: 'Например 10105118 — можно оставить пустым' });
    box.append(h('div', { class: 'card' },
      h('div', { class: 'label', text: 'Кабинеты WB' }),
      h('p', { class: 'small muted', text: 'Для каждого ИП: откройте ol.wildberries.ru под этим кабинетом, скопируйте адрес страницы целиком (в нём есть bearer=) и вставьте сюда. Ключ хранится на сервере в зашифрованном виде.' }),
      list,
      h('details', {}, h('summary', { text: '+ Добавить кабинет' }),
        h('label', { class: 'field' }, h('span', { text: 'Название' }), name),
        h('label', { class: 'field' }, h('span', { text: 'Адрес страницы с bearer= или ключ' }), key),
        h('label', { class: 'field' }, h('span', { text: 'ID поставщика (для остатков)' }), sup),
        h('button', { class: 'btn light', type: 'button', text: 'Добавить', onclick: async () => {
          try {
            await api('/api/cabinets', { method: 'POST', body: { name: name.value, value: key.value, supplierIds: sup.value } });
            name.value = ''; key.value = ''; sup.value = '';
            fill(out, msg('Кабинет добавлен.', 'ok'));
            draw();
          } catch (e) { fill(out, msg(e.message)); }
        } })),
      out));

    async function draw() {
      const { cabinets } = await api('/api/cabinets');
      fill(list, ...(cabinets.length ? [] : [h('p', { class: 'muted', text: 'Кабинетов пока нет.' })]),
        ...cabinets.map((c) => {
          const newKey = h('input', { class: 'input', type: 'password', placeholder: 'Новый адрес с bearer=…', autocomplete: 'off' });
          const rawDate = h('input', { class: 'input', type: 'date', value: shift(today(), -1) });
          const key2 = h('input', { class: 'input', type: 'password', placeholder: 'Bearer eyJ…', autocomplete: 'off', spellcheck: 'false' });
          const k2 = c.key2 || {};
          const k2text = k2.configured ? keyText(k2) : 'не задан — используется основной ключ';
          const supEdit = h('input', { class: 'input grow', inputmode: 'numeric', value: c.supplierIds.join(', '), placeholder: 'ID поставщика' });
          const bad = !c.key.configured || c.key.expired;
          return h('div', { class: 'list-item' },
            h('div', { class: 'row between' },
              h('span', { class: 't', text: c.name }),
              h('span', { class: `small ${bad ? 'pill warn' : 'muted'}`, text: keyText(c.key) })),
            h('div', { class: 'small muted', text: `ID поставщика: ${c.supplierIds.length ? c.supplierIds.join(', ') : 'определится сам по путевым'}` }),
            h('div', { class: `small ${k2.configured && k2.expired ? 'pill warn' : 'muted'}`, text: `Ключ остатков и удержаний: ${k2text}` }),
            h('details', {}, h('summary', { text: 'Ключ остатков и удержаний' }),
              h('p', { class: 'small muted', text: 'Нужен, если WB для остатков и удержаний требует другой ключ. Где взять: '
                + 'на ol.wildberries.ru откройте «Остатки», F12 → Network, обновите страницу, щёлкните запрос by-routecar → '
                + 'Headers → Request Headers → строка Authorization → правой кнопкой «Copy Value» и вставьте сюда. '
                + 'Никому этот ключ не пересылайте.' }),
              h('div', { class: 'field' }, key2),
              h('div', { class: 'row' },
                h('button', { class: 'btn small light', type: 'button', text: 'Сохранить ключ', onclick: async () => {
                  try {
                    await api(`/api/cabinets/${c.id}`, { method: 'PUT', body: { value2: key2.value } });
                    fill(out, msg('Ключ остатков и удержаний сохранён.', 'ok'));
                    draw();
                  } catch (e) { fill(out, msg(e.message)); }
                } }),
                k2.configured ? h('button', { class: 'btn small danger', type: 'button', text: 'Убрать', onclick: async () => {
                  await api(`/api/cabinets/${c.id}`, { method: 'PUT', body: { clearValue2: true } });
                  draw();
                } }) : null)),
            h('details', {}, h('summary', { text: 'Обновить ключ / ID поставщика / удалить' }),
              h('div', { class: 'field' }, newKey),
              h('div', { class: 'row' }, supEdit,
                h('button', { class: 'btn small', type: 'button', text: 'Сохранить ID', onclick: async () => {
                  try {
                    await api(`/api/cabinets/${c.id}`, { method: 'PUT', body: { supplierIds: supEdit.value } });
                    fill(out, msg('ID поставщика сохранён.', 'ok'));
                    draw();
                  } catch (e) { fill(out, msg(e.message)); }
                } })),
              h('div', { class: 'row' },
                h('button', { class: 'btn small light', type: 'button', text: 'Сохранить ключ', onclick: async () => {
                  try {
                    await api(`/api/cabinets/${c.id}`, { method: 'PUT', body: { value: newKey.value } });
                    fill(out, msg('Ключ обновлён.', 'ok'));
                    draw();
                  } catch (e) { fill(out, msg(e.message)); }
                } }),
                h('button', { class: 'btn small danger', type: 'button', text: 'Удалить кабинет', onclick: async () => {
                  if (!confirm(`Удалить кабинет «${c.name}» и его ключ?`)) return;
                  await api(`/api/cabinets/${c.id}`, { method: 'DELETE' });
                  draw();
                } })),
              h('p', { class: 'small muted', text: 'Проверка полей: скачать ответ WB за день как есть (ключа в файле нет).' }),
              h('div', { class: 'row' }, rawDate,
                h('button', { class: 'btn small', type: 'button', text: 'Скачать JSON', onclick: () => {
                  location.href = `/api/raw?${new URLSearchParams({ cabinet: c.id, date: rawDate.value })}`;
                } }))));
        }));
    }
    try { await draw(); } catch (e) { fill(out, msg(e.message)); }
  }

  async function settingsUsers(box) {
    const out = h('div');
    const list = h('div');
    let cabinets = [];
    try { ({ cabinets } = await api('/api/cabinets')); } catch { /* без списка */ }
    const cabName = (id) => (cabinets.find((c) => c.id === id) || {}).name || 'кабинет не выбран';
    const cabSelect = (value) => h('select', { class: 'input' }, h('option', { value: '', text: 'Выберите кабинет' }),
      ...cabinets.map((c) => h('option', { value: c.id, text: c.name, selected: c.id === value })));

    const f = {
      name: h('input', { class: 'input', placeholder: 'ИП Иванов Иван' }),
      login: h('input', { class: 'input', autocomplete: 'off' }),
      password: h('input', { class: 'input', type: 'password', autocomplete: 'new-password' }),
      role: h('select', { class: 'input' },
        h('option', { value: 'owner', text: 'ИП — видит свой кабинет' }),
        h('option', { value: 'admin', text: 'Администратор — всё и настройки' }),
        h('option', { value: 'driver', text: 'Водитель — только себя' })),
      cabinetId: cabSelect(cabinets.length === 1 ? cabinets[0].id : ''),
      driverName: h('input', { class: 'input', list: 'wb-drivers', placeholder: 'Как в WB, если отличается от ФИО' }),
      driverId: h('input', { class: 'input', placeholder: 'Необязательно' }),
      percent: h('input', { class: 'input', type: 'number', min: '0', max: '100', step: '0.01', placeholder: 'Общий' }),
    };
    const cabField = h('label', { class: 'field' }, h('span', { text: 'Кабинет' }), f.cabinetId);
    const dl = h('datalist', { id: 'wb-drivers' });
    const loadDrivers = h('button', { class: 'btn small', type: 'button', text: 'Подтянуть ФИО из WB за 30 дней', onclick: async () => {
      loadDrivers.disabled = true;
      try {
        const { drivers } = await api(`/api/drivers?${new URLSearchParams({ from: shift(today(), -30), to: today() })}`);
        dl.replaceChildren(...drivers.map((d) => h('option', { value: d.driverName, text: `${d.driverName}${d.driverId ? ` · ID ${d.driverId}` : ''} · рейсов ${d.trips}` })));
        fill(out, msg(`Найдено водителей: ${drivers.length}. Начните вводить ФИО в поле «ФИО в WB».`, 'ok'));
      } catch (e) { fill(out, msg(e.message)); }
      loadDrivers.disabled = false;
    } });
    const driverFields = h('div', {},
      h('label', { class: 'field' }, h('span', { text: 'ФИО в WB' }), f.driverName, dl),
      loadDrivers,
      h('div', { class: 'grid2' },
        h('label', { class: 'field' }, h('span', { text: 'ID водителя WB' }), f.driverId),
        h('label', { class: 'field' }, h('span', { text: 'Комиссия, %' }), f.percent)));
    const syncRole = () => {
      cabField.hidden = f.role.value !== 'owner';
      driverFields.hidden = f.role.value !== 'driver';
    };
    f.role.addEventListener('change', syncRole);
    syncRole();

    box.append(h('div', { class: 'card' },
      h('div', { class: 'label', text: 'Пользователи' }),
      h('p', { class: 'small muted', text: 'ИП входит по логину и паролю и видит всё по своему кабинету: отчёты, ведомость, путевые, удержания, остатки. Ключей и настроек он не видит.' }),
      list,
      h('details', {}, h('summary', { text: '+ Добавить пользователя' }),
        h('label', { class: 'field' }, h('span', { text: 'Имя (ФИО или название ИП)' }), f.name),
        h('div', { class: 'grid2' },
          h('label', { class: 'field' }, h('span', { text: 'Логин' }), f.login),
          h('label', { class: 'field' }, h('span', { text: 'Пароль (от 6 символов)' }), f.password)),
        h('label', { class: 'field' }, h('span', { text: 'Роль' }), f.role),
        cabField,
        driverFields,
        h('button', { class: 'btn light', type: 'button', text: 'Добавить', onclick: async () => {
          try {
            const body = Object.fromEntries(Object.entries(f).map(([k, el]) => [k, el.value]));
            await api('/api/users', { method: 'POST', body });
            [f.name, f.login, f.password, f.driverName, f.driverId, f.percent].forEach((el) => { el.value = ''; });
            fill(out, msg('Пользователь добавлен. Передайте ему адрес сайта, логин и пароль.', 'ok'));
            draw();
          } catch (e) { fill(out, msg(e.message)); }
        } })),
      out));

    async function draw() {
      const { users } = await api('/api/users');
      fill(list, ...users.map((u) => {
        const pw = h('input', { class: 'input', type: 'password', placeholder: 'Новый пароль', autocomplete: 'new-password' });
        const cab = cabSelect(u.cabinetId);
        const pc = h('input', { class: 'input', type: 'number', min: '0', max: '100', step: '0.01', placeholder: 'Общий', value: u.percent ?? '' });
        const dn = h('input', { class: 'input', list: 'wb-drivers', value: u.driverName || '', placeholder: 'Как в ФИО' });
        const di = h('input', { class: 'input', value: u.driverId || '', placeholder: 'Не задан' });
        const role = ROLE_NAMES[u.role] || u.role;
        return h('div', { class: 'list-item' },
          h('div', { class: 'row between' },
            h('span', { class: 't', text: u.name }),
            h('span', { class: 'small muted', text: `${u.login} · ${role}${u.role === 'owner' ? ` · ${cabName(u.cabinetId)}` : ''}` })),
          h('details', {}, h('summary', { text: 'Изменить' }),
            u.role === 'owner' ? h('label', { class: 'field' }, h('span', { text: 'Кабинет' }), cab) : null,
            u.role === 'driver' ? h('div', { class: 'grid2' },
              h('label', { class: 'field' }, h('span', { text: 'ФИО в WB' }), dn),
              h('label', { class: 'field' }, h('span', { text: 'ID водителя WB' }), di)) : null,
            h('div', { class: 'grid2' },
              u.role === 'driver' ? h('label', { class: 'field' }, h('span', { text: 'Комиссия, %' }), pc) : null,
              h('label', { class: 'field' }, h('span', { text: 'Новый пароль' }), pw)),
            h('div', { class: 'row' },
              h('button', { class: 'btn small light', type: 'button', text: 'Сохранить', onclick: async () => {
                try {
                  const body = {};
                  if (u.role === 'owner') body.cabinetId = cab.value;
                  if (u.role === 'driver') Object.assign(body, { driverName: dn.value, driverId: di.value, percent: pc.value });
                  if (pw.value) body.password = pw.value;
                  await api(`/api/users/${u.id}`, { method: 'PUT', body });
                  fill(out, msg('Сохранено.', 'ok'));
                  draw();
                } catch (e) { fill(out, msg(e.message)); }
              } }),
              u.id === state.me.id ? null : h('button', { class: 'btn small danger', type: 'button', text: 'Удалить', onclick: async () => {
                if (!confirm(`Удалить пользователя «${u.name}»?`)) return;
                await api(`/api/users/${u.id}`, { method: 'DELETE' });
                draw();
              } }))));
      }));
    }
    try { await draw(); } catch (e) { fill(out, msg(e.message)); }
  }

  async function settingsCommon(box) {
    const out = h('div');
    const pct = h('input', { class: 'input', type: 'number', min: '0', max: '100', step: '0.01' });
    const pending = h('input', { type: 'checkbox' });
    const minutes = h('input', { class: 'input', type: 'number', min: '0', max: '1440', step: '1' });
    try {
      const s = await api('/api/settings');
      pct.value = s.percent;
      pending.checked = s.deductPending;
      minutes.value = s.remainsMinutes;
    } catch { pct.value = 0; pending.checked = true; }
    pending.addEventListener('change', async () => {
      try {
        const s = await api('/api/settings', { method: 'POST', body: { deductPending: pending.checked } });
        fill(out, msg(s.deductPending ? 'Штрафы на рассмотрении вычитаются.' : 'Штрафы на рассмотрении не вычитаются.', 'ok'));
      } catch (e) { fill(out, msg(e.message)); pending.checked = !pending.checked; }
    });
    box.append(h('div', { class: 'card' },
      h('div', { class: 'label', text: 'Комиссия' }),
      h('p', { class: 'small muted', text: 'Процент от суммы путевых, вычитается до штрафов. 0 — без комиссии. У водителя-пользователя можно задать свой процент.' }),
      h('div', { class: 'row' }, h('div', { class: 'grow' }, pct),
        h('button', { class: 'btn light', type: 'button', text: 'Сохранить', onclick: async () => {
          try {
            const s = await api('/api/settings', { method: 'POST', body: { percent: pct.value } });
            fill(out, msg(`Комиссия: ${s.percent}%.`, 'ok'));
          } catch (e) { fill(out, msg(e.message)); }
        } })),
      h('label', { class: 'row check' }, pending,
        h('span', { text: 'Вычитать штрафы, которые WB ещё рассматривает' })),
      h('div', { class: 'label', text: 'Остатки: автообновление, минут' }),
      h('p', { class: 'small muted', text: '0 — выключено, иначе от 5 до 1440.' }),
      h('div', { class: 'row' }, h('div', { class: 'grow' }, minutes),
        h('button', { class: 'btn', type: 'button', text: 'Сохранить', onclick: async () => {
          try {
            const s = await api('/api/settings', { method: 'POST', body: { remainsMinutes: minutes.value } });
            fill(out, msg(s.remainsMinutes ? `Остатки обновляются каждые ${s.remainsMinutes} мин.` : 'Автообновление остатков выключено.', 'ok'));
          } catch (e) { fill(out, msg(e.message)); }
        } })),
      out));
  }

  function settingsPassword() {
    const out = h('div');
    const old = h('input', { class: 'input', type: 'password', autocomplete: 'current-password' });
    const nw = h('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
    return h('div', { class: 'card' },
      h('div', { class: 'label', text: 'Сменить пароль' }),
      h('div', { class: 'grid2' },
        h('label', { class: 'field' }, h('span', { text: 'Старый' }), old),
        h('label', { class: 'field' }, h('span', { text: 'Новый' }), nw)),
      h('button', { class: 'btn', type: 'button', text: 'Сменить', onclick: async () => {
        try {
          await api('/api/password', { method: 'POST', body: { old: old.value, new: nw.value } });
          old.value = ''; nw.value = '';
          fill(out, msg('Пароль изменён.', 'ok'));
        } catch (e) { fill(out, msg(e.message)); }
      } }),
      out);
  }

  const RENDER = {
    reports: renderReports,
    holds: renderHolds,
    remains: renderRemains,
    waysheets: renderWaysheets,
    summary: renderSummary,
    settings: renderSettings,
  };

  // ================================================================ старт

  window.addEventListener('hashchange', () => {
    const p = location.hash.slice(1);
    if (state.me && p && p !== state.page && RENDER[p]) { state.page = p; renderApp(); }
  });

  (async () => {
    try {
      const r = await api('/api/me');
      if (r.needsSetup) return renderAuth(true, r.setupNeedsCode);
      if (!r.user) return renderAuth(false);
      state.me = r.user;
      const p = location.hash.slice(1);
      if (RENDER[p]) state.page = p;
      renderApp();
    } catch (e) {
      root.replaceChildren(h('div', { class: 'auth' }, msg(e.message)));
    }
  })();
})();
