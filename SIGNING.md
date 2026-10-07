# APK 签名与原地更新

现有主应用使用 Mac 上 `.build/debug.keystore` 的 `zfold` 别名。构建机器可以变化，签名密钥必须保持一致，才能覆盖安装并保留应用数据。当前证书 SHA-256：

```text
192ee603469e9f3a289c7cea7d223228565f51faf38dd82f27b421a477a08530
```

## GitHub Actions

仓库配置两项：Secret `ANDROID_DEBUG_KEYSTORE_BASE64` 保存 keystore 的 Base64；Variable `ANDROID_DEBUG_CERT_SHA256` 保存上述公开证书指纹。Secret 由 Mac 通过标准输入上传，没有进入源码或命令参数。

`push` 和 `workflow_dispatch` 构建先恢复 `.build/debug.keystore`，再构建 compat28 / modern APK，核对产物证书后才上传。缺少 Secret 或证书不一致时失败，避免产生无法更新现有主应用的安装包。Pull request 构建使用临时测试签名，产物名称带 `-pr-test`，不能用于覆盖现有主应用。

现有构建脚本使用 Android debug keystore 的密码 `android`，别名 `zfold`；更换密码或签名方案时需要同步修改构建脚本。私钥只放在 Secret 或本机忽略目录，不提交 keystore、不上传为 Actions artifact。

## 已配置的 Fold7 本机编译

私钥位于主应用的私有目录，目录权限 700、文件权限 600：

```text
/data/user/0/dev.zfold.assistant/no_backup/credentials/signing/debug.keystore
```

手机的 `/sdcard/pi/workspace/ondevice-build.sh` 已接入此路径。主包编译：

```sh
sh /sdcard/pi/workspace/ondevice-build.sh 28
```

`--dev` 继续使用手机原来的 `zfolddev` 签名，保留分身的独立更新路径。也可以通过 `ZFOLD_KEYSTORE` 指定主应用 keystore 路径。手机私有源码目录 `files/runtime/dev/src/pi-android/.build/debug.keystore` 链接到同一私钥，供通用构建脚本使用。

已核对 Mac、手机原安装 APK 和手机签名探针的证书一致，私钥副本逐字节一致。未卸载或更新手机上的应用，也未修改模型或语音凭据。
