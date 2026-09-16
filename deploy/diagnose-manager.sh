#!/usr/bin/env bash
# HFG Manager 登录故障诊断脚本
# 在服务器上以 root 执行: sudo bash diagnose-manager.sh
set -uo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[OK]${NC}   $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
hdr()  { echo -e "\n${YELLOW}====== $* ======${NC}"; }

JAR=/opt/hfg/hfg-manager.jar
ENV_FILE=/etc/hfg/hfg-manager.env
JAVA_BIN=/opt/jdk17/bin/java

hdr "1. 进程与端口"
if pgrep -f 'hfg-manager.jar' >/dev/null 2>&1; then
  ok "Manager 进程在运行 (PID: $(pgrep -f 'hfg-manager.jar' | head -1))"
else
  fail "Manager 进程未运行!"
  echo "  可能原因: gRPC 证书缺失导致启动崩溃, 或数据库连接失败"
  echo "  查看启动日志: journalctl -u hfg-manager -n 200 --no-pager (如果用 systemd)"
fi

echo -n "  端口 8080: "
if ss -ltn 2>/dev/null | grep -q ':8080 '; then
  ok "监听中"
else
  fail "未监听!"
fi

echo -n "  端口 19090 (gRPC): "
if ss -ltn 2>/dev/null | grep -q ':19090 '; then
  ok "监听中"
else
  warn "未监听 (gRPC 可能未启用或启动失败)"
fi

hdr "2. Java 版本"
if [ -x "$JAVA_BIN" ]; then
  echo "  $JAVA_BIN:"
  $JAVA_BIN -version 2>&1 | head -2 | sed 's/^/    /'
else
  fail "$JAVA_BIN 不存在或不可执行"
fi

hdr "3. 环境变量文件"
if [ -f "$ENV_FILE" ]; then
  ok "环境文件存在: $ENV_FILE"
  echo "  关键变量 (密码已遮掩):"
  while IFS='=' read -r key val; do
    case "$key" in
      ''|\#*) continue ;;
    esac
    case "$key" in
      *PASSWORD*|*KEY*|*SECRET*)
        if [ -z "$val" ]; then
          echo "    $key = (空!)"
        elif [ "$val" = "CHANGE_ME" ] || [ "$val" = "REPLACE_WITH_STRONG_PASSWORD" ] || [ "$val" = "REPLACE_WITH_ADMIN_PASSWORD" ]; then
          echo "    $key = $val  <-- 还是模板默认值, 未修改!"
        else
          echo "    $key = ${val:0:3}***${val: -2} (已设置)"
        fi ;;
      *)
        echo "    $key = $val" ;;
    esac
  done < "$ENV_FILE"
else
  fail "环境文件不存在: $ENV_FILE"
fi

hdr "4. 前端是否打包在 JAR 内"
if [ -f "$JAR" ]; then
  if unzip -l "$JAR" 2>/dev/null | grep -q 'BOOT-INF/classes/static/index.html'; then
    ok "前端已打包在 JAR 中"
  else
    fail "前端未打包在 JAR 中!"
    echo "  这会导致 http://172.16.64.192:8080/ 返回 404, 看不到登录页面"
    echo "  修复: 使用发布介质中的 JAR, 或用 -Dhfg.bundle-web=true 重新构建:"
    echo "    cd hfg-manager-web && npm ci && npm run build && cd .."
    echo "    ./mvnw -Dhfg.bundle-web=true -DskipTests clean package"
  fi
else
  fail "JAR 文件不存在: $JAR"
fi

hdr "5. Manager 健康检查"
if curl -sf --noproxy '*' -m 5 http://127.0.0.1:8080/actuator/health 2>/dev/null | head -c 200; then
  echo ""
  ok "健康检查通过"
else
  fail "健康检查失败 (Manager 可能未正常启动)"
fi

hdr "6. 前端页面可达性"
echo -n "  GET / : "
CODE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1:8080/ 2>/dev/null)
echo "HTTP $CODE"
if [ "$CODE" = "200" ]; then ok "首页返回 200"; else fail "首页未返回 200 (可能是前端未打包)"; fi

echo -n "  GET /index.html : "
CODE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1:8080/index.html 2>/dev/null)
echo "HTTP $CODE"
if [ "$CODE" = "200" ]; then ok "index.html 可访问"; else fail "index.html 不可访问"; fi

hdr "7. 登录 API 测试"
# 从环境文件读取管理员凭据
ADMIN_USER=$(grep -E '^HFG_ADMIN_USERNAME=' "$ENV_FILE" 2>/dev/null | cut -d= -f2)
ADMIN_PASS=$(grep -E '^HFG_ADMIN_PASSWORD=' "$ENV_FILE" 2>/dev/null | cut -d= -f2)

if [ -z "$ADMIN_PASS" ]; then
  fail "HFG_ADMIN_PASSWORD 未在环境文件中设置!"
  echo "  Manager 启动时会因 @Value 占位符无法解析而崩溃"
