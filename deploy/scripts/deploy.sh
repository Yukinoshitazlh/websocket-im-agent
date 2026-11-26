#!/usr/bin/env bash
# ============================================================================
# PulseIM v2.0 生产原子发布脚本（版本目录软链接 + 滚动重启 + 健康检查 + 失败自动回滚）
# 用法：sudo bash /opt/pulseim/deploy.sh <release_version> [gateway_nodes]
# 示例：sudo bash /opt/pulseim/deploy.sh 20261126-9bed846 "1"
#       sudo bash /opt/pulseim/deploy.sh v2.0.0 "1 2"   # 双网关依次滚动重启
# ============================================================================
set -euo pipefail

VERSION="${1:?请指定发布版本号，例如 v2.0.0}"
GATEWAY_INSTANCES="${2:-1}"

APP_ROOT="/opt/pulseim"
RELEASE_DIR="${APP_ROOT}/releases/${VERSION}"
CURRENT_LINK="${APP_ROOT}/current"
KEEP_RELEASES=5

if [ ! -d "${RELEASE_DIR}" ]; then
  echo "发布目录不存在: ${RELEASE_DIR}" >&2
  exit 1
fi

for jar in im-server.jar im-gateway.jar im-agent.jar; do
  if [ ! -f "${RELEASE_DIR}/${jar}" ]; then
    echo "缺少发布制品: ${RELEASE_DIR}/${jar}" >&2
    exit 1
  fi
done

if [ -f "${RELEASE_DIR}/SHA256SUMS.txt" ]; then
  echo "==> 校验构建制品 SHA-256"
  (cd "${RELEASE_DIR}" && sha256sum -c SHA256SUMS.txt)
fi

PREVIOUS_TARGET=""
if [ -L "${CURRENT_LINK}" ]; then
  PREVIOUS_TARGET="$(readlink -f "${CURRENT_LINK}")"
fi

wait_health() {
  local service_name="$1"
  local url="$2"
  local max_attempts="${3:-30}"
  echo "==> 等待 ${service_name} 健康检查就绪 (${url})..."
  for ((i=1; i<=max_attempts; i++)); do
    if curl -fsS --max-time 2 "${url}" | grep -q '"status":"UP"'; then
      echo "✓ ${service_name} 健康检查通过 (${i}/${max_attempts})"
      return 0
    fi
    sleep 2
  done
  echo "✗ ${service_name} 健康检查超时失败！" >&2
  return 1
}

restart_stack() {
  echo "==> [1/3] 重启核心服务 im-server (Flyway 自动检查迁移)"
  systemctl restart im-server
  wait_health "im-server" "http://127.0.0.1:8080/actuator/health" 45

  echo "==> [2/3] 依次滚动重启 Netty 网关节点: ${GATEWAY_INSTANCES}"
  for idx in ${GATEWAY_INSTANCES}; do
    local gw_port="8081"
    if [ "${idx}" = "2" ]; then
      gw_port="8089"
    fi
    if [ -f "/etc/pulseim/gateway-${idx}.env" ]; then
      local cfg_port
      cfg_port=$(grep -E '^GATEWAY_HTTP_PORT=' "/etc/pulseim/gateway-${idx}.env" | cut -d'=' -f2 || true)
      if [ -n "${cfg_port}" ]; then
        gw_port="${cfg_port}"
      fi
    fi
    systemctl restart "im-gateway@${idx}"
    wait_health "im-gateway@${idx}" "http://127.0.0.1:${gw_port}/actuator/health" 30
  done

  echo "==> [3/3] 重启 AI Agent Worker im-agent"
  systemctl restart im-agent
  wait_health "im-agent" "http://127.0.0.1:8082/actuator/health" 30
}

rollback() {
  echo "!!! 发布异常，开始自动回滚至上一版本 !!!" >&2
  if [ -n "${PREVIOUS_TARGET}" ] && [ -d "${PREVIOUS_TARGET}" ]; then
    ln -sfn "${PREVIOUS_TARGET}" "${CURRENT_LINK}"
    chown -h pulseim:pulseim "${CURRENT_LINK}"
    restart_stack || true
    echo "已完成自动回滚至: ${PREVIOUS_TARGET}" >&2
  else
    echo "无历史版本可回滚，请人工排查 journalctl -u im-server -n 100" >&2
  fi
}

echo "==> 切换软链接 ${CURRENT_LINK} -> ${RELEASE_DIR}"
chown -R pulseim:pulseim "${RELEASE_DIR}"
ln -sfn "${RELEASE_DIR}" "${CURRENT_LINK}"
chown -h pulseim:pulseim "${CURRENT_LINK}"

if ! restart_stack; then
  rollback
  exit 1
fi

echo "==> 清理旧版本目录（保留最近 ${KEEP_RELEASES} 个版本）"
ls -1dt "${APP_ROOT}/releases/"* 2>/dev/null | tail -n +$((KEEP_RELEASES + 1)) | xargs -r rm -rf

echo "✓ PulseIM ${VERSION} 发布成功！"
