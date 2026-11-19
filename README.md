# 媒体播放开关

Android 7.0 及以上（minSdk 24），Java 原生应用，无第三方运行时依赖。
安装包：dist/MediaToggle.apk（1.1）。

## 使用

1. 安装 dist/MediaToggle.apk，启动“媒体播放开关”。未授权且没有媒体正在播放时，只提示“当前无播放器”，不打开授权页面。检测到正在播放的媒体且尚未授权时，才引导打开系统“通知使用权”设置，手动允许“媒体播放开关（识别播放器）”。
2. 授权后再次启动应用，即执行一次切换。授权流程本身不切换播放状态。
3. 正在播放时暂停，Toast：“已暂停媒体播放”；有暂停的播放器时恢复，Toast：“已恢复媒体播放”；没有可控制的播放器时不发送指令，Toast：“当前无播放器”。

日常触发没有应用页面、弹窗、悬浮窗和常驻通知，完成后立即退出透明入口 Activity，不出现在最近任务中。系统负责保持通知监听服务连接。
通知使用权用于系统媒体会话和收音机通知按钮识别，不保存通知内容，不访问网络，不需要 Root、辅助功能或修改收音机 APK。

未授权时，系统只允许通过 AudioManager.isMusicActive 判断是否存在正在播放的媒体，无法枚举已暂停的播放器。因此首次授权可以先播放媒体再触发应用，或者手动进入系统通知使用权设置授权；授权后才支持准确识别并恢复暂停的播放器。

## 控制顺序

- 通过 Android MediaSessionManager 获取系统当前媒体会话，优先暂停正在播放、缓冲或连接的会话。标准播放器使用系统 MediaController.TransportControls 的 play/pause；仅声明切换动作的播放器使用该会话的媒体键。
- 系统没有正在播放的会话时，检查 cradio 的播放通知；如果收音机正在播放，优先暂停它，避免恢复另一个已暂停的播放器。
- 没有任何正在播放的播放器时，按系统会话优先级恢复一个暂停的播放器；没有对应会话时恢复暂停的 cradio。
- 不恢复 STATE_NONE、STATE_STOPPED、STATE_ERROR，也不根据“安装过播放器”或历史记录启动播放器。无播放器时不广播全局播放键，避免意外唤醒历史播放器。
- 每次只控制一个播放器，快速重复触发时合并正在处理的请求。发送指令后最多等待四秒核对状态，确认后才显示成功提示；播放器不响应则提示“播放器未响应播放/暂停指令”。
- 已授权时，系统通知监听连接最多等待 1.8 秒，连接失败时给出相应提示，不误报“当前无播放器”。未授权时按上述无播放分支处理。

## cradio 4.9 兼容

分析对象为项目中提供的 cradio.apk，包名 com.gongjin.cradio，versionCode 49。
SHA-256：5131D93A2A89229A578E8171B938DFD4A46512D3C052948473CC970702309A53。

该版本使用自定义 RemoteViews 播放通知，PlayerService 不导出，未发现系统 MediaSession 播放接口。通知的 btnPlay_Notification 按钮通过收音机自身创建的 PendingIntent 发送 com.gongjin.cradio.notification，BUTTON_TAG=2；其接收器按当前状态执行停止流或重新播放当前电台。

兼容逻辑读取当前通知，将 RemoteViews 在内存中还原（不显示），用收音机自身的 ic_pause_notification / ic_play_notification 图标及大通知对应图标识别播放状态，再调用原按钮的 PendingIntent。支持新版 Android 上不导出的接收器，不通过启动私有 Service 绕过权限，不发送未经授权的私有广播。

这里只恢复同一电台的直播流，不能从暂停时刻续播。收音机必须已运行且保留播放通知；退出服务、清除通知或禁用收音机通知后不强行启动。其他版本如果修改了通知布局或资源名称，需要重新适配。

## 构建

JDK 17 或 21，Android SDK Platform 35、Build Tools 35.0.0。

~~~powershell
.\tools\build-apk.ps1
~~~

脚本默认复用相邻 HotspotApp/.tools/android-sdk，可传入 -SdkPath / -JdkPath；不修改 HotspotApp 工程。中文路径通过临时盘符映射构建，退出时自动释放。使用本工程 .tools/media-debug.keystore 签名，后续覆盖安装应保留该密钥。源工程也提供 Gradle wrapper，修改 local.properties 后可使用 .\gradlew.bat assembleDebug。

## 验证范围

已编译 Java、打包 DEX/APK，通过 apksigner 签名验证及 zipalign 对齐校验；Gradle :app:lintDebug 通过（0 errors，3 warnings：外部应用资源名称查找和备份规则提示）。已静态核对 cradio 4.9 的通知图标、按钮资源、PendingIntent 和接收器控制分支。
当前没有连接 Android 设备，尚未完成真机播放/暂停验证。建议在目标设备分别检查：普通播放器播放/暂停、cradio 播放/暂停、无播放器、多个播放器并存、连续触发、撤销授权。每次操作应只影响一个播放器，且 Toast 与实际状态一致。
