#!/usr/bin/env bash
# ============================================================================
# PulseIM v2.0 生产服务器一键初始化脚本（Ubuntu 22.04 / 24.04，零 Docker）
# 安装：Temurin JDK 21 + MySQL 8 + Redis 7 + RabbitMQ + Nginx + Certbot
# 用法：sudo bash deploy/scripts/setup-ubuntu.sh
# ============================================================================
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "请使用 sudo 或 root 权限运行本脚本" >&2
  exit 1
fi

echo "==> [1/6] 更新基础包并安装工具链"
apt-get update
apt-get install -y curl wget gnupg ca-certificates lsb-release apt-transport-https unzip jq ufw

echo "==> [2/6] 安装 Eclipse Temurin JDK 21"
if ! java -version 2>&1 | grep -q 'version "21'; then
  mkdir -p /etc/apt/keyrings
  wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update
  apt-get install -y temurin-21-jre
fi
java -version

echo "==> [3/6] 安装原生中间件（MySQL 8 / Redis / RabbitMQ / Nginx / Certbot）"
apt-get install -y mysql-server redis-server rabbitmq-server nginx certbot python3-certbot-nginx

systemctl enable --now mysql redis-server rabbitmq-server nginx

echo "==> [4/6] 创建最小权限运行账号 pulseim 与运行目录"
if ! id -u pulseim >/dev/null 2>&1; then
  useradd --system --home-dir /opt/pulseim --shell /usr/sbin/nologin pulseim
fi

mkdir -p /opt/pulseim/releases /etc/pulseim /var/log/pulseim /var/lib/pulseim/uploads /var/backups/pulseim
chown -R pulseim:pulseim /opt/pulseim /var/log/pulseim /var/lib/pulseim /var/backups/pulseim
chmod 750 /etc/pulseim

echo "==> [5/6] 生成默认网关节点配置（单网关 9001；可按需启用 gateway-2.env）"
if [ ! -f /etc/pulseim/gateway-1.env ]; then
  cat >/etc/pulseim/gateway-1.env <<'EOF'
GATEWAY_NODE_ID=gateway-1
WS_PORT=9001
GATEWAY_HTTP_PORT=8081
EOF
fi

if [ ! -f /etc/pulseim/gateway-2.env ]; then
  cat >/etc/pulseim/gateway-2.env <<'EOF'
GATEWAY_NODE_ID=gateway-2
WS_PORT=9002
GATEWAY_HTTP_PORT=8089
EOF
fi
chown -R pulseim:pulseim /etc/pulseim
chmod 600 /etc/pulseim/*.env

echo "==> [6/6] 配置 UFW 防火墙（仅放行 SSH / 80 / 443，屏蔽中间件与内部端口）"
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

cat <<'EOF'

============================================================================
基础环境初始化完成！请接着完成以下一次性人工配置：
1. 配置 MySQL 账号与库：
   sudo mysql -e "CREATE DATABASE IF NOT EXISTS im_agent_chat DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER IF NOT EXISTS 'pulseim'@'127.0.0.1' IDENTIFIED BY '你的强密码';
   GRANT ALL PRIVILEGES ON im_agent_chat.* TO 'pulseim'@'127.0.0.1'; FLUSH PRIVILEGES;"
2. 配置 Redis 密码（/etc/redis/redis.conf 设置 requirepass 你的Redis强密码，并 bind 127.0.0.1），然后：
   sudo systemctl restart redis-server
3. 配置 RabbitMQ 账号：
   sudo rabbitmqctl add_user im_admin '你的MQ强密码'
   sudo rabbitmqctl set_user_tags im_admin administrator
   sudo rabbitmqctl set_permissions -p / im_admin '.*' '.*' '.*'
4. 复制并填写生产环境变量：
   sudo cp deploy/env/pulseim.env.example /etc/pulseim/pulseim.env
   sudo chmod 600 /etc/pulseim/pulseim.env && sudo chown pulseim:pulseim /etc/pulseim/pulseim.env
5. 安装 systemd 服务单元：
   sudo cp deploy/systemd/*.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable im-server im-gateway@1 im-agent
6. 配置 Nginx 与免费 HTTPS 证书：
   sudo cp deploy/nginx/pulseim.conf /etc/nginx/sites-available/pulseim.conf
   sudo ln -sf /etc/nginx/sites-available/pulseim.conf /etc/nginx/sites-enabled/pulseim.conf
   sudo nginx -t && sudo systemctl reload nginx
   sudo certbot --nginx -d 你的域名
============================================================================
EOF
