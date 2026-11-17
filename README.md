# G2 MD3 歌词（g2-md3-lyrics）

把手机上 **MD3Music** 正在播放的歌词，实时投到 **Even G2 智能眼镜**上：上一句 / 当前句（更亮）/ 下一句，只显示原文。

手机端同时提供一个**实时预览页**，可以对着眼镜核对当前应该显示的三行内容、桥接状态和运行日志。

<p align="center">
  <img src="docs/images/glasses-lyrics.png" width="560" alt="眼镜上的三行歌词">
</p>

---

## 1. 它是怎么工作的

```
MD3Music 播放
   │  Dart 把「当前歌曲 + 全部歌词行 + 播放进度」交给 Kotlin
   ▼
LSPosed 模块（运行在 MD3Music 进程内，com.personal.g2lyricsbridge）
   │  hook 通道分发器拿到歌词 Map；监听 MediaSession 拿到播放状态
   ▼
http://127.0.0.1:21771/v1/lyrics   ← 只绑回环，局域网与外网都访问不到
   ▲
   │  插件每 250 ms 轮询一次
Even Hub 插件（在 Even Realities App 的 WebView 里运行，com.personal.g2lyricsbridge）
   ▼
G2 眼镜：上一句 / 当前句 / 下一句（通过 Bluetooth 通道下发文本容器）
```

设计取舍：

- **不改 MD3Music 源码**（它是 AGPL-3.0），所有逻辑都在 LSPosed 模块里运行时注入。
- **抗 R8 混淆**：MD3Music 6.3.0 的 release 包把歌词入口方法内联掉了，所以主策略改成挂 Flutter 的**通道分发器**（`setSong` 入口），按 `Result` 形态做结构识别；另有「按名字找 Companion」「按签名扫 Service」两条兜底。
- **只走回环**：服务绑定 `127.0.0.1:21771` 与 `[::1]:21771`（双栈），外部访问不到。
- **整行高亮**：G2 的文本容器只有 `textColor`（亮度 0–4）这一个强调手段，没有字号字段，所以当前句整行提亮，不能行内逐字点亮，也不能把字放大。
- **不抢前台**：眼镜下发 `FOREGROUND_EXIT` 时，2.5 秒后自动恢复；30 秒内连续 3 次被收起，才判定为「用户真的离开了」，停止自动弹出，等下一次点按 / 切歌再回来。

---

## 2. 你需要准备什么

### 2.1 硬件 / 系统

| 项目 | 要求 |
| --- | --- |
| 手机 | Android，**已 root**，能装 LSPosed（本项目在小米 17 Ultra / Android 17 / SukiSU Ultra 上验证） |
| 框架 | **LSPosed 2.1.1**（Zygisk 版或对应变体，能正常给应用配作用域） |
| 音乐 App | **MD3Music 6.3.0**，包名 `com.md3music.md3music`（精简版）或 `com.apple.android.music`（随身听兼容版） |
| 眼镜 | **Even G2**，固件 2.4.1.4 |
| 手机 App | **Even Realities App 2.4.0**（插件 `min_app_version` 就是 2.4.0） |

> 只支持上面这套组合。不同固件 / 不同 MD3Music 版本都可能让 hook 失效（模块靠结构特征定位，不是靠固定方法名）。

### 2.2 电脑端工具（只在你需要自己编译时用到）

| 工具 | 用途 | 说明 |
| --- | --- | --- |
| **Node.js ≥ 18**（实测 22） | 构建插件、跑单测 | 自带 npm |
| **npm 依赖** | `@evenrealities/even_hub_sdk`、`@evenrealities/evenhub-cli`、`esbuild` | `npm install` 自动装 |
| **JDK 17** | 编译 LSPosed 模块 | 项目用 `javac` 直接编译，不依赖 Gradle |
| **Android SDK build-tools** | `aapt2` `d8` `zipalign` `apksigner` | 打包并签名 APK |
| **android.jar（API 35）** | 编译期引用 | `platforms/android-35/android.jar` |
| **Xposed API（api-82.jar）** | 编译期引用 | LSPosed 官方仓库提供 |
| **adb** | 推包、装包、看日志 | `platform-tools` |
| **Even Hub CLI** | 打 `.ehpk`、传插件 | 已在 `devDependencies` 里 |

> 只想用、不想编：直接用仓库 Releases 里的 `g2-lyrics-bridge.apk` 和 `g2-md3-lyrics.ehpk`，跳到第 3 节。

---

## 3. 快速上手（用现成的两个包）

