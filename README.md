# 物理阻断条（MyPhysicalDeadZone）

一个需要 **Root 权限** 的 Android 触摸阻断工具。

本项目通过 Linux 输入系统的 `EVIOCGRAB` 和 `/dev/uinput` 接管并转发真实触摸事件，在屏幕上模拟一块无法触摸的“物理阻断条”：手指滑入阻断区域时，Android 会收到一次抬起；手指离开阻断区域后，会在当前位置重新建立一次新的触摸。

> 本项目会独占真实触摸设备。使用前请完整阅读下方的安全警告，并建议在首次测试时保持 ADB 连接。

## 运行要求

### 必须具备

- 已 Root 的 Android 设备；
- Root 管理器允许本应用执行 `su` 命令；
- Android 8.0（API 26）或更高版本；
- `arm64-v8a` 设备；
- 内核提供可用的 `/dev/uinput`；
- 系统允许应用读取真实触摸设备 `/dev/input/eventX`；
- 授予应用“在其他应用上层显示”权限。

### 当前 Android 配置

| 配置 | 当前值 |
|---|---:|
| 最低 Android 版本 | Android 8.0 / API 26 |
| 编译 SDK | API 33 |
| 目标 SDK | API 33 |
| 当前支持 ABI | arm64-v8a |

不同厂商的 Android 系统、Root 方案和触摸驱动实现可能不同。即使 Android 版本符合要求，也不能保证所有设备都能正常使用。

## 功能

- 检测 Root 权限；
- 自动识别多点触摸设备；
- 安装、启动、停止和卸载 Root 后端；
- 显示可移动的悬浮阻断区域；
- 调整中心位置、宽度、高度、旋转角度和透明度；
- 支持矩形和自定义四边形；
- 支持拖动四个角点编辑形状；
- 支持配置预设；
- 支持多点触摸 slot 独立过滤；
- 手指进入阻断区时结束当前虚拟触摸；
- 手指离开阻断区时创建新的虚拟触摸；
- 检测快速移动路径是否穿过阻断区域；
- 桌面与全屏窗口切换时重新同步阻断位置；
- 提供后端状态、控制状态和调试日志页面。

## 阻断原理

### Android 原始触摸链路

正常情况下，Android 直接读取真实触摸设备：

```text
真实触摸屏
    ↓
/dev/input/eventX
    ↓
Android InputReader
    ↓
应用或游戏
```

仅仅监听触摸事件无法实现真正的阻断，因为 Android 仍然会收到真实设备的原始触摸。

### 启用物理阻断后的链路

后端首先创建一个虚拟触摸屏，然后通过 `EVIOCGRAB` 独占真实触摸设备：

```text
真实触摸屏
    ↓
/dev/input/eventX
    ↓
deadzone_daemon
    ├─ 阻断区内：过滤触摸
    └─ 阻断区外：转发触摸
            ↓
        /dev/uinput
            ↓
    Android InputReader
            ↓
        应用或游戏
```

启用 `EVIOCGRAB` 后，Android 不再直接接收真实触摸屏的事件，而是接收后端通过 `/dev/uinput` 发送的虚拟触摸事件。

因此，后端必须正确转发所有未被阻断的触摸。如果后端异常、虚拟设备写入失败或多指状态错误，就可能造成触摸中断。

## “物理阻断条”的触摸语义

假设手指从阻断条左侧滑向右侧：

```text
正常区域        阻断区域        正常区域
──────────────██████████──────────────
手指路径  → → → → → → → → → → → →
```

游戏或应用看到的触摸过程是：

```text
阻断区外：DOWN / MOVE
进入阻断区：UP
阻断区内部：没有虚拟触摸
离开阻断区：新的 DOWN
离开后：新的 MOVE
```

也就是说，一根连续的真实手指可能被转换成两段独立的虚拟触摸：

```text
真实手指：DOWN ───────────────────────────── UP

虚拟触摸：DOWN ─── UP      DOWN ─────────── UP
                    阻断区
```

如果手指直接按在阻断区域内，后端不会向 Android 发送该手指的虚拟按下事件。

## 多指处理

后端根据 Linux 多点触摸协议中的 `ABS_MT_SLOT` 和 `ABS_MT_TRACKING_ID` 区分不同手指。

目标行为是每根手指独立处理：

```text
手指 A 位于阻断区：虚拟设备看不到 A
手指 B 位于正常区域：B 的 DOWN / MOVE / UP 正常转发
```

一根手指进入阻断区域时，不应该改变其他未阻断手指的 tracking ID 和触摸生命周期。

不同游戏对多指 `pointer-up` 的处理方式可能不同。即使后端只抬起进入阻断区的手指，部分游戏仍可能重置自己的组合操作状态。

## 坐标与形状

