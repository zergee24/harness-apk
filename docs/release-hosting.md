# Release Hosting

第一版发布物只需要静态文件托管。当前策略是 GitHub 管源码，阿里云 OSS 管 APK 发布物。

当前测试通道结构：

```text
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/test/update.json
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/test/app-debug.apk
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/test/chunks/app-debug.apk.part-000
```

GitHub Actions:

- Workflow: `.github/workflows/deploy-apk-to-oss.yml`
- 构建：`./gradlew :app:assembleDebug --console=plain`
- 生成：`build/release-oss/update.json`、`app-debug.apk`、`chunks/*`
- 上传：`scripts/upload_to_oss.py build/release-oss`

GitHub 配置：

- Secrets: `ALIYUN_ACCESS_KEY_ID`, `ALIYUN_ACCESS_KEY_SECRET`
- Variables: `OSS_BUCKET`, `OSS_ENDPOINT`, `OSS_PREFIX`, `OSS_ACL`
- 可选 Variables: `ENABLE_OSS_DEPLOY_ON_PUSH=true` 时，推送 `main` 或 `apk-test` 的应用源码变更会自动部署；未开启时只支持手动运行 workflow。

要求：

- GitHub 只存源码，不提交 `release/test/`、APK、分片、生产 `update.json`。
- manifest、APK 和分片必须使用 HTTPS。
- `update.json` 同时写入完整包 `apkUrl` 和分片 `apkChunks`，客户端优先使用分片。
- OSS 对象需要可公开读取，默认上传对象 ACL 为 `public-read`。
- 如后续绑定中国内地自定义域名，需要按云厂商要求完成 ICP 备案。
