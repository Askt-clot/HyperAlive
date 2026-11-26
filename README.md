# HyperAlive / 魅族息屏 (com.qihang.aod) —— LSPosed 模块

> 在小米澎湃 OS4（Xiaomi HyperOS / Android 14+ / 17）上复刻魅族 Alive 壁纸「息屏 AOD ➔ 锁屏 ➔ 桌面」三态无缝连贯动画。  
> 采用 **LSPosed 模块 + 自建 WallpaperService (GLES 3.0)** 实现。

---

## 一、核心特性与视觉风格

1. **息屏视觉风格**：
   - **启航**：魅族经典圆形透镜，中心相框自转，全屏大圆爆发扩散铺满屏幕。
   - **留光**：45° 倾角流光拍立得双层相框遮罩，优雅绽放转场。
   - **星月**：魅族经典弯月星芒遮罩，32 帧离散掩码序列与高斯微朦胧渐开绽放铺满全屏。
2. **自定义壁纸跟随与取景框**：
   - 支持相册任意照片跟随息屏与锁屏。
   - 内置图片取景器，支持精准双指缩放、旋转、移动与一键复位居中。
3. **自绘魅族经典时钟 (FlymeClockView)**：
   - 纯 Canvas 自绘魅族特色时钟排版（双行堆叠、纵向拉伸、极简单行等风格）。
   - 支持时钟色彩、字体粗细、大小缩放微调，并附带农历日期与极简电量胶囊。
4. **单槽位一体化架构（which=3 / FLAG_SYSTEM | FLAG_LOCK）**：
   - 统一由 `com.qihang.aod.QihangWallpaper` 承接锁屏与桌面（`which=3`）。
   - **终生常驻单个 Surface 与单个 EGLContext**，彻底解决双槽位切换时 74ms 跨窗口 Surface 销毁重建造成的黑闪。
5. **深度系统级转场协同（LSPosed Hook）**：
   - **系统电源前馈**：Hook `PowerManagerService`，`goToSleep` 瞬时广播 `mode=0`，`wakeUp` 瞬时广播对应亮屏/解锁模式。
   - **防黑屏重定向**：`DisplayPowerState.setScreenState` 将断电入参重定向为 `STATE_DOZE`，杜绝 OLED 物理断电黑闪；阻断 `prepareColorFade` 硬件淡黑。
   - **SystemUI 协同**：监听 `ScrimController.updateScrims` 兜底三态分发；整体拦截 `KeyguardPanelViewController.doWallpaperBlackAnim` 黑屏动画图层。

---

## 二、版本演进历史

| 版本 | 核心变更说明 |
| :--- | :--- |
| **v1.0.0** | 实现「启航」与「留光」双息屏视觉、Flyme 自绘时钟排版、壁纸跟随与基础 LSPosed 转场 Hook。 |
| **v1.0.1** | 1.1 稳定基线版。完善双槽位壁纸架构（which=1 桌面 + which=2 锁屏），协同 HyperOS 原生全屏 AOD（`full_screen_aod_on` 模式），留光相框遮罩高清屏适配。 |
| **v1.0.2** | 全面升级为单槽位 `which=3` 一体化常驻渲染，彻底消灭 74ms 跨窗口黑闪；深度注入 `system_server` 电源前馈与 Doze 状态重定向；推出自研免 Gradle 极速 Python 构建工具链。 |
| **v1.0.3** | 修复已知问题，提升稳定性，新增星月息屏样式 |
| **v1.0.4** | 修复桌面到息屏的亮度异常问题，修复黑闪问题，优化UI界面，提示稳定性 |
| **v1.0.5** | 修复已知问题，新增动态壁纸，新增轻启，山脉，银河息屏样式 |

---

## 三、工程结构与快速构建

### 工程结构

```text
HyperAlive/
├── app/src/main/
│   ├── AndroidManifest.xml        # 包名、版本和 LSPosed 模块声明
│   ├── java/com/hyperalive/aod/    # 壁纸引擎、预览编辑器、时钟和 LSPosed Hook
│   ├── assets/                   # 动态壁纸资源、照片息屏遮罩和 GLSL 着色器
│   └── res/                      # 图标、文案和 Android 资源
├── stub/                         # 离线编译使用的 Xposed API 桩
├── tools/                        # 转场、息屏宿主和壁纸备份回归
└── build.py                      # 免 Gradle 的本地构建脚本
```

### 下载与安装

下载安装包后，在 LSPosed 中启用 HyperAlive，按第四节设置作用域。 [GitHub Releases](https://github.com/Askt-clot/HyperAlive/releases/latest)

### 本地构建

准备 Python 3、JDK 和 Android SDK（含 Android 平台与 Build Tools）。通过 `JAVA_HOME` 或 `PATH` 指定 JDK，通过 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 指定 SDK。

用环境变量提供自己的签名配置。`HYPERALIVE_KEYSTORE` 指向密钥库，`HYPERALIVE_KS_ALIAS` 指定密钥别名，`HYPERALIVE_KS_PASS` 指定密钥库口令。密钥口令不同时，另设 `HYPERALIVE_KEY_PASS`。

在工程根目录执行以下命令构建 1.0.5，并在已连接设备上安装。

```bash
python build.py
adb install -r build/HyperAlive-v1.0.5.apk
```

运行 `python build.py --help` 可以查看 SDK、签名和输出路径的命令行选项。

---

## 四、LSPosed 推荐作用域

在 LSPosed 管理器中勾选以下系统作用域：
- `系统框架` (`android`)
- `System UI` (`com.android.systemui`)
- `System UI 插件` (`miui.systemui.plugin`)
- `息屏与锁屏` (`com.miui.aod`)
- `主题壁纸` (`com.android.thememanager`)

---

## 五、致谢

感谢 **下北沢最速傳說**（**OpenAlive** 作者）开源分享。HyperAlive 在照片息屏样式、三态转场和壁纸纹理的实现中参考了 OpenAlive。 [OpenAlive 项目](https://github.com/Maga-King/OpenAlive)
