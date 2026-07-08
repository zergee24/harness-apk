# Release Hosting

第一版发布物只需要静态文件托管。

推荐结构：

```text
https://download.example.com/harness-apk/update.json
https://download.example.com/harness-apk/releases/0.1.0/app-release.apk
```

可用服务：

- 阿里云 OSS + 自定义 HTTPS 域名。
- 腾讯云 COS + 自定义 HTTPS 域名。

要求：

- 不使用默认 bucket 域名分发 APK。
- manifest 和 APK 必须使用 HTTPS。
- APK 上传后运行 `scripts/compute-sha256.sh`，把结果写入生产环境的 `update.json`。
- 每个 release 目录保留历史 APK，`update.json` 只指向当前推荐版本。
- 中国内地 bucket 绑定自定义域名时，需要按云厂商要求完成 ICP 备案。

