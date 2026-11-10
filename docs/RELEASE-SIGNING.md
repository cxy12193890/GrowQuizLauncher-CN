# TVQuizLock 固定签名配置

Android 更新必须使用同一份签名密钥。密钥丢失或更换后，旧安装包无法通过普通覆盖安装升级。

## 一次性生成密钥（Windows）

在 Windows PowerShell 中进入仓库目录。电脑需要安装 JDK 17 或更高版本；Android Studio 自带的 JBR 也包含 `keytool.exe`。

先确认命令可用：

```powershell
keytool -help
```

只生成一次密钥。不要在后续版本重新生成：

```powershell
keytool -genkeypair -v -storetype PKCS12 -keystore "$PWD\tvquizlock-release.keystore" -alias tvquizlock -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TVQuizLock Release, OU=Release, O=TVQuizLock, L=Unknown, ST=Unknown, C=CN"
```

- PKCS12 格式要求密钥条目密码与 keystore 密码相同；设置一个足够强的密码（建议至少 16 位），后续 GitHub Secrets 中的 `ANDROID_KEYSTORE_PASSWORD` 与 `ANDROID_KEY_PASSWORD` 填写同一个值。
- 记住密钥别名：`tvquizlock`。
- 不要把密码写进命令、聊天记录、公开文件或 Git 提交。

生成后立即检查密钥：

```powershell
keytool -list -v -keystore "$PWD\tvquizlock-release.keystore" -alias tvquizlock
```

记录输出中的 **SHA256** 证书指纹。正式密钥配置完成后，应将该指纹固定到发布检查中，防止密钥被意外替换。

## GitHub Actions Secrets

先把 keystore 文件转换为 Base64。以下命令只把结果复制到当前 Windows 剪贴板，不会创建明文 Base64 文件：

```powershell
$bytes = [System.IO.File]::ReadAllBytes("$PWD\tvquizlock-release.keystore")
[System.Convert]::ToBase64String($bytes) | Set-Clipboard
```

在 GitHub 仓库打开：

**Settings → Secrets and variables → Actions → New repository secret**

添加以下四项：

| Secret 名称 | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 剪贴板中的 keystore Base64 全文 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | `tvquizlock` |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

不要把 keystore、密码或 Base64 内容提交到 Git 仓库，也不要把同一份私钥放进公开 Release 资产。不要将这些秘密粘贴到聊天窗口。

## 本地构建

也可以在仓库根目录创建被 `.gitignore` 忽略的 `keystore.properties`：

```properties
storeFile=C:/absolute/path/to/tvquizlock-release.keystore
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
- 密码保存在密码管理器中，不要与 keystore 放在同一个公开目录。
- 不要把唯一密钥保存在电脑桌面、临时目录或云端公开文件夹。
- 任何密钥泄露都应视为永久泄露；不要在已有客户安装后随意更换签名密钥。
