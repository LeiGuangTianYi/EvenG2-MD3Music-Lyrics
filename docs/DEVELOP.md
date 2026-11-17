# 开发与调试

## 1. 工具链版本

本项目**不依赖 Gradle**，APK 由 `javac → aapt2 → d8 → zipalign → apksigner` 手工链路产出（`scripts/build-apk.ps1`）。

| 组件 | 实测版本 | 说明 |
| --- | --- | --- |
| Node.js | 22 | 插件构建与单测 |
| esbuild | latest | 打成 IIFE 单文件 `dist/plugin.js` |
| `@evenrealities/even_hub_sdk` | 0.0.16 | 眼镜侧 API |
| `@evenrealities/evenhub-cli` | latest | `evenhub pack / qr / create / upload` |
| JDK | 17 | 模块编译 |
| Android build-tools | 35 | `aapt2` `d8` `zipalign` `apksigner` |
| android.jar | API 35 | 编译期引用 |
| Xposed API | api-82 | 编译期引用 |
| adb | platform-tools | 装机与遥测 |

`scripts/build-apk.ps1` 顶部集中放这些路径，换机器只改那里（或传 `-JdkHome` / `-SdkDir`）。

## 2. 常用命令

```powershell
npm install
node scripts/build.mjs                        # src/ + index.html + app.json → dist/
npm test                                      # node --test tests/*.test.mjs
node_modules\.bin\evenhub.cmd pack app.json dist -o outputs/g2-md3-lyrics.ehpk --sdk-ver 0.0.16
pwsh -NoProfile -File scripts/build-apk.ps1   # 产出 outputs/g2-lyrics-bridge.apk
pwsh -NoProfile -File scripts/serve-dev.ps1   # 局域网开发模式（改代码时用）
```

> `npm test` 直接写 `node --test tests/*.test.mjs`：Node 22 上把目录传给 `--test` 会报 `MODULE_NOT_FOUND`。

## 3. 插件侧的三个坑（都已踩过）

1. **页面只能有一个**：宿主对「已存在的页面」重复调 `createStartUpPageContainer` 一律返回 `invalid(1)`。所以渲染层绝不因为一次失败就去 `shutDownPageContainer`，而是记住已经创建成功，之后只做 `textContainerUpgrade`。
2. **`launchSource` 不是销毁信号**：App 发来 `launchSource appMenu` 时页面往往还活着，把它当「已关闭」会造成「每 250 ms 重建 → 被拒 → 关页」的死循环。
3. **亮度必须每次升级都带**：`textColor`（0–4，创建时省略用默认 4，升级时省略保持当前亮度）。想让当前句更亮，就要在每次 patch 里显式给 4（上下句给 2）。

此外：

- `app.json` 必须是 **UTF-8 无 BOM**，否则 `JSON.parse` 会报 `Unexpected token '﻿'`。
- `dist` 里 `index.html` 引用的脚本路径要相对（`./plugin.js`），绝对路径 `/assets/...` 在某些宿主上会 404。
- 网络白名单要同时写 `http://127.0.0.1:21771` 和 `http://localhost:21771`（不同 Android 版本解析回环的方式不同）。

## 4. 模拟器自动化

`@evenrealities/evenhub-simulator` 可以在电脑上渲染眼镜画面，并开一个 HTTP 自动化端口：

```powershell
node node_modules\@evenrealities\evenhub-simulator\bin\index.js http://127.0.0.1:8099/ --automation-port 9898
```

配合本地静态服务器（`python -m http.server 8099 --directory dist`）使用。可用接口：

| 接口 | 用途 |
| --- | --- |
| `GET /api/ping` | 探活，返回 `pong` |
| `GET /api/screenshot/glasses` | 眼镜画面截图（576×288） |
| `GET /api/screenshot/webview` | 手机页截图 |
| `GET /api/console` | 控制台日志 |
| `POST /api/input` | 模拟点击/滚动 |

同一个端口只能起一个实例，重启前先结束占用该端口的进程。

## 5. 真机装机（开发模式目录）

Even App 把开发插件放在：

```
/data/data/com.even.sg/files/even/even_hub/<userId>/develop/<package_id>/
```

替换 `dist/` 后重启 App 即可生效（需要 root）：

```bash
adb shell "su -c 'rm -rf /data/local/tmp/g2ld'"
adb push dist /data/local/tmp/g2ld
adb shell "su -c 'd=/data/data/com.even.sg/files/even/even_hub/<userId>/develop/com.personal.g2lyricsbridge; \
  rm -rf \$d/dist && mkdir -p \$d/dist && cp -r /data/local/tmp/g2ld/. \$d/dist/ && \
  chown -R <appuser>:<appuser> \$d && \
  find \$d -type d -exec chmod 700 {} \; && find \$d -type f -exec chmod 600 {} \;'"
adb shell am force-stop com.even.sg
adb shell am start -n com.even.sg/com.even.sg.MainActivity
```

注意：`adb push` 到已存在的目标目录会生成嵌套子目录（`g2ld/dist/...`），每次换一个新的目录名最省事。

## 6. 遥测

模块在 `127.0.0.1:21771` 上提供三个接口，调试时最常用 `/v1/logs`（插件通过 `POST /v1/log` 把日志推进来，环形保留 200 条）：

```powershell
adb forward tcp:21771 tcp:21771
curl http://127.0.0.1:21771/v1/health
curl http://127.0.0.1:21771/v1/logs
```

关键日志行：

- `shape rows3+border -> 0` —— 页面创建成功（`0` success / `1` invalid / `2` oversize / `3` outOfMemory）。
- `page opened with rows3+border` —— 锁定该形状。
- `sysEvent 4` / `5` —— `FOREGROUND_ENTER` / `FOREGROUND_EXIT`。
- `N upgrades refused` —— 有 upgrade 被宿主拒绝（正常应为 0）。

## 7. 布局与常量

`src/renderer.js`：

```js
export const BRIGHT = 4;   // 当前句亮度
export const DIM = 2;      // 上下句亮度（想更强对比可改 1）
export const LAYOUT = [...];  // 画布 576×288，三行 x=24 / width=528
```

`CANDIDATES` 是「形状协商」列表（`rows3+border` 等 5 种），逐个试到宿主接受为止，第一个成功即锁定。

## 8. 测试覆盖

`npm test` 共 23 例，包括：歌词行插值（`core`）、渲染（创建一次 + 三行亮度首刷 + 只补差异行）、状态机（暂停 10 秒收页 / 恢复重现 / 前台退出重开）、真实 HTTP 传输（起一个本地服务跑通 `/v1/lyrics` 的解析流程）。