两件东西，缺一不可：

| 文件 | 是什么 | 装在哪 |
| --- | --- | --- |
| `g2-lyrics-bridge.apk` | LSPosed 模块 | 手机，LSPosed 里启用并勾作用域 |
| `g2-md3-lyrics.ehpk` | Even Hub 插件 | Even Realities App / 眼镜 |

完整、逐步（含界面位置、验证命令、报错对照）请看 **[INSTALL.md](INSTALL.md)**。最短路径：

1. 装 `g2-lyrics-bridge.apk` → LSPosed「模块」里启用 **G2 Lyrics Bridge** → 作用域勾上 MD3Music → 强行停止 MD3Music 再打开。
2. 在 MD3Music 里打开**词幕 / 实时歌词桥接**开关（不开就没有歌词数据）。
3. 手机浏览器打开 `http://127.0.0.1:21771/v1/health`，看到 `"ok":true` 且 `"lines"` 大于 0 就通了。
4. 把 `g2-md3-lyrics.ehpk` 传到眼镜（Even Hub 网页端上传，或用开发者中心的局域网开发模式），在 Even App 里打开插件。
5. 播一首歌 → 眼镜上出现三行。

---

## 4. 日常使用

- **播放** → 页面自动出现；**暂停** → 保留 10 秒后收页；**恢复播放** → 立即重现；**切歌** → 直接换新歌词。
- 当前句亮度 4、上下句亮度 2（`src/renderer.js` 里的 `BRIGHT` / `DIM`，想更强对比可以把 `DIM` 改成 1）。
- 手机端预览页：打开插件后能看到「眼镜上此刻应显示的三行」+ 桥接/歌曲/进度/眼镜页状态 + 最近日志，方便核对。
- 如果手机点「打开」时弹「功能正在运行中 / 开启新功能前需要退出当前功能」，选**退出运行并继续**即可（开发插件在 App 启动时就已经在后台运行了）。

---

## 5. 自己构建

```powershell
npm install
node scripts/build.mjs            # 插件打包到 dist/
npm test                          # 23 个用例
node_modules\.bin\evenhub.cmd pack app.json dist -o outputs/g2-md3-lyrics.ehpk --sdk-ver 0.0.16
pwsh -NoProfile -File scripts/build-apk.ps1   # 编译并签名桥接 APK
```

`build-apk.ps1` 里写死了 JDK / SDK 的默认路径（`work/android-tools/...`），换机器时改脚本顶部的几个变量，或用 `-JdkHome` / `-SdkDir` 参数覆盖。签名用的是调试 keystore，仅供自用；要长期使用请换成自己的 keystore。

更细的调试手段（模拟器自动化、真机装机脚本、遥测接口）见 **[docs/DEVELOP.md](docs/DEVELOP.md)**。

---

## 6. 目录

```
src/           插件源码：core(纯逻辑) / bridge-client(轮询) / renderer(渲染) / app(状态机) / ui(手机预览) / main(接线)
index.html     手机端预览页
app.json       插件清单（package_id / 版本 / 网络白名单 / 最低 App 版本）
tests/         插件单测与真实 HTTP 传输测试
lsp-module/    LSPosed 模块源码：Md3LyricsHook(注入+回环服务) / LyricMapParser(歌词 Map 解析)
scripts/       build.mjs(插件构建) / build-apk.ps1(APK 全链路) / serve-dev.ps1(局域网开发模式)
docs/          安装细节、开发调试、截图
```

---

## 7. 已知限制

- G2 文本容器**没有字号参数**，只能改亮度；中间句靠亮度和位置区分，无法真正「放大字号」。
- 播放进度由模块插值推算（`positionMs`），与系统播放器可能有几百毫秒偏差。
- 只显示原文：翻译、罗马音即使 MD3Music 里有也不会推送。
- 真机上 `FOREGROUND_EXIT` 出现得比较频繁（宿主会收起非前台页面），所以恢复了「自动重开」策略；如果你希望它更「安静」，把 `src/app.js` 里的 `reopenAt` 延时改大即可。

---

## 8. 许可与免责

- 本仓库代码（模块 + 插件）为个人自用项目，采用 MIT 许可。
- 不包含、不分发 MD3Music 的任何代码或资源；MD3Music 为 AGPL-3.0，请自行从其官方渠道获取。
- 与 Even Realities、MD3Music 官方均无关联。
- root / LSPosed 属于高风险操作，请自行评估；本项目不收集、不上传任何数据，全部通信都走手机本机回环。
