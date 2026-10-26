'use strict';

// Клиент неофициального API WB Логистики. Формат может поменяться без
// предупреждения — все отклонения превращаем в понятные ошибки.

const WB_URL = 'https://logistics.wb.ru/waysheets-netcore-service/api/v1/waysheet/external/receive-by-period';

class WbError extends Error {
  constructor(code, message, status) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

async function fetchWaysheets({ from, to, token, timeoutMs = 60000, fetchImpl = fetch }) {
  const body = {
    from,
    to,
    isNewMotivation: true,
    isNotConfirmedWithTransportRequests: null,
    officeId: null,
    paymentTypeId: null,
    routeId: null,
    supplierId: null,
  };

  let res;
  try {
    res = await fetchImpl(WB_URL, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json;charset=utf-8',
        Accept: 'application/json, text/plain, */*',
        Origin: 'https://ol.wildberries.ru',
        Referer: 'https://ol.wildberries.ru/',
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (e) {
    const reason = e && e.name === 'TimeoutError' ? 'WB не ответил вовремя' : 'нет связи с WB';
    throw new WbError('WB_NETWORK',
      `${reason}. Если сервер запущен на хостинге, WB может не пускать такие запросы — запустите на домашнем компьютере.`);
  }

  if (res.status === 401 || res.status === 403) {
    throw new WbError('KEY_EXPIRED', 'Ключ устарел, обновите.', res.status);
  }
  if (res.status === 429) {
    throw new WbError('WB_RATE_LIMIT', 'WB ограничил частоту запросов, попробуйте через пару минут.', 429);
  }
  if (!res.ok) {
    throw new WbError('WB_HTTP', `WB вернул ошибку ${res.status}.`, res.status);
  }

  let json;
  try {
    json = await res.json();
  } catch {
    throw new WbError('WB_FORMAT', 'WB вернул не JSON — возможно, API изменился.');
  }

  const list = Array.isArray(json) ? json
    : Array.isArray(json?.data) ? json.data
      : Array.isArray(json?.items) ? json.items
        : Array.isArray(json?.result) ? json.result
          : null;
  if (!list) throw new WbError('WB_FORMAT', 'Неожиданный формат ответа WB — возможно, API изменился.');
  return list;
}

module.exports = { fetchWaysheets, WbError, WB_URL };
