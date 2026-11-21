# 预编译产物

不想自己编译的话，直接用这里两个文件（源码见仓库根目录）。

| 文件 | 说明 | 校验 |
| --- | --- | --- |
| `g2-lyrics-bridge-0.3.4.apk` | LSPosed 模块，装到手机 | 25,043 字节，调试签名（CN=G2 Lyrics Bridge），SHA-256 `25A0AB49CB6D0C1A3258659AAB705DD39641A0BEEA2AD7A6C3454C134BB97C37` |
| `g2-md3-lyrics-0.3.4.ehpk` | Even Hub 插件，装到眼镜 | 75,219 字节，`evenhub pack --sdk-ver 0.0.16` 产出，SHA-256 `279DD4A0C5CFE3C05012E3A501ACC356C69528570106891E038E2A830E7A1500` |

安装步骤见 [../INSTALL.md](../INSTALL.md)。

> 签名是自用调试 keystore，仅供个人使用；要发布或长期使用，请用 `scripts/build-apk.ps1` 换成自己的 keystore 重新编译。
> 两个包都不联网、不上传数据，全部通信走手机本机回环（`127.0.0.1:21771`）。
