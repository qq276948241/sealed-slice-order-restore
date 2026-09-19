#!/usr/bin/env bash
# 只用 JDK 自带库：编译 + 两个互相独立的进程阶段。
set -euo pipefail

cd "$(dirname "$0")"

# 如果系统默认没有 java（例如只装了 Homebrew 的 openjdk），自动补上 PATH。
for candidate in \
  /opt/homebrew/opt/openjdk@21/bin \
  /opt/homebrew/opt/openjdk/bin \
  /usr/local/opt/openjdk@21/bin \
  /usr/local/opt/openjdk/bin; do
  if [ -x "$candidate/java" ]; then
    export PATH="$candidate:$PATH"
    break
  fi
done

rm -rf out logdata
mkdir -p out

echo "== 编译 =="
javac -d out $(find src -name '*.java')

echo
echo "== 阶段一：写入 / 自动封口 / 注入一条坏记录（进程随后退出） =="
java -cp out segmentlog.Demo write

echo
echo "== 阶段二：重新打开目录，按封口时间找回 =="
java -cp out segmentlog.Demo read
