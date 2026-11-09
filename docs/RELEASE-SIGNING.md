# TVQuizLock 固定签名配置

Android 更新必须使用同一份签名密钥。密钥丢失后，旧安装包无法通过普通覆盖安装升级。

## GitHub Actions Secrets

在 GitHub 仓库打开：

**Settings → Secrets and variables → Actions → New repository secret**

添加以下四项：

| Secret 名称 | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `tvquizlock-release.keystore` 文件的 Base64 全文 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

不要把 keystore、密码或 Base64 内容提交到 Git 仓库。不要把同一份私钥放进公开 Release 资产。

## 本地构建

也可以在仓库根目录创建被 `.gitignore` 忽略的 `keystore.properties`：

```properties
storeFile=/absolute/path/to/tvquizlock-release.keystore
storePassword=你的keystore密码
keyAlias=tvquizlock
keyPassword=你的私钥密码
```

配置后执行：

```bash
./gradlew assembleRelease
```

未配置固定密钥时，CI 仍会构建 Debug 测试包和未签名 Release 包；未签名 Release 包不得安装或分发。正式发布工作流会在密钥缺失时直接失败。

## 密钥保管

- 至少保存两份离线备份，分别放在不同的安全位置。
- 不要把密码与 keystore 放在同一个公开目录。
- 任何密钥泄露都应视为永久泄露；不要在已有客户安装后随意更换签名密钥。
