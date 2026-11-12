#!/usr/bin/env bash
# ============================================================================
# 复式账本 HTTP 联调脚本（第 2 周 Day 5-6）
#
# 覆盖：充值落账 / 下单冻结 / 成交清算 / 提币失败解冻 / 冲正 / 幂等重复提交
#
# 前置条件：
#   1. 已执行 sql/schema.sql 与 sql/ledger_test_data.sql（固定测试账户）
#   2. 应用已启动，默认 http://localhost:8080
#   3. security.jwt.enabled=true（默认）时脚本会自动签发 ledger:write token；
#      本地关闭鉴权时用 AUTH_ENABLED=false 运行
#
# 用法：
#   bash scripts/ledger-api-demo.sh
#   BASE_URL=http://localhost:8080 ISSUER_KEY=local RUN_ID=demo1 bash scripts/ledger-api-demo.sh
#   AUTH_ENABLED=false bash scripts/ledger-api-demo.sh
#
# 每次运行用 RUN_ID 生成新的业务单号，避免命中上一次的幂等键。
# ============================================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ISSUER_KEY="${ISSUER_KEY:-local}"
AUTH_ENABLED="${AUTH_ENABLED:-true}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"

# 与 sql/ledger_test_data.sql 保持一致
USER_A=900001              # 用户A / Maker：初始 1000 USDT
USER_B=900002              # 用户B / Taker：初始 1000 USDT
USER_C=900003              # 用户C / 手续费付款方：初始 500 USDT
PLATFORM_LIABILITY=900101  # 平台代收负债
PLATFORM_FEE=900102        # 平台手续费收入
USDT=USDT

TOKEN=""

# 签发 JWT（scope=ledger:write）。关闭鉴权时跳过。
if [ "$AUTH_ENABLED" = "true" ]; then
  token_resp=$(curl -sS -X POST \
    -H "X-Issuer-Key: $ISSUER_KEY" \
    "$BASE_URL/api/auth/token?sub=ledger-demo&scope=ledger:write")
  TOKEN=$(printf '%s' "$token_resp" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if [ -z "$TOKEN" ]; then
    echo "获取 token 失败，响应：$token_resp" >&2
    echo "请确认应用已启动且 security.jwt.issuer-access-key 与 ISSUER_KEY 一致。" >&2
    exit 1
  fi
  echo "token 获取成功（sub=ledger-demo, scope=ledger:write）"
else
  echo "AUTH_ENABLED=false，跳过 token 签发，直接匿名调用"
fi

# post <标题> <路径> <JSON 请求体>
post() {
  local title="$1"
  local path="$2"
  local body="$3"
  printf '\n===== %s =====\n' "$title"
  printf 'POST %s\n%s\n' "$BASE_URL$path" "$body"
  if [ "$AUTH_ENABLED" = "true" ]; then
    curl -sS -w '\nHTTP %{http_code}\n' -X POST "$BASE_URL$path" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$body"
  else
    curl -sS -w '\nHTTP %{http_code}\n' -X POST "$BASE_URL$path" \
      -H 'Content-Type: application/json' \
      -d "$body"
  fi
}

echo "RUN_ID=$RUN_ID"
echo "提示：本脚本假设测试账户余额处于 sql/ledger_test_data.sql 的初始状态；重复整轮执行前请先重跑该 SQL。"

# ---------------------------------------------------------------------------
# ① 充值落账：注入测试资金（平台代收负债账户允许为负）
# ---------------------------------------------------------------------------
post "① 充值：用户A +100 USDT / 平台代收负债 -100" /api/ledger/deposit "{
  \"depositNo\": \"DEP-$RUN_ID-A\",
  \"userAccountId\": $USER_A,
  \"platformLiabilityAccountId\": $PLATFORM_LIABILITY,
  \"amount\": \"100\",
  \"currency\": \"$USDT\"
}"

post "① 充值：用户B +200 USDT" /api/ledger/deposit "{
  \"depositNo\": \"DEP-$RUN_ID-B\",
  \"userAccountId\": $USER_B,
  \"platformLiabilityAccountId\": $PLATFORM_LIABILITY,
  \"amount\": \"200\",
  \"currency\": \"$USDT\"
}"

