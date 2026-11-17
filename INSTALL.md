# 安装说明（逐步）

本文件是「一步一步照着做」的版本。总览见 [README.md](README.md)。

需要装两件东西：

| 文件 | 是什么 | 装在哪 | 版本 |
| --- | --- | --- | --- |
| `g2-lyrics-bridge.apk` | LSPosed 模块 | 手机，LSPosed 里启用 | 0.2.1（versionCode 3） |
| `g2-md3-lyrics.ehpk` | Even Hub 插件 | Even Realities App / 眼镜 | 0.3.0 |

两个文件都不联网、不上传数据。

---

## 0. 检查前置条件

| 项 | 怎么确认 |
| --- | --- |
| 手机已 root | 装 `su -c id` 能用；本项目在小米 17 Ultra / Android 17 / SukiSU Ultra 4.2.1 上验证 |
| LSPosed 已激活 | LSPosed 首页显示「已激活」，能进「模块」页 |
| MD3Music 版本 | 设置 → 关于 → 版本号为 **6.3.0**；包名精简版 `com.md3music.md3music` |
| Even App 版本 | Even Realities App **2.4.0** 或更高 |
| 眼镜固件 | Even App → 设备 → 固件 **2.4.1.4** |

版本对不上时，hook 很可能失效（模块靠结构特征定位被混淆的方法，不是固定方法名）。

---

## 1. 装 LSPosed 模块

1. 把 `g2-lyrics-bridge.apk` 传到手机（数据线 / 网盘 / 聊天工具均可），点开安装。
   - 用 USB 更快：`adb install -r g2-lyrics-bridge.apk`
2. 打开 **LSPosed** → 底部「**模块**」→ 找到 **G2 Lyrics Bridge** → 打开右侧开关。
3. 展开该模块的「**作用域**」，勾上你实际装的 MD3Music：
   - 精简版 → 勾 `com.md3music.md3music`
   - 随身听兼容版 → 勾 `com.apple.android.music`
   - 不确定就两个都勾（没装的那个不会有副作用，模块自己会判断）。
4. **强制停止 MD3Music**（设置 → 应用 → MD3Music → 强行停止），再重新打开；或直接重启手机。

模块没有桌面图标，也不需要单独启动 —— 它只活在 MD3Music 进程里。

> 提醒：装完模块后如果 MD3Music 直接闪退，说明版本不匹配：先停用模块，把 MD3Music 升/降回 6.3.0 再试。

---

## 2. 在 MD3Music 里打开「词幕」开关

MD3Music 把歌词交给系统有两条通道，模块抓的是 **Lyricon（词幕）实时歌词桥接** 这条：

1. 打开 MD3Music → 设置 → 找 **「词幕」/「实时歌词桥接」/「Lyricon 提供方」** 这类开关（6.3.0 在歌词/播放相关设置里）。
2. 打开它，然后播一首有歌词的歌（本地 LRC 或 App 内能正常滚动的那种都行）。

> 为什么要开：模块截获的是这条通道上 Dart 交给 Kotlin 的「歌曲 + 全部歌词行」。开关关着，这份数据根本不会产生。只需设置一次，之后一直有效。

---

## 3. 30 秒验证桥接是否活着

手机浏览器打开：

```
http://127.0.0.1:21771/v1/health
```

正常应看到类似：

```json
{"ok":true,"package":"com.md3music.md3music","status":"lyric hook: setSong x2","lyricHook":true,"setSong":2,"lines":67,"revision":1418,"isPlaying":true}
```

对照表：

| 现象 | 含义 / 怎么办 |
| --- | --- |
| 打不开、连接被拒 | 模块没启用 / 作用域没勾 / MD3Music 进程没起来（先播一首歌再试） |
| `lyricHook:false`，`status:"waiting for AudioPlaybackService"` | 还在等 Service 创建，或没走词幕通道（回第 2 步） |
| `lyricHook:true`，`setSong:0` | hook 挂上了但还没收到歌词：播一首歌；一直为 0 说明词幕开关没真正生效 |
| `setSong` 在涨、`lines` > 0 | 通了，`lines` 就是这首歌的歌词行数 |
| `isPlaying:false` | 当前暂停（眼镜上 10 秒后会自动收页，属正常） |

