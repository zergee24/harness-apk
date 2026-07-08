# Release Hosting

第一版发布物只需要静态文件托管。当前策略是 GitHub 管源码，阿里云 OSS 管 APK 发布物。

测试通道：

```text
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/test/update.json
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/test/app-debug.apk
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/test/chunks/app-debug.apk.part-000
```

正式通道：

```text
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/prod/update.json
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/prod/app-release.apk
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/prod/chunks/app-release.apk.part-000
```

GitHub Actions:

- Workflow: `.github/workflows/deploy-apk-to-oss.yml`
- 手动参数：`channel=test|prod`
- 测试包构建：`./gradlew :app:assembleDebug --console=plain`
- 正式包构建：`./gradlew :app:assembleRelease --console=plain`
- 生成：`build/release-oss/update.json`、APK、`chunks/*`
- 上传：`scripts/upload_to_oss.py build/release-oss`

本地打包机：

```bash
scripts/release_apk.sh test
scripts/release_apk.sh prod
scripts/release_apk.sh test --upload
scripts/release_apk.sh prod --upload
```

GitHub 配置：

- Secrets: `ALIYUN_ACCESS_KEY_ID`, `ALIYUN_ACCESS_KEY_SECRET`
- Prod signing secrets: `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEY_PASSWORD`
- Variables: `OSS_BUCKET`, `OSS_ENDPOINT`, `OSS_TEST_PREFIX`, `OSS_PROD_PREFIX`, `OSS_ACL`
- 可选 Variables: `ENABLE_OSS_DEPLOY_ON_PUSH=true` 时，推送 `main` 或 `apk-test` 的应用源码变更会自动部署；未开启时只支持手动运行 workflow。

要求：

- GitHub 只存源码，不提交 `release/test/`、APK、分片、生产 `update.json`。
- manifest、APK 和分片必须使用 HTTPS。
- `update.json` 同时写入完整包 `apkUrl` 和分片 `apkChunks`，客户端优先使用分片。
- OSS 对象需要可公开读取，默认上传对象 ACL 为 `public-read`。
- Debug 包只读取测试通道 manifest；release 包只读取正式通道 manifest。
- `prod` 通道必须签名后上传；未配置正式签名时，脚本只允许本地生成 unsigned APK 做结构验证，不允许上传。
- 如后续绑定中国内地自定义域名，需要按云厂商要求完成 ICP 备案。