post "① 充值：用户C +50 USDT（手续费付款方）" /api/ledger/deposit "{
  \"depositNo\": \"DEP-$RUN_ID-C\",
  \"userAccountId\": $USER_C,
  \"platformLiabilityAccountId\": $PLATFORM_LIABILITY,
  \"amount\": \"50\",
  \"currency\": \"$USDT\"
}"

# ---------------------------------------------------------------------------
# ② 下单冻结：用户B available -150 / frozen +150
# ---------------------------------------------------------------------------
post "② 冻结：用户B 冻结 150 USDT" /api/ledger/freeze "{
  \"orderNo\": \"ORD-$RUN_ID\",
  \"userAccountId\": $USER_B,
  \"amount\": \"150\",
  \"currency\": \"$USDT\"
}"

# ---------------------------------------------------------------------------
# ③ 成交清算：本金 + 手续费两组分录同事务
#    Taker(B) frozen -100 / Maker(A) available +100
#    C available -1 / 平台手续费 +1
# ---------------------------------------------------------------------------
post "③ 清算：本金 100 USDT + 手续费 1 USDT" /api/ledger/settle "{
  \"tradeNo\": \"TRADE-$RUN_ID\",
  \"takerAccountId\": $USER_B,
  \"makerAccountId\": $USER_A,
  \"feePayerAccountId\": $USER_C,
  \"platformFeeAccountId\": $PLATFORM_FEE,
  \"amount\": \"100\",
  \"fee\": \"1\",
  \"currency\": \"$USDT\"
}"

# ---------------------------------------------------------------------------
# ④ 提币失败解冻：用户B frozen -30 / available +30
# ---------------------------------------------------------------------------
post "④ 解冻：用户B 解冻 30 USDT" /api/ledger/unfreeze "{
  \"withdrawNo\": \"WD-$RUN_ID\",
  \"userAccountId\": $USER_B,
  \"amount\": \"30\",
  \"currency\": \"$USDT\"
}"

# ---------------------------------------------------------------------------
# 冲正：撤销用户A 的充值（写反向分录，原分录不改）
# ---------------------------------------------------------------------------
post "冲正：撤销用户A 的充值 DEP-$RUN_ID-A" /api/ledger/reverse "{
  \"bizType\": \"DEPOSIT\",
  \"bizNo\": \"DEP-$RUN_ID-A\"
}"

# ---------------------------------------------------------------------------
# 幂等演示：重复提交同一笔充值，应返回相同 entryNo，余额不再变化
# ---------------------------------------------------------------------------
post "幂等：重复提交用户A 的充值（预期返回同一 entryNo）" /api/ledger/deposit "{
  \"depositNo\": \"DEP-$RUN_ID-A\",
  \"userAccountId\": $USER_A,
  \"platformLiabilityAccountId\": $PLATFORM_LIABILITY,
  \"amount\": \"100\",
  \"currency\": \"$USDT\"
}"

# ---------------------------------------------------------------------------
# 结果核对
# ---------------------------------------------------------------------------
cat <<EOF

===== 执行完毕 =====
RUN_ID=$RUN_ID

期望余额（以 sql/ledger_test_data.sql 初始状态为前提）：
  900001 用户A        available=1100  frozen=0
  900002 用户B        available=1080  frozen=20
  900003 用户C        available=549   frozen=0
  900101 平台代收负债  available=-250  frozen=0
  900102 平台手续费    available=1     frozen=0

核对 SQL（mysql -u root -p web3_backend）：
  SELECT id, user_id, account_type, currency, available, frozen, version
  FROM \`account\`
  WHERE id IN ($USER_A, $USER_B, $USER_C, $PLATFORM_LIABILITY, $PLATFORM_FEE)
  ORDER BY id;

  SELECT e.id, e.entry_no, e.biz_type, e.biz_no, e.reverse_of,
         p.account_id, p.direction, p.balance_type, p.amount, p.balance_after
  FROM ledger_entry e
  JOIN ledger_posting p ON p.entry_id = e.id
  WHERE e.biz_no LIKE '%$RUN_ID%' OR e.biz_no LIKE 'REV:%'
  ORDER BY e.id, p.id;
EOF