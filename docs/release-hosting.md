# Release Hosting

第一版发布物只需要静态文件托管。当前策略是 GitHub 管源码，阿里云 OSS 管 APK 发布物。

测试通道：

```text
https://www.zerg.work/harness-apk/test/update.json
https://www.zerg.work/harness-apk/test/app-debug.apk
https://www.zerg.work/harness-apk/test/chunks/app-debug.apk.part-000
```

正式通道：

```text
https://www.zerg.work/harness-apk/prod/update.json
https://www.zerg.work/harness-apk/prod/app-release.apk
https://www.zerg.work/harness-apk/prod/chunks/app-release.apk.part-000
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
scripts/release_apk.sh test --version-code 1015009 --upload
```

版本策略：

- `versionName` 是给人看的产品版本，例如 `0.1.15`；测试通道生成的更新清单和 APK 会显示为 `0.1.15-debug`。
- `versionCode` 是给系统和更新器看的机器版本，只有远端 `versionCode` 大于已安装包时才提示更新。
- `app/build.gradle.kts` 中维护产品版本基础值，例如 `versionName=0.1.15`、`versionCode=1015000`。
- GitHub Actions 的 `test` 通道默认用 `基础 versionCode + GitHub run number` 生成递增测试构建号；因此同一个产品版本可以多次打测试包。
- 手动运行 workflow 或本地脚本时，可以显式传入 `version_code` / `--version-code` 覆盖自动值。

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
