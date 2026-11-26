#!/usr/bin/env bash
# ============================================================================
# PulseIM MySQL 每日自动压缩备份脚本（保留最近 14 天）
# 定时任务示例（crontab -e）：0 3 * * * /opt/pulseim/backup-mysql.sh >> /var/log/pulseim/backup.log 2>&1
# ============================================================================
set -euo pipefail

ENV_FILE="/etc/pulseim/pulseim.env"
BACKUP_DIR="/var/backups/pulseim"
KEEP_DAYS=14

if [ -f "${ENV_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a; source "${ENV_FILE}"; set +a
fi

mkdir -p "${BACKUP_DIR}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="${BACKUP_DIR}/${MYSQL_DATABASE:-im_agent_chat}-${TIMESTAMP}.sql.gz"

MYSQL_PWD="${MYSQL_PASSWORD:?MYSQL_PASSWORD 未设置}" mysqldump \
  -h "${MYSQL_HOST:-127.0.0.1}" \
  -P "${MYSQL_PORT:-3306}" \
  -u "${MYSQL_USER:-pulseim}" \
  --single-transaction \
  --quick \
  --routines \
  --triggers \
  "${MYSQL_DATABASE:-im_agent_chat}" | gzip -9 > "${OUT_FILE}"

chmod 600 "${OUT_FILE}"
find "${BACKUP_DIR}" -type f -name '*.sql.gz' -mtime +"${KEEP_DAYS}" -delete

echo "[$(date '+%F %T')] MySQL 备份完成: ${OUT_FILE} ($(du -h "${OUT_FILE}" | cut -f1))"
