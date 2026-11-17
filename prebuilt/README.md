# 预编译产物

不想自己编译的话，直接用这里两个文件（源码见仓库根目录）。

| 文件 | 说明 | 校验 |
| --- | --- | --- |
| `g2-lyrics-bridge-0.3.0.apk` | LSPosed 模块，装到手机 | 20,947 字节，调试签名（CN=G2 Lyrics Bridge） |
| `g2-md3-lyrics-0.3.0.ehpk` | Even Hub 插件，装到眼镜 | 75,218 字节，`evenhub pack --sdk-ver 0.0.16` 产出 |

安装步骤见 [../INSTALL.md](../INSTALL.md)。

> 签名是自用调试 keystore，仅供个人使用；要发布或长期使用，请用 `scripts/build-apk.ps1` 换成自己的 keystore 重新编译。
> 两个包都不联网、不上传数据，全部通信走手机本机回环（`127.0.0.1:21771`）。
