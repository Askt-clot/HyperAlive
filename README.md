# HyperAlive / 魅族息屏 (io.github.askt_clot.hyperalive) —— LSPosed 模块

> 面向小米澎湃 OS 4（Xiaomi HyperOS），实现魅族 Alive 壁纸「息屏 AOD ➔ 锁屏 ➔ 桌面」三态转场。已在小米 17 Pro Max 上实测，其他机型和系统版本待验证。
>
> 采用 **LSPosed 模块 + 自建 WallpaperService (GLES 3.0)** 实现。

---

## 一、核心特性与视觉风格

1. **息屏视觉风格**：
   - **启航**：魅族经典圆形透镜，中心相框自转，全屏大圆爆发扩散铺满屏幕。
   - **留光**：45° 倾角流光拍立得双层相框遮罩，优雅绽放转场。
   - **星月**：魅族经典弯月星芒遮罩，32 帧离散掩码序列与高斯微朦胧渐开绽放铺满全屏。
   - **轻启、山脉、银河**：另外三种照片息屏样式。
2. **自定义壁纸跟随与取景框**：
   - 支持相册任意照片跟随息屏与锁屏。
   - 内置图片取景器，支持双指缩放、拖动和恢复默认取景。
   - 六种照片息屏样式均可拖动预览或用水平、垂直滑块调整位置，拖动时显示参考线。
3. **自绘魅族经典时钟 (FlymeClockView)**：
   - 纯 Canvas 自绘魅族特色时钟排版（双行堆叠、纵向拉伸、极简单行等风格）。
   - 支持时钟色彩、字体粗细、大小缩放微调，并附带农历日期与极简电量胶囊。
4. **照片与动态壁纸**：
   - 照片壁纸使用桌面和锁屏双槽，支持分别选择图片和纹理。
   - 内置 36 款动态壁纸，由 `QihangDynamicWallpaper` 统一承接息屏、锁屏与桌面。
5. **深度系统级转场协同（LSPosed Hook）**：
   - **系统电源前馈**：监听主屏所在的 `PowerGroup` 状态，分发息屏和亮屏转场。
   - **主屏息屏转场**：在 HyperAlive 接管锁屏壁纸且 AOD 已开启时，按转场阶段调整显示状态和淡出动画，减少黑闪。
   - **SystemUI 协同**：监听 `ScrimController.updateScrims`，为三态切换提供补充信号。息屏策略生效时，拦截 `KeyguardPanelViewController.doWallpaperBlackAnim` 触发的锁屏壁纸黑屏动画。

---

## 二、版本演进历史

| 版本 | 核心变更说明 |
| :--- | :--- |
| **v1.0.0** | 实现「启航」与「留光」双息屏视觉、Flyme 自绘时钟排版、壁纸跟随与基础 LSPosed 转场 Hook。 |
| **v1.0.1** | 完善双槽位壁纸架构（which=1 桌面 + which=2 锁屏），协同 HyperOS 原生全屏 AOD（`full_screen_aod_on` 模式），留光相框遮罩高清屏适配。 |
| **v1.0.2** | 改用单槽位 `which=3` 一体化渲染，处理当时双槽切换时因跨窗口重建渲染表面而出现的黑闪。加入 `system_server` 电源事件联动与 Doze 状态重定向。 |
| **v1.0.3** | 修复已知问题，提升稳定性，新增星月息屏样式 |
| **v1.0.4** | 修复桌面进入息屏时的亮度异常和部分黑闪问题，优化界面并提升稳定性。 |
| **v1.0.5** | 新增 36 款动态壁纸，以及轻启、山脉、银河三种息屏样式。支持拖动和滑块调整六种息屏样式的位置，修正魅族时钟的农历日期。 |

---

## 三、工程结构与快速构建

### 工程结构

```text
HyperAlive/
├── app/src/main/
│   ├── AndroidManifest.xml        # 包名、版本和 LSPosed 模块声明
│   ├── java/io/github/askt_clot/hyperalive/    # 壁纸引擎、预览编辑器、时钟和 LSPosed Hook
│   ├── assets/                   # 动态壁纸资源、照片息屏遮罩和 GLSL 着色器
│   └── res/                      # 图标、文案和 Android 资源
├── stub/                         # 离线编译使用的 Xposed API 桩
├── tools/                        # 转场、息屏宿主和壁纸备份回归
└── build.py                      # 免 Gradle 的本地构建脚本
```

### 下载与安装

下载安装包后，在 LSPosed 中启用 HyperAlive，按第四节设置作用域。APK 的最低安装要求为 Android 10（API 29）。[GitHub Releases](https://github.com/Askt-clot/HyperAlive/releases/latest)

使用「备份并应用」或恢复原壁纸时，需要为 HyperAlive 授予 Root 权限。

从旧包名 `com.hyperalive.aod` 或 `com.qihang.aod` 迁移时，Android 会将新包视为独立应用。请在 LSPosed 中停用旧模块、启用新模块并重新设置作用域，再在新应用中设置壁纸。旧应用的私有设置不会自动迁移。

GitHub Releases 中的 v1.0.0 至 v1.0.5 APK 均已迁移到新包名。v1.0.0 和 v1.0.1 的实际版本名称已更正，版本代码分别为 `1` 和 `2`。历史标签保留了当时的源码，当前源码以 `main` 分支为准。

### 本地构建

准备 Python 3、JDK 和 Android SDK（含 Android 平台与 Build Tools）。通过 `JAVA_HOME` 或 `PATH` 指定 JDK，通过 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 指定 SDK。

用环境变量提供自己的签名配置。`HYPERALIVE_KEYSTORE` 指向密钥库，`HYPERALIVE_KS_ALIAS` 指定密钥别名，`HYPERALIVE_KS_PASS` 指定密钥库口令。密钥口令不同时，另设 `HYPERALIVE_KEY_PASS`。

在工程根目录执行以下命令构建 1.0.5，并在已连接设备上安装。

```bash
python build.py
adb install -r build/HyperAlive-v1.0.5.apk
```

设备上已有同包名应用时，`adb install -r` 要求新旧 APK 的签名匹配。

运行 `python build.py --help` 可以查看 SDK、签名和输出路径的命令行选项。

---

## 四、LSPosed 推荐作用域

在 LSPosed 管理器中选择以下推荐作用域。

- `系统框架` (`android`)
- `System UI` (`com.android.systemui`)
- `System UI 插件` (`miui.systemui.plugin`)
- `息屏与锁屏` (`com.miui.aod`)
- `主题壁纸` (`com.android.thememanager`)

---

## 五、资产说明

HyperAlive 有权授权的自实现代码采用 MIT 许可。移植自 OpenAlive 的代码保留上游的版权声明和 MIT 许可正文。[HyperAlive MIT 许可证](LICENSE) · [OpenAlive MIT 许可证](app/src/main/assets/licenses/OpenAlive-MIT.txt)

**部分资产来源于魅族 Flyme OS。** 随仓库或 APK 分发的第三方字体、壁纸视频、效果遮罩、着色器和预览素材保留各自权利人的版权及适用条款，排除在 HyperAlive 的 MIT 授权范围之外。具体来源和排除路径见 [资源说明](THIRD_PARTY_NOTICES.md)。

---

## 六、致谢

感谢 OpenAlive 作者 **下北沢最速傳說** 和项目贡献者。HyperAlive 移植了部分 OpenAlive 代码，并参考了其照片息屏样式、三态转场和壁纸纹理的实现。[OpenAlive 项目](https://github.com/Maga-King/OpenAlive) · [代码与资源来源](THIRD_PARTY_NOTICES.md)