elif [ "$ADMIN_PASS" = "CHANGE_ME" ] || [ "$ADMIN_PASS" = "REPLACE_WITH_ADMIN_PASSWORD" ]; then
  warn "HFG_ADMIN_PASSWORD 仍为模板默认值: $ADMIN_PASS"
  echo "  如果你用的不是这个密码登录, 当然会 401"
fi

echo "  使用环境文件中的凭据 ($ADMIN_USER/$(echo "$ADMIN_PASS" | sed 's/./*/g')) 测试登录 API:"
echo -n "  GET /api/v1/users?size=1 : "
CODE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -m 5 -u "$ADMIN_USER:$ADMIN_PASS" http://127.0.0.1:8080/api/v1/users?size=1 2>/dev/null)
echo "HTTP $CODE"
if [ "$CODE" = "200" ]; then
  ok "登录 API 正常! 后端认证工作正常"
  echo "  请确认浏览器中输入的密码与 HFG_ADMIN_PASSWORD 完全一致"
elif [ "$CODE" = "401" ]; then
  fail "认证失败 (401) — 后端拒绝了凭据"
  echo "  可能是 BCrypt 编码问题, 或凭据不匹配"
elif [ "$CODE" = "500" ]; then
  fail "服务器内部错误 (500)"
  echo "  可能是数据库查询失败、JPA 扫描问题或 Hibernate 映射错误"
  echo "  查看日志: journalctl -u hfg-manager -n 200 --no-pager"
  echo "  或前台启动观察: sudo -u hfg bash -c 'set -a; source $ENV_FILE; set +a; exec $JAVA_BIN -jar $JAR'"
elif [ "$CODE" = "000" ]; then
  fail "无法连接到 8080 端口"
else
  warn "意外的 HTTP 状态码: $CODE"
fi

hdr "8. 数据库连接检查"
echo "  PostgreSQL 进程:"
if pgrep -x postgres >/dev/null 2>&1; then
  ok "PostgreSQL 在运行"
else
  fail "PostgreSQL 未运行!"
  echo "  启动: sudo systemctl start postgresql"
  echo "  或检查是否使用远程数据库"
fi

DB_URL=$(grep -E '^HFG_DB_URL=' "$ENV_FILE" 2>/dev/null | cut -d= -f2)
echo "  数据库 URL: $DB_URL"
if echo "$DB_URL" | grep -q 'postgresql'; then
  DB_HOST=$(echo "$DB_URL" | sed -n 's|.*//\([^:]*\).*|\1|p')
  DB_PORT=$(echo "$DB_URL" | sed -n 's|.*:\([0-9]*\)/.*|\1|p')
  DB_PORT=${DB_PORT:-5432}
  DB_NAME=$(echo "$DB_URL" | sed -n 's|.*/\([^?]*\).*|\1|p')
  echo "  检查 $DB_HOST:$DB_PORT/$DB_NAME 连通性:"
  if ss -tn 2>/dev/null | grep -q ":$DB_PORT "; then
    ok "数据库端口 $DB_PORT 有连接"
  else
    warn "未检测到数据库端口 $DB_PORT 的连接"
  fi
fi

hdr "9. gRPC 证书检查"
RPC_ENABLED=$(grep -E '^HFG_RPC_ENABLED=' "$ENV_FILE" 2>/dev/null | cut -d= -f2)
RPC_ENABLED=${RPC_ENABLED:-true}
echo "  HFG_RPC_ENABLED=$RPC_ENABLED"
if [ "$RPC_ENABLED" = "true" ]; then
  echo "  检查证书文件:"
  for f in manager.crt manager.key ca.crt ca.key; do
    path=$(grep -E "^HFG_RPC_$(echo "$f" | tr 'a-z' 'A-Z' | sed 's/\./_/g')=" "$ENV_FILE" 2>/dev/null | cut -d= -f2)
    path=${path:-/etc/hfg/pki/$f}
    if [ -f "$path" ]; then
      ok "  $path 存在"
    else
      fail "  $path 不存在! gRPC 启动会崩溃, 导致整个 Manager 启动失败"
    fi
  done
  echo ""
  echo "  如果证书未配置, 首次验证请设置:"
  echo "    echo 'HFG_RPC_ENABLED=false' >> $ENV_FILE"
  echo "  然后重启 Manager"
fi

hdr "10. 启动日志检查"
if [ "$(pgrep -f 'hfg-manager.jar' >/dev/null 2>&1; echo $?)" = "0" ]; then
  echo "  最后 30 行日志:"
  journalctl -u hfg-manager -n 30 --no-pager 2>/dev/null | sed 's/^/    /' || echo "  (非 systemd 启动, 无 journalctl 日志)"
fi

echo ""
hdr "诊断完成"
echo "  如果以上检查未定位问题, 请前台启动观察完整输出:"
echo "    sudo -u hfg bash -c 'set -a; source $ENV_FILE; set +a; exec $JAVA_BIN -jar $JAR'"
echo "  然后将启动日志中的 ERROR/WARN 行贴给我分析"
