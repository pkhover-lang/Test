#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc is required'; exit 1; }
command -v java >/dev/null || { echo 'Java 17+ is required'; exit 1; }
mkdir -p build/core-tests
kotlinc app/src/main/java/local/phoneauditor/core/*.kt \
  app/src/test/java/local/phoneauditor/CoreChecks.kt tools/CoreTestMain.kt \
  -jvm-target 17 -include-runtime -d build/core-tests/tests.jar
java -jar build/core-tests/tests.jar build/core-tests/json-roundtrip.json
python3 - <<'PY'
import json
with open('build/core-tests/json-roundtrip.json', encoding='utf-8') as f:
    obj = json.load(f)
assert obj['label'] == 'Проверка "кавычек" \\ путь\n😀'
assert obj['unknown'] is None and obj['not_a_number'] is None
assert obj['counts'][-1] == 9223372036854775807
print('PASS independent Python JSON roundtrip')
PY
