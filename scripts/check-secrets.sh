#!/usr/bin/env bash
# 网关密钥扫描告警：只报告疑似密钥位置，不自动改文件。
# 是否替换为占位符、迁移到 Nacos 或保留当前配置，由项目负责人判断。
set -euo pipefail

# 切到仓库根目录，保证 git ls-files 与相对路径匹配一致
cd "$(git rev-parse --show-toplevel)"

# 仅排除二进制 wrapper、脚本自身等不可能承载业务密钥、又容易误报的文件
excluded='^(scripts/check-secrets.sh|gradle/wrapper/gradle-wrapper.jar|gradlew|gradlew.bat)$'

# 常见密钥特征：Google AIza、OpenAI sk-、JWT、access_token、PEM 私钥、AWS AKIA、超长疑似密钥串
patterns=(
  'AIza[[:alnum:]_-]{20,}'
  'sk-[[:alnum:]_-]{20,}'
  'MT[[:alnum:]_.-]{30,}'
  'access_token=[[:alnum:]_-]{20,}'
  'SEC[[:alnum:]]{20,}'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'AKIA[[:alnum:]]{16}'
  "'[[:alnum:]]{48,}'"
)

failed=0

# 遍历 git 已跟踪与未忽略的新增文件，逐个匹配密钥特征
while IFS= read -r file; do
  # 命中排除名单的文件直接跳过，避免对 wrapper/脚本自身误报
  if [[ "$file" =~ $excluded ]]; then
    continue
  fi
  # 跳过不存在或二进制文件，避免无意义扫描与误报
  if [[ ! -f "$file" ]] || ! grep -Iq . "$file"; then
    continue
  fi
  # 任一密钥特征命中即记录行号并标记发现，便于定位
  for pattern in "${patterns[@]}"; do
    matched_lines="$(grep -nE "$pattern" "$file" 2>/dev/null | cut -d: -f1 || true)"
    if [[ -n "$matched_lines" ]]; then
      echo "Potential secret detected in $file:"
      while IFS= read -r line; do
        echo "  line $line matches a suspicious secret pattern"
      done <<< "$matched_lines"
      failed=1
    fi
  done
done < <(git ls-files --cached --others --exclude-standard)

# 默认只告警；人工设置 STRICT_SECRET_SCAN=1 时才返回非零状态
if [[ "$failed" -ne 0 ]]; then
  echo "Secret scan warning. Do not change credentials automatically; report the file and let the project owner decide whether to move or replace them."
  if [[ "${STRICT_SECRET_SCAN:-0}" == "1" ]]; then
    echo "STRICT_SECRET_SCAN=1 enabled, failing because potential secrets were detected."
    exit 1
  fi
  exit 0
fi

echo "Secret scan passed."
