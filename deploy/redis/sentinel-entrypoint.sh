#!/bin/sh
set -eu

# Sentinel 配置无法直接读取 Compose 的环境变量，因此在容器启动时生成临时配置。
# 配置文件只存在于容器临时目录，不把 Redis 口令提交到仓库或镜像层。
: "${REDIS_AUTH:?REDIS_AUTH must be set}"

if printf '%s' "$REDIS_AUTH" | grep -q '[[:cntrl:]]'; then
  echo "REDIS_AUTH must not contain control characters" >&2
  exit 64
fi

monitor_host="${REDIS_SENTINEL_MONITOR_HOST:-redis-master}"
quorum="${REDIS_SENTINEL_QUORUM:-2}"
sentinel_port="${REDIS_SENTINEL_PORT:-26379}"

# Redis 配置字符串使用双引号；转义反斜杠和双引号，避免口令改变配置语义。
escaped_auth=$(printf '%s' "$REDIS_AUTH" | sed 's/[\\"]/\\&/g')
config_file=/tmp/sentinel.conf
umask 077

cat > "$config_file" <<EOF
port $sentinel_port
sentinel monitor mymaster $monitor_host 6379 $quorum
sentinel auth-pass mymaster "$escaped_auth"
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 60000
sentinel parallel-syncs mymaster 1
EOF

exec redis-server "$config_file" --sentinel
