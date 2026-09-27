import json
import sys

# 截图留档瘦身：去掉审计噪声字段与 null 值，让截图聚焦业务内容。
# 完整返回可通过重新运行 run-acceptance.sh 复现。

DROP_KEYS = {"createBy", "updateBy", "updateTime"}


def slim(value):
    if isinstance(value, dict):
        return {k: slim(v) for k, v in value.items()
                if k not in DROP_KEYS and v is not None}
    if isinstance(value, list):
        return [slim(item) for item in value]
    return value


data = json.load(sys.stdin)
print(json.dumps(slim(data), ensure_ascii=False, indent=2))
