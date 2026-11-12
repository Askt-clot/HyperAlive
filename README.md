# HyperAlive / 魅族息屏 (com.qihang.aod) —— LSPosed 模块

> 在小米澎湃 OS（Xiaomi HyperOS / Android 14+ / 17）上复刻魅族 Alive 壁纸「息屏 AOD ➔ 锁屏 ➔ 桌面」三态无缝连贯动画。  
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

| 版本 || 核心变更说明 |
| :--- | :--- | :--- |
| **v1.0.0** | 实现「启航」与「留光」双息屏视觉、Flyme 自绘时钟排版、壁纸跟随与基础 LSPosed 转场 Hook。 |
| **v1.0.1** | 1.1 稳定基线版。完善双槽位壁纸架构（which=1 桌面 + which=2 锁屏），协同 HyperOS 原生全屏 AOD（`full_screen_aod_on` 模式），留光相框遮罩高清屏适配。 |
| **v1.0.2** | 全面升级为单槽位 `which=3` 一体化常驻渲染，彻底消灭 74ms 跨窗口黑闪；深度注入 `system_server` 电源前馈与 Doze 状态重定向；推出自研免 Gradle 极速 Python 构建工具链。 |
| **v1.0.3** |修复已知问题，提升稳定性，新增星月息屏样式

---

## 三、工程结构与快速构建

本项目采用标准 Android Gradle 工程结构，同时内置了无需 Gradle 的超快速 Python 本地构建工具链：

```text
D:\HyperAlive
├── app/                                # 核心模块工程
│   └── src/main/
│       ├── java/com/qihang/aod/        # Java 源码（GLES 渲染、时钟、UI、LSPosed Hook）
│       │   ├── LensRenderer.java       # GLES 3.0 启航透镜与留光/星月相框渲染器
│       │   ├── FlymeClockView.java     # 魅族风格自绘时钟
│       │   ├── PhotoCropActivity.java  # 取景框裁剪与缩放
│       │   ├── QihangWallpaper.java    # 单槽位一体化 WallpaperService
│       │   ├── LspState.java           # LSPosed 激活状态三态判定与锁屏安全管理
│       │   ├── XposedInit.java         # LSPosed 系统 Hook 入口（门面分发）
│       │   ├── hook/                   # ★ 全新模块化 Hook 核心层（PMS/SysUI/AOD/时钟与预览协同）
│       │   └── root/                   # 独立进程工具（app_process 直连设壁纸与 LspDb）
│       ├── res/                        # 资源文件（图标、布局、文本、作用域配置）
│       └── assets/                     # 着色器(GLSL)、遮罩素材(启航/留光/星月)与配置
├── stub/                               # Xposed API 离线编译桩（免外网依赖）
├── tools/                              # 自动化调试、动效回归与时钟时序测试工具链
├── build.py                            # 免 Gradle 本地高速构建脚本（~3秒生成 APK）
├── deploy.py                           # ADB 快速安装、激活作用域与单槽绑定工具
```

### 极速构建与真机部署

```bash
# 1. 极速全量编译并签名
python build.py

# 2. 一键安装并配置 LSPosed 作用域
python deploy.py install

# 3. 绑定单槽位一体化动态壁纸
python deploy.py setwall
```

---

## 四、LSPosed 推荐作用域

在 LSPosed 管理器中勾选以下系统作用域：
- `系统框架` (`android`)
- `System UI` (`com.android.systemui`)
- `System UI 插件` (`miui.systemui.plugin`)
- `息屏与锁屏` (`com.miui.aod`)
- `主题壁纸` (`com.android.thememanager`)
