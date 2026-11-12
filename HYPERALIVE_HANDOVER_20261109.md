# HyperAlive 调试交接 · 2026-11-09

开发目录为 `D:\ButtonHyperAlive\HyperAlive-debug`。当前源码与最新调试包以这个目录为准。最新包是 `D:\ButtonHyperAlive\HyperAlive-debug\HyperAlive.apk`，SHA-256 为 `4243D7462736AD0CF381C94A4D12A234B679850D8E74DC80DAB4EB670654338C`。该包已安装到调试机，并用于下列最终复测。

## 最终保留的行为

- 锁屏和 AOD 时钟沿用用户发送「之前由于额度耗尽，任务中断，继续当前任务」之前的交接行为。保留当时对偶发双时钟、原生 AOD 时钟延迟出现以及魅族时钟开关不生效的处理。锁屏与 AOD 的魅族时钟开关独立生效，默认显示原生时钟。
- 自动息屏保留提前启动壁纸回缩的修复，覆盖系统因无操作超时而熄屏的路径。锁屏时钟恢复显示时额外请求一次数字重绘，解决回退后复测遇到的锁屏时分空白。
- 保留启航样式在省电停止自转后，经指纹直接从 AOD 解锁到桌面的扩散处理。此前用户实测反馈「有扩散」。此后本轮的时钟回退没有覆盖对应的指纹和壁纸过渡代码。

## 改动定位

以 `D:\ButtonHyperAlive\HyperAlive-debug\backup_timeout_clock_20261109_212452\app\src` 为基线，当前 `app\src` 中只有下列三个文件的内容有变化。

