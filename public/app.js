'use strict';

(() => {
  const $ = (sel) => document.querySelector(sel);
  const el = (tag, attrs = {}, ...children) => {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === 'class') node.className = v;
      else if (k === 'text') node.textContent = v;
      else node.setAttribute(k, v);
    }
    for (const c of children) if (c !== null && c !== undefined) node.append(c);
    return node;
  };

  let report = null;

  // ---------- форматирование ----------

  function rub(kop) {
    const sign = kop < 0 ? '-' : '';
    const abs = Math.abs(kop);
    const r = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
    return `${sign}${r},${String(abs % 100).padStart(2, '0')}`;
  }

  // Дата/время из WB: показываем как есть, без пересчёта поясов браузера.
  function dt(s) {
    if (!s) return '—';
    const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(s);
    if (!m) return s;
    if (/(?:Z|[+-]\d{2}:?\d{2})$/i.test(s)) {
      return new Date(s).toLocaleString('ru-RU', {
        timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
      });
    }
    return `${m[3]}.${m[2]} ${m[4]}:${m[5]}`;
  }

  function isoDay(d) {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }

  function ruDay(s) {
    const [y, m, d] = s.split('-');
    return `${d}.${m}.${y}`;
  }

  // ---------- API ----------

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      ...opts,
      headers: opts.body ? { 'Content-Type': 'application/json' } : undefined,
    });
    let data = null;
    try { data = await res.json(); } catch { /* пусто */ }
    if (!res.ok) {
      const err = new Error((data && data.error && data.error.message) || `Ошибка ${res.status}`);
      err.code = data && data.error && data.error.code;
      throw err;
    }
    return data;
  }

  function showMessage(text, kind = 'error') {
    const box = $('#message');
    box.textContent = text;
    box.className = `message ${kind}`;
    box.hidden = !text;
  }

  // ---------- ключ ----------

  function renderKey(info) {
    const badge = $('#keyToggle');
    const status = $('#keyStatus');
    badge.className = 'key-badge';
    if (!info.configured) {
      badge.textContent = info.broken ? 'Ключ не читается' : 'Ключ не задан';
      badge.classList.add('bad');
      status.textContent = info.broken
        ? 'Сохранённый ключ не удалось расшифровать (сменился WB_KEY_SECRET?). Вставьте ключ заново.'
        : 'Ключ ещё не задан.';
      $('#keyPanel').hidden = false;
      return;
    }
    if (info.expired) {
      badge.textContent = 'Ключ устарел';
      badge.classList.add('bad');
      status.textContent = 'Срок ключа истёк. Ключ устарел, обновите.';
      $('#keyPanel').hidden = false;
      return;
    }
    const until = info.expiresAt ? new Date(info.expiresAt).toLocaleDateString('ru-RU') : 'неизвестно';
    badge.textContent = info.daysLeft !== null ? `Ключ: ${info.daysLeft} дн.` : 'Ключ задан';
    badge.classList.add(info.daysLeft !== null && info.daysLeft < 14 ? 'warn' : 'ok');
    status.textContent = `Ключ сохранён. Действует до ${until}${info.daysLeft !== null ? ` (осталось ${info.daysLeft} дн.)` : ''}.`;
  }

  async function loadKey() {
    try { renderKey(await api('/api/key')); } catch (e) { $('#keyStatus').textContent = e.message; }
  }

  $('#keyToggle').addEventListener('click', () => { $('#keyPanel').hidden = !$('#keyPanel').hidden; });

  $('#keyForm').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const input = $('#keyInput');
    if (!input.value.trim()) return;
    try {
      const info = await api('/api/key', { method: 'POST', body: JSON.stringify({ value: input.value }) });
      input.value = '';
      renderKey(info);
      showMessage('Ключ сохранён.', 'warn');
      $('#keyPanel').hidden = true;
      run();
    } catch (e) {
      showMessage(e.message);
    }
  });

  $('#keyDelete').addEventListener('click', async () => {
    if (!confirm('Удалить сохранённый ключ с сервера?')) return;
    try { renderKey(await api('/api/key', { method: 'DELETE' })); } catch (e) { showMessage(e.message); }
  });

  // ---------- период ----------

  function setRange(kind) {
    const now = new Date();
    let from;
    let to;
    if (kind === 'today') {
      from = to = new Date(now);
    } else if (kind === 'yesterday') {
      from = to = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
    } else if (kind === 'week') {
      const dow = (now.getDay() + 6) % 7; // пн = 0
      from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - dow - 7);
      to = new Date(from.getFullYear(), from.getMonth(), from.getDate() + 6);
    } else if (kind === 'month') {
      from = new Date(now.getFullYear(), now.getMonth(), 1);
      to = new Date(now);
    }
    $('#from').value = isoDay(from);
    $('#to').value = isoDay(to);
  }

  document.querySelectorAll('[data-range]').forEach((b) => {
    b.addEventListener('click', () => { setRange(b.dataset.range); run(); });
  });

  $('#periodForm').addEventListener('submit', (ev) => { ev.preventDefault(); run(); });

  $('#savePercent').addEventListener('click', async () => {
    try {
      const s = await api('/api/settings', { method: 'POST', body: JSON.stringify({ percent: $('#percent').value }) });
      showMessage(`Процент удержания по умолчанию: ${s.percent}%.`, 'warn');
    } catch (e) { showMessage(e.message); }
  });

  // ---------- расчёт ----------

  async function run() {
    const from = $('#from').value;
    const to = $('#to').value;
    if (!from || !to) return;
    const btn = $('#periodForm button[type="submit"]');
    btn.disabled = true;
    btn.textContent = 'Считаю…';
    showMessage('');
    try {
      const q = new URLSearchParams({ from, to, percent: $('#percent').value });
      report = await api(`/api/report?${q}`);
      render();
    } catch (e) {
      if (e.code === 'KEY_EXPIRED' || e.code === 'NO_KEY') {
        $('#keyPanel').hidden = false;
        loadKey();
        if (e.code === 'KEY_EXPIRED') {
          const badge = $('#keyToggle');
          badge.className = 'key-badge bad';
          badge.textContent = 'Ключ устарел';
          $('#keyStatus').textContent = 'WB отклонил ключ. Ключ устарел, обновите.';
        }
      }
      showMessage(e.message);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Рассчитать';
    }
  }

  function tile(label, value, cls = '') {
    return el('div', { class: `tile ${cls}` }, el('div', { class: 'label', text: label }), el('div', { class: 'value', text: value }));
  }

  function tripsTable(trips) {
    const tbody = el('tbody');
    for (const t of trips) {
      const flags = [];
      if (t.isFineUnderConsideration) flags.push(el('span', { class: 'flag', text: 'на рассмотрении' }));
      if (t.isApproved === false) flags.push(el('span', { class: 'flag', text: 'не подтверждён' }));
      const route = t.routePoints.length
        ? t.routePoints.map((p) => (p.id ? `${p.name} (${p.id})` : p.name)).join(' → ')
        : t.logRouteName;
      const boxes = t.boxCountTotal !== null ? `${t.boxCountDelivered ?? '—'}/${t.boxCountTotal}` : '—';
      tbody.append(el('tr', {},
        el('td', {}, String(t.id), ...flags),
        el('td', { text: dt(t.openDt) }),
        el('td', { text: dt(t.closeDt) }),
        el('td', { text: t.vehicleNumberPlate || '—' }),
        el('td', { class: 'route' }, t.srcOfficeName ? `${t.srcOfficeName}: ` : '', route || '—'),
        el('td', { class: 'num', text: t.shksCount ?? '—' }),
        el('td', { class: 'num', text: boxes }),
        el('td', { class: 'num', text: t.planMileage ?? '—' }),
        el('td', { class: 'num', text: t.totalSumReturn !== null ? rub(t.totalSumReturn) : '—' }),
        el('td', { class: 'num', text: rub(t.price) }),
        el('td', { class: `num ${t.fine ? 'fine' : ''}`, text: t.fine ? rub(t.fine) : '—' }),
      ));
    }
    const head = ['Рейс', 'Выезд', 'Закрыт', 'Машина', 'Маршрут', 'ШК', 'Коробки', 'Км (план)', 'Возвраты', 'Сумма', 'Штраф'];
    return el('table', { class: 'trips' },
      el('thead', {}, el('tr', {}, ...head.map((h, i) => el('th', { class: i >= 5 ? 'num' : '', text: h })))),
      tbody);
  }

  function render() {
    const r = report;
    $('#result').hidden = false;
    const period = r.from === r.to ? ruDay(r.from) : `${ruDay(r.from)} — ${ruDay(r.to)}`;

    const totals = $('#totals');
    totals.replaceChildren(
      tile('Сумма', rub(r.totals.sum)),
      tile(`Удержание ${String(r.percent).replace('.', ',')}%`, rub(r.totals.retention)),
      tile('Штрафы', rub(r.totals.fines)),
      tile('К выплате', rub(r.totals.payout), 'main'),
      tile('Водителей / рейсов', `${r.drivers.length} / ${r.totals.tripsCount}`),
    );

    const fetched = new Date(r.fetchedAt).toLocaleTimeString('ru-RU');
    $('#meta').textContent = `${period} · данные WB на ${fetched}`;

    renderDrivers();

    const tb = $('#transit tbody');
    tb.replaceChildren(...r.inTransit.map((t) => el('tr', {},
      el('td', { text: t.driverName }),
      el('td', { text: t.vehicleNumberPlate || '—' }),
      el('td', { text: dt(t.openDt) }),
      el('td', { class: 'route', text: t.logRouteName || '—' }),
      el('td', { class: 'num', text: rub(t.price) }),
    )));
    $('#transitCount').textContent = r.inTransit.length ? `(${r.inTransit.length})` : '';
    $('#transitBlock').hidden = r.inTransit.length === 0;

    if (r.drivers.length === 0) {
      showMessage(r.inTransit.length
        ? 'Закрытых рейсов за период нет — все ещё в пути.'
        : 'За этот период рейсов нет.', 'warn');
    }
  }

  function renderDrivers() {
    const q = $('#filter').value.trim().toLowerCase();
    const rows = report.drivers.filter((d) => !q
      || d.driverName.toLowerCase().includes(q)
      || d.plates.some((p) => p.toLowerCase().includes(q)));

    const tbody = $('#drivers tbody');
    tbody.replaceChildren();
    for (const d of rows) {
      const tr = el('tr', { class: 'driver' },
        el('td', {}, d.driverName, d.hasFineUnderConsideration ? el('span', { class: 'flag', text: 'штраф на рассмотрении' }) : null),
        el('td', { text: d.plates.join(', ') || '—' }),
        el('td', { class: 'num', text: d.tripsCount }),
        el('td', { class: 'num', text: rub(d.sum) }),
        el('td', { class: 'num', text: rub(d.retention) }),
        el('td', { class: `num ${d.fines ? 'fine' : ''}`, text: d.fines ? rub(d.fines) : '—' }),
        el('td', { class: 'num payout', text: rub(d.payout) }),
      );
      const details = el('tr', { class: 'details' }, el('td', { colspan: '7' }, tripsTable(d.trips)));
      details.hidden = true;
      tr.addEventListener('click', () => {
        details.hidden = !details.hidden;
        tr.classList.toggle('open', !details.hidden);
      });
      tbody.append(tr, details);
    }

    const t = report.totals;
    $('#drivers tfoot').replaceChildren(el('tr', {},
      el('td', { text: 'Итого' }),
      el('td', { text: '' }),
      el('td', { class: 'num', text: t.tripsCount }),
      el('td', { class: 'num', text: rub(t.sum) }),
      el('td', { class: 'num', text: rub(t.retention) }),
      el('td', { class: 'num', text: rub(t.fines) }),
      el('td', { class: 'num', text: rub(t.payout) }),
    ));
  }

  $('#filter').addEventListener('input', () => { if (report) renderDrivers(); });

  // ---------- выгрузка ----------

  function csvCell(v) {
    const s = String(v ?? '');
    return /[";\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  }

  $('#csv').addEventListener('click', () => {
    if (!report) return;
    const money = (k) => rub(k).replace(/ /g, '');
    const lines = [['Водитель', 'ID водителя', 'Машина', 'Рейсов', 'Сумма', 'Удержание', 'Штрафы', 'К выплате']];
    for (const d of report.drivers) {
      lines.push([d.driverName, d.driverId ?? '', d.plates.join(', '), d.tripsCount,
        money(d.sum), money(d.retention), money(d.fines), money(d.payout)]);
    }
    const t = report.totals;
    lines.push(['Итого', '', '', t.tripsCount, money(t.sum), money(t.retention), money(t.fines), money(t.payout)]);
    // Excel в русской локали ждёт «;» и BOM.
    const csv = `﻿${lines.map((l) => l.map(csvCell).join(';')).join('\r\n')}`;
    const a = el('a', {
      href: URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' })),
      download: `zarplata_${report.from}_${report.to}.csv`,
    });
    document.body.append(a);
    a.click();
    a.remove();
  });

  $('#print').addEventListener('click', () => window.print());

  // ---------- старт ----------

  (async () => {
    setRange('yesterday');
    await loadKey();
    try {
      const s = await api('/api/settings');
      $('#percent').value = s.percent;
    } catch {
      $('#percent').value = 20;
    }
  })();
})();
