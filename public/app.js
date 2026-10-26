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

  function renderAuth(needsSetup = false) {
    const login = h('input', { class: 'input', autocomplete: 'username', required: true });
    const pass = h('input', { class: 'input', type: 'password', autocomplete: needsSetup ? 'new-password' : 'current-password', required: true });
    const name = h('input', { class: 'input', required: true });
    const out = h('div');
    const form = h('form', { class: 'card', onsubmit: async (e) => {
      e.preventDefault();
      fill(out, );
      try {
        const body = { login: login.value, password: pass.value };
        if (needsSetup) body.name = name.value;
        const r = await api(needsSetup ? '/api/setup' : '/api/login', { method: 'POST', body });
        state.me = r.user;
        renderApp();
      } catch (err) { fill(out, msg(err.message)); }
    } },
    needsSetup ? h('p', { class: 'small muted', text: 'Первый запуск: создайте администратора. Он добавит кабинеты WB и сотрудников.' }) : null,
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
    { id: 'rating', title: 'Рейтинг', ico: '🏆' },
    { id: 'waysheets', title: 'Путевые листы', ico: '🗒' },
    { id: 'summary', title: 'Ведомость', ico: '📊', admin: true },
    { id: 'settings', title: 'Настройки', ico: '⚙' },
  ];

  function openMenu() {
    const bg = h('div', { class: 'drawer-bg', onclick: () => close() });
    const close = () => { bg.remove(); drawer.remove(); };
    const drawer = h('nav', { class: 'drawer' },
      h('div', { class: 'head' },
        h('button', { class: 'btn menu-btn', type: 'button', text: '☰ Меню', onclick: () => close() }),
        h('button', { class: 'icon-btn', type: 'button', text: '✕', onclick: () => close() })),
      h('div', { class: 'who' },
        h('div', { class: 'label', text: state.me.role === 'admin' ? 'Администратор' : 'Сотрудник' }),
        h('div', { class: 'n', text: state.me.name }),
        h('div', { class: 'small muted', text: 'LogisticaWB' })),
      ...PAGES.filter((p) => !p.admin || state.me.role === 'admin').map((p) => h('button', {
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
    const page = PAGES.find((p) => p.id === state.page && (!p.admin || state.me.role === 'admin')) || PAGES[0];
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

  async function employeeSelect(onChange) {
    if (state.me.role !== 'admin') return null;
    const sel = h('select', { class: 'input', onchange: () => { state.employee = sel.value; onChange(); } },
      h('option', { value: '', text: `${state.me.name} (я)` }));
    try {
      const { users } = await api('/api/users');
      for (const u of users) {
        if (u.id === state.me.id) continue;
        sel.append(h('option', { value: u.id, text: u.name, selected: u.id === state.employee }));
      }
    } catch { /* без списка */ }
    return h('label', { class: 'field' }, h('span', { text: 'Сотрудник' }), sel);
  }

  // ================================================================ Отчеты

  async function renderReports(main) {
    const body = h('div');
    const card = h('div', { class: 'card' }, head('📄', 'Отчеты', 'Период и итог к выплате'));
    main.append(card);
    const sel = await employeeSelect(load);
    card.append(...[sel, periodField(() => renderApp()), body].filter(Boolean));

    async function load() {
      fill(body, h('div', { class: 'status', text: 'Загружаю…' }));
      try {
        const q = new URLSearchParams({ from: state.period.from, to: state.period.to });
        if (state.employee) q.set('employee', state.employee);
        const r = await api(`/api/report?${q}`);
        const copyBtn = h('button', { class: 'btn', type: 'button', text: 'Копировать отчет', onclick: async () => {
          copyBtn.textContent = (await copyText(r.text)) ? 'Скопировано' : 'Не удалось скопировать';
          setTimeout(() => { copyBtn.textContent = 'Копировать отчет'; }, 2000);
        } });
        fill(body, 
          ...r.warnings.map((w) => msg(w, 'warn')),
          h('div', { class: 'stat' }, h('div', { class: 'label', text: 'Путевых записей' }), h('div', { class: 'value', text: String(r.tripsCount) })),
          h('div', { class: 'stat' }, h('div', { class: 'label', text: 'К выплате' }), h('div', { class: 'value', text: rub(r.payout) })),
          h('div', { class: 'report-text', text: r.text }),
          r.inTransit ? h('p', { class: 'small muted', text: `Ещё в пути (не закрыты, в расчёт не вошли): ${r.inTransit}` }) : null,
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
    const body = h('div');
    main.append(h('div', { class: 'card' },
      head('🧾', 'Удержания WB', 'Штрафы по путевым листам'),
      periodField(() => renderApp()), body));
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
          state.me.role === 'admin' ? h('div', { text: t.driverName }) : null,
          h('div', { class: 'small muted', text: `${t.cabinetName} · ${t.closeDt ? `закрыт ${dt(t.closeDt)}` : `в пути с ${dt(t.openDt)}`}` }),
          h('div', {}, fineStatusPill(t)),
          ...fineTypeLines(t.fineParts))));
    } catch (e) {
      fill(body, msg(e.message));
    }
  }

  // ================================================================ Остатки и Рейтинг

  function renderNeedsApi(ico, title, sub) {
    return (main) => {
      main.append(h('div', { class: 'card' },
        head(ico, title, sub),
        h('p', { text: 'Для этого раздела нужен ещё один запрос WB — его пока нет в программе.' }),
        h('p', { class: 'small muted', text: 'Как его достать: откройте этот раздел на ol.wildberries.ru в браузере на компьютере, '
          + 'нажмите F12 → вкладка Network (Сеть), обновите страницу и найдите запрос с данными. '
          + 'Пришлите разработчику адрес запроса, тело запроса и пример ответа. Заголовок Authorization (ключ) не присылайте.' })));
    };
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
        h('label', { class: 'field' }, h('span', { text: 'Удержание, %' }), pct)),
      h('button', { class: 'btn light', type: 'button', text: 'Рассчитать', onclick: load })), body);
    try { pct.value = (await api('/api/settings')).percent; } catch { pct.value = 20; }

    async function load() {
      fill(body, h('div', { class: 'status', text: 'Загружаю…' }));
      try {
        const r = await api(`/api/summary?${new URLSearchParams({ from: state.period.from, to: state.period.to, percent: pct.value })}`);
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
            h('td', { class: 'num', text: rub(d.payout) }));
          const det = h('tr', { hidden: true }, h('td', { colspan: '7' },
            ...d.trips.map((x) => h('div', { class: 'small muted', text: `№ ${x.id} · ${x.vehicleNumberPlate || ''} · закрыт ${dt(x.closeDt)} · ${rub(x.price)}${x.fine ? ` · штраф ${rub(x.fine)}` : ''}` }))));
          tr.addEventListener('click', () => { det.hidden = !det.hidden; });
          tbody.append(tr, det);
        }
        const csvBtn = h('button', { class: 'btn', type: 'button', text: 'Скачать CSV', onclick: () => downloadCsv(r) });
        fill(body, 
          ...r.warnings.map((w) => msg(w, 'warn')),
          h('div', { class: 'grid2' },
            h('div', { class: 'stat' }, h('div', { class: 'label', text: 'Сумма' }), h('div', { class: 'value', text: rub(t.sum) })),
            h('div', { class: 'stat' }, h('div', { class: 'label', text: 'К выплате' }), h('div', { class: 'value', text: rub(t.payout) }))),
          h('div', { class: 'table-wrap' }, h('table', {},
            h('thead', {}, h('tr', {}, ...['Водитель', 'Рейсов', 'Сумма', 'Удержание', 'Штрафы', 'На рассм.', 'К выплате']
              .map((x, i) => h('th', { class: i ? 'num' : '', text: x })))),
            tbody,
            h('tfoot', {}, h('tr', {},
              h('td', { text: 'Итого' }), h('td', { class: 'num', text: t.tripsCount }), h('td', { class: 'num', text: rub(t.sum) }),
              h('td', { class: 'num', text: rub(t.retention) }), h('td', { class: 'num', text: rub(t.fines) }),
              h('td', { class: 'num', text: rub(t.finesPending) }),
              h('td', { class: 'num', text: rub(t.payout) }))))),
          h('p', { class: 'small muted', text: `В пути (не вошли): ${r.inTransit.length}. Данные WB на ${localTime(r.fetchedAt)}.` }),
          csvBtn);
      } catch (e) { fill(body, msg(e.message)); }
    }
    load();
  }

  function downloadCsv(r) {
    const money = (k) => rub(k).replace(/ /g, '');
    const cell = (v) => { const s = String(v ?? ''); return /[";\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
    const lines = [['Водитель', 'ID водителя', 'Рейсов', 'Сумма', 'Удержание', 'Штрафы', 'К выплате']];
    for (const d of r.drivers) lines.push([d.driverName, d.driverId ?? '', d.tripsCount, money(d.sum), money(d.retention), money(d.fines), money(d.payout)]);
    const t = r.totals;
    lines.push(['Итого', '', t.tripsCount, money(t.sum), money(t.retention), money(t.fines), money(t.payout)]);
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
    return `ключ до ${new Date(k.expiresAt).toLocaleDateString('ru-RU')} (${k.daysLeft} дн.)`;
  }

  async function settingsCabinets(box) {
    const out = h('div');
    const list = h('div');
    const name = h('input', { class: 'input', placeholder: 'ИП Иванов Иван' });
    const key = h('input', { class: 'input', type: 'password', placeholder: 'https://ol.wildberries.ru/…bearer=…', autocomplete: 'off', spellcheck: 'false' });
    box.append(h('div', { class: 'card' },
      h('div', { class: 'label', text: 'Кабинеты WB' }),
      h('p', { class: 'small muted', text: 'Для каждого ИП: откройте ol.wildberries.ru под этим кабинетом, скопируйте адрес страницы целиком (в нём есть bearer=) и вставьте сюда. Ключ хранится на сервере в зашифрованном виде.' }),
      list,
      h('details', {}, h('summary', { text: '+ Добавить кабинет' }),
        h('label', { class: 'field' }, h('span', { text: 'Название' }), name),
        h('label', { class: 'field' }, h('span', { text: 'Адрес страницы с bearer= или ключ' }), key),
        h('button', { class: 'btn light', type: 'button', text: 'Добавить', onclick: async () => {
          try {
            await api('/api/cabinets', { method: 'POST', body: { name: name.value, value: key.value } });
            name.value = ''; key.value = '';
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
          const bad = !c.key.configured || c.key.expired;
          return h('div', { class: 'list-item' },
            h('div', { class: 'row between' },
              h('span', { class: 't', text: c.name }),
              h('span', { class: `small ${bad ? 'pill warn' : 'muted'}`, text: keyText(c.key) })),
            h('details', {}, h('summary', { text: 'Обновить ключ / удалить' }),
              h('div', { class: 'field' }, newKey),
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
    const f = {
      name: h('input', { class: 'input' }),
      login: h('input', { class: 'input', autocomplete: 'off' }),
      password: h('input', { class: 'input', type: 'password', autocomplete: 'new-password' }),
      role: h('select', { class: 'input' }, h('option', { value: 'driver', text: 'Водитель' }), h('option', { value: 'admin', text: 'Администратор' })),
      driverName: h('input', { class: 'input', list: 'wb-drivers', placeholder: 'Как в WB, если отличается от ФИО' }),
      driverId: h('input', { class: 'input', placeholder: 'Необязательно' }),
      percent: h('input', { class: 'input', type: 'number', min: '0', max: '100', step: '0.01', placeholder: 'Общий' }),
    };
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

    box.append(h('div', { class: 'card' },
      h('div', { class: 'label', text: 'Сотрудники' }),
      h('p', { class: 'small muted', text: 'Водитель видит только свои путевые и отчёты. Путевые находятся по ID водителя WB, а если его нет — по ФИО.' }),
      list,
      h('details', {}, h('summary', { text: '+ Добавить сотрудника' }),
        h('label', { class: 'field' }, h('span', { text: 'ФИО' }), f.name),
        h('div', { class: 'grid2' },
          h('label', { class: 'field' }, h('span', { text: 'Логин' }), f.login),
          h('label', { class: 'field' }, h('span', { text: 'Пароль' }), f.password)),
        h('label', { class: 'field' }, h('span', { text: 'Роль' }), f.role),
        h('label', { class: 'field' }, h('span', { text: 'ФИО в WB' }), f.driverName, dl),
        loadDrivers,
        h('div', { class: 'grid2' },
          h('label', { class: 'field' }, h('span', { text: 'ID водителя WB' }), f.driverId),
          h('label', { class: 'field' }, h('span', { text: 'Удержание, %' }), f.percent)),
        h('button', { class: 'btn light', type: 'button', text: 'Добавить', onclick: async () => {
          try {
            const body = Object.fromEntries(Object.entries(f).map(([k, el]) => [k, el.value]));
            await api('/api/users', { method: 'POST', body });
            Object.values(f).forEach((el) => { if (el.tagName === 'INPUT') el.value = ''; });
            fill(out, msg('Сотрудник добавлен.', 'ok'));
            draw();
          } catch (e) { fill(out, msg(e.message)); }
        } })),
      out));

    async function draw() {
      const { users } = await api('/api/users');
      fill(list, ...users.map((u) => {
        const pw = h('input', { class: 'input', type: 'password', placeholder: 'Новый пароль', autocomplete: 'new-password' });
        const pc = h('input', { class: 'input', type: 'number', min: '0', max: '100', step: '0.01', placeholder: 'Общий', value: u.percent ?? '' });
        const dn = h('input', { class: 'input', list: 'wb-drivers', value: u.driverName || '', placeholder: 'Как в ФИО' });
        const di = h('input', { class: 'input', value: u.driverId || '', placeholder: 'Не задан' });
        return h('div', { class: 'list-item' },
          h('div', { class: 'row between' },
            h('span', { class: 't', text: u.name }),
            h('span', { class: 'small muted', text: `${u.login} · ${u.role === 'admin' ? 'администратор' : 'водитель'}${u.percent !== null ? ` · ${u.percent}%` : ''}` })),
          h('details', {}, h('summary', { text: 'Изменить' }),
            h('div', { class: 'grid2' },
              h('label', { class: 'field' }, h('span', { text: 'ФИО в WB' }), dn),
              h('label', { class: 'field' }, h('span', { text: 'ID водителя WB' }), di)),
            h('div', { class: 'grid2' },
              h('label', { class: 'field' }, h('span', { text: 'Удержание, %' }), pc),
              h('label', { class: 'field' }, h('span', { text: 'Пароль' }), pw)),
            h('div', { class: 'row' },
              h('button', { class: 'btn small light', type: 'button', text: 'Сохранить', onclick: async () => {
                try {
                  const body = { driverName: dn.value, driverId: di.value, percent: pc.value };
                  if (pw.value) body.password = pw.value;
                  await api(`/api/users/${u.id}`, { method: 'PUT', body });
                  fill(out, msg('Сохранено.', 'ok'));
                  draw();
                } catch (e) { fill(out, msg(e.message)); }
              } }),
              u.id === state.me.id ? null : h('button', { class: 'btn small danger', type: 'button', text: 'Удалить', onclick: async () => {
                if (!confirm(`Удалить сотрудника «${u.name}»?`)) return;
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
    try {
      const s = await api('/api/settings');
      pct.value = s.percent;
      pending.checked = s.deductPending;
    } catch { pct.value = 20; pending.checked = true; }
    pending.addEventListener('change', async () => {
      try {
        const s = await api('/api/settings', { method: 'POST', body: { deductPending: pending.checked } });
        fill(out, msg(s.deductPending ? 'Штрафы на рассмотрении вычитаются.' : 'Штрафы на рассмотрении не вычитаются.', 'ok'));
      } catch (e) { fill(out, msg(e.message)); pending.checked = !pending.checked; }
    });
    box.append(h('div', { class: 'card' },
      h('div', { class: 'label', text: 'Общее удержание' }),
      h('p', { class: 'small muted', text: 'Процент от суммы путевых. У сотрудника можно задать свой процент.' }),
      h('div', { class: 'row' }, h('div', { class: 'grow' }, pct),
        h('button', { class: 'btn light', type: 'button', text: 'Сохранить', onclick: async () => {
          try {
            const s = await api('/api/settings', { method: 'POST', body: { percent: pct.value } });
            fill(out, msg(`Удержание: ${s.percent}%.`, 'ok'));
          } catch (e) { fill(out, msg(e.message)); }
        } })),
      h('label', { class: 'row check' }, pending,
        h('span', { text: 'Вычитать штрафы, которые WB ещё рассматривает' })),
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
    remains: renderNeedsApi('📦', 'Остатки', 'Маршруты, коробки, КГТ и ШК'),
    rating: renderNeedsApi('🏆', 'Рейтинг', 'Рейтинг водителей'),
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
      if (r.needsSetup) return renderAuth(true);
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