| 文件 | 最终处理 |
| --- | --- |
| [SystemServerHook.java](D:/ButtonHyperAlive/HyperAlive-debug/app/src/main/java/com/qihang/aod/hook/SystemServerHook.java#L237) | 提前拦截主显示组 `PowerGroup.setWakefulnessLocked` 从交互态进入 DOZE 或 ASLEEP。系统自动超时可绕过原有 `goToSleep` 入口，新增前馈把 `mode=0` 提前送到壁纸引擎。保留已有的手动熄屏入口和指纹广播来源校验。 |
| [StateCoordinator.java](D:/ButtonHyperAlive/HyperAlive-debug/app/src/main/java/com/qihang/aod/hook/StateCoordinator.java#L309) | 两个入口共用 `onSleepIntent(String source)`，沿用原有协调逻辑，日志记录具体的前馈来源。 |
| [ViewTreeSweeper.java](D:/ButtonHyperAlive/HyperAlive-debug/app/src/main/java/com/qihang/aod/hook/ViewTreeSweeper.java#L199) | 恢复按原生 AOD 时钟实际绘制交接显示的逻辑。通过 `RootState.lockClockHidden` 与 `nativeKeyguard` 识别锁屏原生时钟重新显示的时机，仅对锁屏时分子树中的 `TimeView` 请求重绘。 |

`SystemUiHook.java` 恢复为自动息屏修改前备份中的版本。`AodHook.java` 和 `ClockSettings.java` 与该备份的版本一致。中间曾在 AOD 预绘制阶段提前切换时钟显示，随后出现锁屏时钟消失。该中间版本留在回退前备份里，当前源码已撤回这项切换。

## 实机验证与边界

- 最终包连续复测两轮 AOD、亮屏到锁屏、再回到 AOD，锁屏和 AOD 都显示了完整时分。查看 [最终两轮截图](D:/ButtonHyperAlive/HyperAlive-debug/shots/clock_restore/clock_cycles.png) 和 [对应日志](D:/ButtonHyperAlive/HyperAlive-debug/shots/clock_restore/clock_cycles.log)。同目录的 `restore_check.png` 和 `final_restore_check.png` 属于中间尝试。
- 最终包从桌面等待 15 秒触发自动息屏。系统在 21:45:59.904 报超时，PowerGroup 在 .910 发出前馈，桌面槽和锁屏槽分别在 .917 和 .919 启动 `tr=3`。两个槽各完成 81 帧，用时 654 ms 和 652 ms。见 [最终自动息屏日志](D:/ButtonHyperAlive/HyperAlive-debug/shots/clock_restore/final_timeout.log)。30 秒在保留同一 PowerGroup 修复的中间版本也测过，测试后已将设备恢复到 15 秒。
- 最终包的自动超时日志覆盖桌面 → AOD，时钟截图覆盖锁屏与 AOD 的可见性。若用户再报告锁屏等待超时后时钟移动中偶发重叠，应单独从已显示时钟的锁屏界面等待超时，用逐帧画面和日志确认具体发生在哪一帧。
- 魅族时钟两项开关的四种组合，以及省电停止自转后的实际指纹直达桌面扩散，在本轮较早版本已分别验证。指纹日志记录了 `mode=5` 和双壁纸槽完成 `tr=2`，但最后一次时钟回退后没有重跑这两组验证。此前过渡策略和动效回归脚本分别通过 39 和 814 项断言。见 [指纹复测日志](D:/ButtonHyperAlive/HyperAlive-debug/shots/four_bugs/fingerprint_final.log)。

## 备份与开发入口

| 路径 | 用途 |
| --- | --- |
| [backup_timeout_clock_20261109_212452](D:/ButtonHyperAlive/HyperAlive-debug/backup_timeout_clock_20261109_212452) | 自动息屏修改前的源码与 APK。时钟源码对应用户指定的基线。此备份的 APK 已含续接时加入的 SystemUI 广播发送方 UID 校验。 |
| [backup_before_clock_restore_20261109_213856](D:/ButtonHyperAlive/HyperAlive-debug/backup_before_clock_restore_20261109_213856) | 最后一次回退前的源码与 APK，用于对照中间尝试。 |
| [backup_four_bugs_20261108_230033](D:/ButtonHyperAlive/HyperAlive-debug/backup_four_bugs_20261108_230033) | 本轮最初处理四个问题前的源码、APK 与校验清单。 |

- 当前开发目录没有 Git 仓库。改源码前先备份相关文件和当前 APK，需要核对改动范围时可和备份按文件哈希比较。
- 进入 `D:\ButtonHyperAlive\HyperAlive-debug` 后，`python build.py` 只构建，`python deploy.py install` 会构建、安装、处理 LSPosed 作用域并软重启相关进程。安装后可核对设备 APK 与本地产物的 SHA-256。
- 调试机已通过 ADB 接入，设备序列号 `662b6636`。ADB 路径为 `C:\Users\xl768\AppData\Local\Android\Sdk\platform-tools\adb.exe`，主屏采集使用显示 ID `4630946457447247251`。设备用独立双壁纸槽，槽 1 为 `QihangHomeWallpaper`，槽 2 为 `QihangWallpaper`。只有明确需要改变槽位时才使用 `deploy.py setwall`，它会设成合并槽 `which=3`。
- 旧 Gemini 交接文档在 `C:\Users\xl768\.gemini\antigravity\brain\82857e28-4d0f-476c-b8f3-4539ae9c5ce2\HANDOVER_PROMPT.md`。逆向文件、开发经验和历史日志在 `D:\Antigravity work\alive移植`。稳定版仓库在 `D:\HyperAlive`，调试版本留在当前开发目录。

## 下一轮会话提示词

```text
请接手 HyperAlive 模块的后续调试。先读 D:\ButtonHyperAlive\HYPERALIVE_HANDOVER_20261109.md，再根据我接下来的具体反馈检查 D:\ButtonHyperAlive\HyperAlive-debug 的当前源码和设备。旧 Gemini 文档和逆向资料的位置见交接文件。

当前基线是用户在说「之前由于额度耗尽，任务中断，继续当前任务」之前满意的锁屏与 AOD 时钟交接，加上原生锁屏时分恢复显示时的定向重绘，以及 PowerGroup 自动超时熄屏前馈。请保留这三项行为，同时保留魅族时钟的独立开关和启航样式省电停转后指纹直达桌面的扩散修复。最后验证过的 APK SHA-256 见交接文件，先以实际源码和安装版本为准。

修改前备份相关文件与 APK。设备可通过 ADB 调试，维持现有双壁纸槽和 15 秒超时配置。若要验证锁屏自动息屏的动画，先亮屏停在能看到时钟的锁屏界面，再等待系统自然超时。分别观察时钟是否消失或重叠、壁纸回缩是否完整，并记录逐帧画面和 PowerGroup、tr=3 日志。若验证指纹扩散，等 AOD 因省电停止自转后，用实际指纹直接解锁到桌面并确认过渡完成。请在完成后列明实际复测的场景和结果，调试包留在开发目录。
```