Android 悬浮窗将阻断区域转换为四个绝对屏幕坐标点，并写入应用私有目录中的配置文件。

C++ 后端将触摸设备原始坐标映射到当前物理屏幕坐标，然后进行：

- 点是否位于四边形内部的判断；
- 点是否位于边界上的判断；
- 相邻两帧之间的移动线段是否穿过阻断区的判断。

矩形同样会转换为四个顶点，因此矩形和自定义四边形使用相同的主要几何边界。

请避免创建以下异常形状：

- 四条边互相交叉的“蝴蝶结”形状；
- 多个顶点重合；
- 面积接近零；
- 顶点被拖动到远离屏幕的位置。

## 使用方法

1. 安装 APK；
2. 启动应用并授予悬浮窗权限；
3. 点击“检测 Root”，允许 Root 授权；
4. 点击“自动检测”，识别真实多点触摸设备；
5. 点击“安装后端”；
6. 显示并调整阻断条的位置和形状；
7. 完成调整后关闭编辑模式，使悬浮窗触摸穿透；
8. 点击“启动后端”；
9. 确认状态为 `RUNNING_ENABLED` 后再进入需要使用的应用或游戏。

应用内还提供：

- 停止后端；
- 卸载后端；
- PING 测试；
- 后端日志；
- 实际生效配置；
- 配置预设。

## 安全警告与紧急恢复

`EVIOCGRAB` 会让后端独占真实触摸设备。如果后端不能正确向 `/dev/uinput` 写入事件，可能出现暂时性的全屏触摸失效。

首次测试建议：

- 保持 USB 调试和 ADB 连接；
- 不要在无法使用 ADB 的重要设备上直接测试；
- 先测试单指，再测试多指；
- 启用阻断前抬起所有手指；
- 避免在未验证前长时间常驻运行。

紧急停止后端：

```bash
adb shell su -c 'pkill deadzone_daemon'
```

删除已经安装到 Root 临时目录的后端：

```bash
adb shell su -c 'rm -f /data/local/tmp/deadzone_daemon'
```

进程退出或输入设备文件描述符关闭后，Linux 内核通常会自动释放 `EVIOCGRAB`，真实触摸设备随后恢复由 Android 直接读取。

## 构建项目

### 环境

- Android Studio；
- JDK 17；
- Android SDK 33；
- Android NDK；
- Gradle Wrapper 7.5。

项目会从本机 Android SDK 中寻找已安装的 NDK，并使用 ARM64 Clang 编译：

```text
app/src/main/cpp/deadzone_daemon.cpp
```

生成的后端会自动复制到：

```text
app/src/main/assets/backend/arm64-v8a/deadzone_daemon
```

### Windows 构建

```powershell
.\gradlew.bat assembleDebug
```

输出 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```text
app/src/main/java/com/example/myphysicaldeadzone/
├─ MainActivity.kt          # 主界面、配置、预设和后端操作
├─ DeadZoneService.kt       # 前台悬浮窗服务和绝对坐标同步
├─ DeadZoneView.kt          # 阻断区域绘制
├─ QuadEditorView.kt        # 四边形角点编辑器
└─ BackendController.kt     # Root命令、后端安装和文件控制协议

app/src/main/cpp/
└─ deadzone_daemon.cpp      # input/uinput触摸过滤后端
```

## 后端文件

运行时主要使用：

```text
/data/local/tmp/deadzone_daemon
/data/local/tmp/deadzone_daemon.log
/data/local/tmp/deadzone_launch.log
/data/local/tmp/deadzone_state.json
```

应用私有目录中保存：

```text
deadzone_config.json
deadzone_command.json
```

## 当前限制

- 仅构建 `arm64-v8a` 后端；
- 必须具备 Root 权限；
- 必须支持 `/dev/uinput`；
- 不同设备可能需要不同的坐标方向或轴校准；
- 自相交四边形的行为不保证符合视觉直觉；
- 默认矩形的圆角主要是显示效果，后端几何边界仍由四个顶点决定；
- Android 和游戏对虚拟多点触摸的兼容性存在差异；
- 当前项目仍需要更多设备上的长时间、多指和高采样率测试。

## 问题反馈

提交问题时建议提供：

- 手机或平板型号；
- Android 版本；
- Root 方案；
- 屏幕方向和分辨率；
- 触摸设备路径；
- 单指还是多指问题；
- 是否进入或穿过阻断区域；
- 后端状态和必要的日志。

请勿公开提交包含个人信息、完整系统日志、私钥或签名文件的内容。

## 免责声明

本项目会使用 Root 权限并操作底层输入设备，仅供研究、测试和个人使用。使用者应自行承担因设备兼容性、触摸失效、应用异常或其他问题造成的风险。
