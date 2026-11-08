# 更新与报告服务器（mylinux）

这两组服务跑在同一台服务器（SSH 别名 `mylinux`）上，客户端只访问 `https://www.rengouzi.top:8443`。

## growquiz-sync：更新同步

- `growquiz-sync.py`：把 GitHub 仓库最新 Release 的 APK 与发版说明拉到 `/root/nginx/web/growquiz/`（`latest.json` + APK），Caddy `:8443/growquiz/*` 反代到 nginx `:8088` 静态站点。
- `growquiz-sync.timer`：每 3 小时触发一次；手动：`systemctl start growquiz-sync`。
- 客户端每天检查一次 `…/growquiz/latest.json`，发现新版本自动下载安装。

## growquiz-report：兼容性报告接收（v1.14.0 起）

- `growquiz-report.py`：HTTP 服务监听 `127.0.0.1:8092`，Caddy `:8443/growquiz-report/*` 反代进来。校验 `X-Report-Token` 口令（与 APP 端 `ReportUpload.TOKEN` 一致），单份上限 1 MiB。
- 落盘：`/root/growquiz-reports/<设备ID>/<时间>.json`，另维护 `latest/<设备ID>.json` 便于速查。
- `growquiz-report.service`：常驻，`systemctl enable --now growquiz-report`。
- 报告内容：设备探针快照（品牌/固件/权限/TTS）、诊断事件（JSONL 字符串）、上次崩溃栈。设备以随机 UUID 标识。

## 部署

```sh
scp server/growquiz-*.* mylinux:/root/growquiz/
ssh mylinux 'cp /root/growquiz/growquiz-*.service /root/growquiz/growquiz-*.timer /etc/systemd/system/ \
  && systemctl daemon-reload && systemctl enable --now growquiz-sync.timer growquiz-report'
```