完整数据：`http://127.0.0.1:21771/v1/lyrics`，其中 `lines` 为 `[{"b":起始毫秒,"e":结束毫秒,"t":"原文"}]`。

其他接口：

- `GET /v1/logs` —— 插件上报的日志（环形 200 条），排查眼镜端问题最有用。
- `POST /v1/log` —— 插件写日志用（`text/plain`，返回 204）。

---

## 4. 把插件装到眼镜上

`.ehpk` 是 Even 的私有插件包（文件头是 `EHPK` 魔数，不是普通 zip），只能由 Even App / CLI 识别。两条路选一条：

### 方式 A：Even Hub 网页端上传（推荐，一次装好长期可用）

1. 电脑浏览器打开 Even Hub 开发者中心，登录后进入「我的项目」→「上传包」。
2. 选 `g2-md3-lyrics.ehpk`，等它校验通过（页面会显示版本号 0.3.0）。
3. 手机 Even App 里进入「**我的插件**」，找到 **MD3 Lyrics**，点安装/添加到眼镜。
4. 在眼镜上打开该插件（或直接在手机上打开）。

### 方式 B：局域网开发模式（改代码时最快）

电脑上、仓库根目录：

```powershell
pwsh -NoProfile -File scripts\serve-dev.ps1
```

它会构建插件 → 起一个静态服务器（`http://<电脑IP>:8099`）→ 用 Even Hub CLI 生成二维码。手机 Even App 扫这个码，就能把电脑上的 `dist/` 当插件跑。电脑和手机要在同一个 Wi-Fi。

> 开发模式装出来的插件位于 `develop/com.personal.g2lyricsbridge/`，卸载/重装都只影响这一份。

---

## 5. 验收

1. 眼镜上应出现三行：上一句 / 当前句（更亮）/ 下一句。
2. 手机上点「打开」进入插件页，能看到：
   - 三行实时预览（与眼镜一致）
   - 桥接状态（`127.0.0.1:21771` 是否连上）、歌曲名、播放/暂停、进度、眼镜页状态
   - 最近 12 行日志
3. 暂停播放，10 秒后眼镜页面自动收起；恢复播放，页面立刻回来。

如果手机点「打开」弹「功能正在运行中 / 开启新功能前需要退出当前功能」，选**退出运行并继续**（开发插件在 App 启动时就已经在后台跑起来了，这属于正常提示）。

---

## 6. 常见故障对照

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 眼镜上一片空白 | 页面没创建成功 | 看手机预览页的「眼镜页」一栏与日志里的 `shape ... -> N`；`0` 是成功，`1` 是宿主拒绝 |
| 三行歌词全一样亮 | 升级请求里没带 `textColor` | 本项目已在每次 `textContainerUpgrade` 里带亮度（当前句 4 / 上下句 2），旧版本请更新 |
| 歌词停在某一句不再变 | 桥接断了或 MD3Music 被系统冻结 | 手机打开 `/v1/health` 看 `revision` 是否在涨 |
| 播放中歌词突然消失 | 宿主收到 `FOREGROUND_EXIT` 收起了页面 | 本项目会在 2.5 秒后自动重开；连续 3 次才停 |
| 插件页全黑 | 插件请求被 App 的 WebView 代理拦了 | 看日志里的 `读歌词失败：...`，本项目会打印状态码与返回体前 160 字 |
| `GET /v1/health` 只有 IPv6 能连 | 旧版桥接只监听 `[::1]` | 升级到 0.2.1 及以后（双栈监听） |

---

## 7. 卸载 / 回滚

1. Even App 里删除插件（我的插件 → MD3 Lyrics → 移除）。
2. LSPosed 里停用 **G2 Lyrics Bridge**，再卸载 APK。
3. 建议把 MD3Music 的「词幕」开关关回去（不影响其他功能）。

---

## 8. 隐私

- 桥接服务只绑 `127.0.0.1` 与 `[::1]`，外部设备访问不到。
- 插件只访问 `http://127.0.0.1:21771` 与 `http://localhost:21771`（写在 `app.json` 的 network 白名单里）。
- 不上传任何数据，不包含统计、不联网到第三方。
