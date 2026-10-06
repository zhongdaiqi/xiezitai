# tools —— 运维 / 联调小工具

| 脚本 | 用途 |
| --- | --- |
| `ci-status.py` | 查 GitHub Actions 最近几次 run 与 Docker Hub 镜像标签（无 gh CLI 时用公开 API），结果写入 `tools/ci-status.txt` |
| `ai-smoke.py` | AI 能力实调冒烟：登录 → 读 AI 配置 → 调摘要 → 调封面图（ModelScope 异步），用 `XZ_BASE` 覆盖地址 |

```bash
python tools/ci-status.py
XZ_BASE=http://localhost:8099 python tools/ai-smoke.py
```

产物 `tools/ci-status.txt` 已被 gitignore。
