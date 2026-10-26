#!/usr/bin/env bash
# Установка (и обновление) LogisticaWB на сервер Ubuntu 22.04 / 24.04.
#
#   sudo bash deploy/install.sh                 # адрес вида https://1-2-3-4.sslip.io
#   sudo bash deploy/install.sh zarplata.ru     # свой домен (A-запись должна указывать на сервер)
#
# Обновление с компьютера (PowerShell, в папке с новым архивом):
#   scp logistica-wb.zip root@IP: ; ssh root@IP "unzip -o logistica-wb.zip && bash logistica-wb/deploy/install.sh ДОМЕН"
#
# Повторный запуск обновляет программу; данные, ключи и пароли сохраняются.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Запустите через sudo: sudo bash deploy/install.sh" >&2
  exit 1
fi

SRC="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR=/opt/logistica-wb
DATA_DIR=/var/lib/logistica-wb
ENV_FILE=/etc/logistica-wb.env
SERVICE=logistica-wb
PORT=3000

DOMAIN="${1:-}"
if [[ -z "$DOMAIN" ]]; then
  # Внешний IP: у сервиса (не дольше 5 секунд), иначе — первый адрес самого сервера.
  IP="$(curl -4 -fsS -m 5 https://api.ipify.org || true)"
  [[ -n "$IP" ]] || IP="$(hostname -I | awk '{print $1}')"
  DOMAIN="${IP//./-}.sslip.io"
fi

echo "==> Пакеты (несколько минут; на свежем сервере дольше — система сама ставит обновления)"
# Если зеркало пакетов, которое выставил хостинг, недоступно — переключаемся на зеркало Яндекса.
apt-get update -qq 2>&1 | tee /tmp/apt-update.log || true
if grep -qE "Failed to fetch|Could not connect|Unable to connect" /tmp/apt-update.log; then
  echo "    Зеркало пакетов недоступно, переключаюсь на mirror.yandex.ru"
  sed -i -E 's#https?://[a-z.]*archive\.ubuntu\.com/ubuntu#http://mirror.yandex.ru/ubuntu#g; s#https?://security\.ubuntu\.com/ubuntu#http://mirror.yandex.ru/ubuntu#g' \
    /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true
  apt-get update -qq
fi
apt-get install -y -qq python3 python3-cryptography curl debian-keyring debian-archive-keyring apt-transport-https gnupg >/dev/null
if ! command -v caddy >/dev/null; then
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq
  apt-get install -y -qq caddy >/dev/null
fi

echo "==> Пользователь и папки"
id -u logistica >/dev/null 2>&1 || useradd --system --home "$DATA_DIR" --shell /usr/sbin/nologin logistica
mkdir -p "$APP_DIR" "$DATA_DIR"
cp -r "$SRC"/app.py "$SRC"/core.py "$SRC"/remains.py "$SRC"/holds.py "$SRC"/public "$APP_DIR"/
chown -R root:root "$APP_DIR"
chown -R logistica:logistica "$DATA_DIR"
chmod 700 "$DATA_DIR"

echo "==> Настройки"
if [[ ! -f "$ENV_FILE" ]]; then
  # Секрет шифрования ключей WB. Без него ключи не расшифровать — не теряйте этот файл.
  SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
  CODE="$(python3 -c 'import secrets; print(secrets.token_hex(4))')"
  cat > "$ENV_FILE" <<EOF
WB_KEY_SECRET=$SECRET
SETUP_CODE=$CODE
DATA_DIR=$DATA_DIR
HOST=127.0.0.1
PORT=$PORT
NO_BROWSER=1
TRUST_PROXY=1
COOKIE_SECURE=1
TZ=Europe/Moscow
EOF
fi
chmod 600 "$ENV_FILE"

cat > /etc/systemd/system/$SERVICE.service <<EOF
[Unit]
Description=LogisticaWB — зарплата водителей по данным WB
After=network-online.target
Wants=network-online.target

[Service]
User=logistica
Group=logistica
EnvironmentFile=$ENV_FILE
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/python3 $APP_DIR/app.py
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$DATA_DIR
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

# HTTPS: сертификат от Let's Encrypt, а если он недоступен — от ZeroSSL (Caddy переключается сам,
# для ZeroSSL нужен e-mail). Если недоступны оба — сайт работает по http.
# HTTP_ONLY=1 в $ENV_FILE — сайт остаётся на http и при обновлениях (если сертификат не выдают).
reachable() { [[ "$(curl -s -m 15 -o /dev/null -w '%{http_code}' "$1" || true)" != "000" ]]; }
if grep -q '^HTTP_ONLY=1' "$ENV_FILE"; then
  HTTPS_OK=no
elif reachable https://acme-v02.api.letsencrypt.org/directory || reachable https://acme.zerossl.com/v2/DV90; then
  HTTPS_OK=yes
else
  HTTPS_OK=no
fi
if [[ $HTTPS_OK == yes ]]; then
  SCHEME=https
  cat > /etc/caddy/Caddyfile <<EOF
{
	email ${EMAIL:-admin@$DOMAIN}
}

$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:$PORT
}
EOF
  sed -i 's/^#COOKIE_SECURE=.*/COOKIE_SECURE=1/' "$ENV_FILE"
else
  SCHEME=http
  grep -q '^HTTP_ONLY=1' "$ENV_FILE" || echo "HTTP_ONLY=1" >> "$ENV_FILE"
  echo "    Сайт работает на http (без замка в браузере). Чтобы снова пробовать https: убрать HTTP_ONLY из $ENV_FILE."
  cat > /etc/caddy/Caddyfile <<EOF
:80 {
	encode gzip
	reverse_proxy 127.0.0.1:$PORT
}
EOF
  # Без https браузер не отдаст cookie с флагом Secure — выключаем его.
  sed -i 's/^COOKIE_SECURE=.*/#COOKIE_SECURE=1/' "$ENV_FILE"
fi

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
fi

echo "==> Запуск"
systemctl daemon-reload
systemctl enable --now $SERVICE >/dev/null
systemctl restart $SERVICE
systemctl reload caddy || systemctl restart caddy

echo "==> Проверка связи с WB"
HTTP="$(curl -s -o /dev/null -w '%{http_code}' -m 20 -X POST \
  -H 'Content-Type: application/json' -H 'Origin: https://ol.wildberries.ru' \
  https://logistics.wb.ru/waysheets-netcore-service/api/v1/waysheet/external/receive-by-period -d '{}' || true)"
case "$HTTP" in
  401) echo "    WB отвечает (401 без ключа — так и должно быть). Похоже, сервер не заблокирован." ;;
  000) echo "    !!! WB не ответил. Возможно, WB блокирует этот хостинг — попробуйте другой." ;;
  *)   echo "    WB ответил $HTTP. Окончательно проверьте, загрузив путевые на сайте." ;;
esac

sleep 2
if systemctl is-active --quiet $SERVICE; then
  echo
  echo "Готово. Сайт: $SCHEME://$DOMAIN"
  if python3 -c "import json,sys; sys.exit(0 if json.load(open('$DATA_DIR/db.json'))['users'] else 1)" 2>/dev/null; then
    echo "Обновление установлено. Данные, ключи и пароли сохранены."
  else
    echo "Откройте его и создайте администратора (первый вход)."
    echo "Код установки: $(grep '^SETUP_CODE=' "$ENV_FILE" | cut -d= -f2)"
  fi
  echo "Логи: journalctl -u $SERVICE -f"
else
  echo "Сайт не запустился. Логи: journalctl -u $SERVICE -n 50" >&2
  exit 1
fi
