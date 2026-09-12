# Gridea 手机编辑器

> 在手机上写 Gridea 博客，并用「压缩包通道」高速同步到电脑。

基于 [Gridea Pro](https://github.com/Gridea-Pro/gridea-pro)（GPL-3.0）修改而来的 Android 客户端，
让你能在手机上管理文章与配置，并通过 SMB / WebDAV 把内容同步到运行 Gridea 的电脑。

本项目本身同样以 **GPL-3.0** 开源（见 [LICENSE](LICENSE)）。

> 本仓库是在 Gridea Pro 基础上的**修改版本**（修改于 2026 年）。
> 上游：[Gridea Pro](https://github.com/Gridea-Pro/gridea-pro)，
> 其源头为 [Gridea](https://github.com/getgridea/gridea)（作者 [@EryouHao](https://github.com/EryouHao)）。

## 功能

- 手机端博客写作：文章、配置的管理与编辑
- 通过 **SMB / WebDAV** 与电脑上的 Gridea 站点目录同步
- **压缩包通道**（核心特性）：让电脑把站点目录打包成 zip，手机下载后本地解压比对，只传差异
  - 比逐文件传输快一个数量级（实测站点 `themes` 有 2000+ 小文件，逐文件同步极慢）
  - 配套 `pack-server/`：电脑端常驻打包服务，手机一点就「现打现下」

## 目录结构

| 路径 | 说明 |
|------|------|
| `app/` | Android 应用（Kotlin + Jetpack Compose） |
| `pack-server/` | 电脑端 PowerShell 打包服务（开机自启、按需打包） |
| `pack-server/pack-config.example.json` | 打包服务配置示例 |

## 构建

要求：Android Studio / JDK 17、Android SDK（compileSdk 37）。

```bash
git clone <本仓库>
cd <仓库根目录>
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

> 默认仅 debug 构建，未包含签名配置。正式发布前请自行在 `app/build.gradle.kts` 配置 `signingConfig`。

## 同步配置（手机端）

在 App 的「同步」页填写：

- 协议：SMB 或 WebDAV
- 主机地址 / 端口 / 共享名 / 子目录
- 用户名、密码

具体填法见 App 内的提示文字（例如电脑 IP `192.168.1.100`、共享名 `blog`、
站点就在共享根目录时子目录留空）。

## 压缩包通道与电脑端打包服务

SMB / WebDAV 只能读写文件，**无法让手机命令电脑压缩**。
所以「手机点一下、电脑就打包」需要电脑上有个常驻的小服务来接请求 —— 即 `pack-server/`。

### 1. 配置站点目录

复制 `pack-server/pack-config.example.json` 为 `pack-server/pack-config.json`，
把 `SiteDir` 改成你的 Gridea 站点目录（里面有 `posts`、`config` 子目录的那个）：

```json
{
  "SiteDir": "C:/path/to/your/gridea/site",
  "ArchiveName": "site.zip",
  "Port": 8765,
  "ExcludeDirs": ["output", ".git", "node_modules", ".idea"]
}
```

### 2. 启动服务

- 临时调试：双击 `pack-server/启动打包服务.bat`（前台窗口，关掉即停）
- 长期用：双击 `pack-server/安装开机自启.bat`（提权后注册开机任务，后台运行）

> 必须**以管理员身份**运行：Windows 只允许管理员监听「局域网可访问」的地址，
> 普通权限只能绑本机回环，手机根本连不上。

启动后窗口会打印形如 `http://192.168.x.x:8765` 的地址。

### 3. 手机端开启压缩包通道

同步页 → 打开「通过压缩包同步」→ 填：

- 压缩包在远端的路径：`site.zip`
- 电脑端打包服务地址：上面打印的 `http://192.168.x.x:8765`

点「测试打包服务」，显示「打包服务在线」即可。

### 工作原理

```
手机请求打包 ──HTTP──▶ 电脑 pack-server 把站点目录打成 site.zip
手机下载 site.zip ──▶ 解压到临时目录
拿解压结果当「远端」比对 ──▶ 只上传差异文件
传完 ──▶ 删掉临时目录
（若配了打包服务，下次同步由电脑重打，自然带上本次改动）
```

## 许可证

[GPL-3.0](LICENSE) © Gridea Pro 及本仓库贡献者。
基于 [Gridea Pro](https://github.com/Gridea-Pro/gridea-pro)（GPL-3.0）修改。
