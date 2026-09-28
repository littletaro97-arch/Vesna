# Vesna, keep flying

一个 Android 游戏辅助小工具：**在游戏画面上常驻一个悬浮按钮，点一下把当前游戏切到后台**，直接回到最近任务界面。

当前版本：**v1.5.4**（versionCode 10）

## 它怎么做到的（以及为什么不需要 root / Shizuku）

核心只有一次调用：

```kotlin
performGlobalAction(GLOBAL_ACTION_RECENTS)
```

`GLOBAL_ACTION_RECENTS` 是 Android 从 API 16 起就公开的无障碍全局动作。
请求发到 system_server 后由 `SystemActionPerformer.performSystemAction()` 处理：

```java
case AccessibilityService.GLOBAL_ACTION_RECENTS:
    return openRecents();          // -> StatusBarManagerInternal.toggleRecentApps()

// 同族动作，可作对照：
private void expandQuickSettings() {
    final long token = Binder.clearCallingIdentity();
    StatusBarManager statusBarManager = (StatusBarManager) mContext.getSystemService(
            android.app.Service.STATUS_BAR_SERVICE);
    statusBarManager.expandSettingsPanel();
    Binder.restoreCallingIdentity(token);
}
```

关键在于 `Binder.clearCallingIdentity()` —— 系统把调用者身份清成了自己。
所以普通应用不需要 `STATUS_BAR` 这类签名级权限，也就**不需要 root、不需要 Shizuku、不需要模拟触摸手势**。

代价是：必须开启无障碍服务。

## 这个无障碍服务做了什么、没做什么

- 只调用 `GLOBAL_ACTION_RECENTS` 这一个系统动作。
- `canRetrieveWindowContent="false"` —— 读不到任何界面内容。
- `canPerformGestures="false"` —— 无法模拟任何点击或滑动。
- `onAccessibilityEvent()` 是空实现 —— 系统可能回调窗口状态事件，但 Vesna 不读取事件内容、不缓存或转发事件。
- 不申请读取窗口内容或模拟手势的能力。

### 屏幕录制（仅限用户主动开启的实验功能）

「体力耗尽自动切后台」是需要 MediaProjection 屏幕捕获授权的实验功能。Vesna 每 0.5 秒在角色附近的小区域识别黄色/红色体力条；红色像素占可识别体力条颜色像素的比例达到阈值，并连续确认后，才请求无障碍服务切入最近任务界面。每段低体力状态只触发一次；连续识别到黄色体力条后重新待命。若当前游戏特调同时开启自动返回，体力触发会像手动按钮一样显示倒计时并返回该游戏。帧只在设备内存中处理，不写入文件，也不会上传。建议只在原神画面中开启，并在「只在指定应用里显示」中限定目标应用；识别结果可能误报、漏报或增加耗电。

应用没有分析或广告 SDK。更新功能会访问公开的 GitHub Release API，并在用户确认后下载 APK。主页反馈入口指向项目维护者的小红书与 bilibili 个人主页；点击后由 Android 交给关联应用或浏览器处理，Vesna 不读取平台账号信息。运行记录最多保留 300 条在本机；自动返回相关记录可能包含当前应用的包名。Android 云备份与设备迁移均被关闭。用户主动复制诊断信息时，其中还会包含设备品牌、型号和 Android 版本，分享前可先检查内容。

## 「从最近任务隐藏」的开关怎么做出来的（一个 API 不存在的坑）

**`Activity.setExcludeFromRecents()` 这个方法不存在。** 它是这轮踩到的坑，值得单独记一笔。

`android:excludeFromRecents` 只是 manifest 里的**静态属性**，Activity 起来后就改不了；Android 也**没有**提供在 `Activity` 上改这个值的公开 API。编写时的第一版代码直接写了 `setExcludeFromRecents(hide)`，编译器报 `Unresolved reference`。

把 `android.jar` 里的 `Activity.class` 翻出来按符号搜索，含 `Exclude` / `Recents` 的公开符号只有 `setRecentsScreenshotEnabled` —— 完全无关。

真正可用的运行时入口在 **`ActivityManager.AppTask`** 上：

```kotlin
val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
manager.appTasks                                  // 只看得到自己应用的任务，无需权限
    .firstOrNull { it.taskInfo?.baseIntent?.component?.packageName == packageName }
    ?.setExcludeFromRecents(hide)                 // 公开 API，(Z)V
```

签名是 `setExcludeFromRecents(boolean)`，Android 5.0（API 21）起就有。

几个要点：

- 它改的是**任务（task）**的可见性，不是单个 Activity。`MainActivity` 和 `SettingsActivity` 同属一个任务，所以一个开关两个都跟着变 —— 这正是想要的（隐藏了主页却留着设置页等于没隐藏）。
- `getAppTasks()` 只能列出**自己应用**的任务，所以不需要 `GET_TASKS` 之类的权限。
- 调用时机：`onCreate` 里尽早调。任务刚建立时设置最可靠，晚了用户可能已经在最近任务里看见它了。找不到任务时静默返回 `false`，manifest 里的默认值仍在生效，不会出现「两个都没设上」的真空状态。
- 实现集中在 `core/RecentsVisibility.kt`，主页与设置页都调它。

## 权限清单

| 权限 | 用途 | 必需性 |
|---|---|---|
| 悬浮窗 `SYSTEM_ALERT_WINDOW` | 把按钮画在游戏上层 | **必需** |
| 无障碍服务 | 真正执行「切到后台」 | **必需** |
| 通知 `POST_NOTIFICATIONS` | 前台服务常驻通知 | 可选（Android 13+ 才需要） |
| 电池优化白名单 | 降低服务被系统回收的概率 | 可选 |
| 使用情况访问 `PACKAGE_USAGE_STATS` | 按前台应用控制显示范围、定位自动返回的目标应用 | 可选 |
| 屏幕录制（MediaProjection） | 实验性体力条识别；仅在用户主动开启并确认系统授权后使用 | 可选 |

## 功能

- **悬浮按钮**：圆形深色底盘 + 卡片堆叠图标。短按切后台，长按拖动改位置。
- **主页快捷入口**：「游戏特调」卡片可进入原神特调页，或尝试启动已安装的原神客户端；在特调页切换当前生效方案，同一时间只会有一款游戏生效。每款游戏的特调参数按应用包名分别保存，切换生效游戏不会覆盖其它方案；入口绿色表示当前方案生效，灰色表示未生效。主页运行状态分别显示普通运行、游戏特调运行、游戏特调实验室功能运行。
- 位置按屏幕宽高**比例**保存，旋转屏幕或换分辨率后仍落在视觉上相同的位置。
- 大小 40–88 dp、不透明度 30–100% 可调，改动即时生效。
- **「只在指定应用里显示」模式**：用 `UsageStatsManager` 判断前台应用，平时完全不打扰。
- 设置页复选框在浅色模式下使用灰色勾选框，深色模式下使用白色勾选框，未勾选状态也能辨认。
- **游戏优化（实验性）**：各游戏方案可分别设置切后台后 2–15 秒自动返回；体力条识别默认关闭，开启时需系统授权。实验室触发会沿用当前游戏方案的自动返回设置。
- **从最近任务列表隐藏（可在设置页随时开关）**：默认隐藏 —— Vesna 不出现在最近任务里，一键清理后台时不会被顺手清掉，常驻服务因此不那么容易被误杀。代价是不能从最近任务切回 Vesna，回本页走通知栏或桌面图标。想让它出现在最近任务里，到设置页关掉「从最近任务隐藏」即可，**立刻生效，不需要重装**。
- **图标随系统深浅色切换**：自适应图标（adaptive icon），底色在浅色模式是白、深色模式是近黑 `#16181D`，由 `drawable-night/` 自动生效，不需要代码判断。图标图形整体落在 66×66 安全区内，圆形与圆角遮罩均不切角。
- 深浅色跟随系统。
- **应用内自动更新**：GitHub Latest Release → DownloadManager 后台下载 → SHA-256 / 包名 / versionCode / 签名四重校验 → 交给系统安装器。

## 目录名的坑：**不要用 `!`**

**这条是实测结论，不是猜测。** 本工程原来叫 `Vesna, keep flying!`，那个感叹号会让 Kotlin 编译器直接崩溃：

```
e: Internal compiler error
    at org.jetbrains.kotlin.cli.jvm.compiler.KotlinCliJavaFileManagerImpl.findClass
```

原因：`!` 在 Java 的 URL / jar 语义里是「嵌套归档分隔符」，编译器处理 classpath 时会把它当成归档边界。

对照实验（都是实测）：

| 路径 | 结果 |
|---|---|
| `E:\课外项目\Vesna, keep flying!` | ❌ Internal compiler error |
| `E:\课外项目\Vesna Test`（中文 + 空格，无感叹号） | ✅ BUILD SUCCESSFUL |
| `E:\课外项目\Vesna, keep flying`（去掉感叹号，当前路径） | ✅ BUILD SUCCESSFUL |

**结论：中文和空格都没问题，只有 `!` 有问题。**（`android.overridePathCheck=true` 只能放行 AGP 的路径检查，管不了 Kotlin 编译器。）

所以目录名里别再出现 `!` 了。万一有别的项目踩到，临时解法是 `subst` 一个 ASCII 盘符进去构建：

```powershell
subst V: "E:\某个含感叹号的目录!"
cd V:\
.\gradlew.bat --no-daemon lintDebug assembleDebug
```

注意 subst 是**会话级**的，重启电脑后失效；盘符要固定用同一个（Gradle 缓存记录绝对路径，换盘符会降低增量构建命中率）。

## 构建

要求：JDK 17 或更高（本机 JDK 21 实测通过）、Android SDK（compileSdk 35）。

工程版本：Gradle wrapper `8.7` / AGP `8.5.2` / Kotlin `1.9.24`；minSdk 26、targetSdk 35。

```powershell
.\gradlew.bat --no-daemon lintDebug testDebugUnitTest assembleDebug
```

Debug APK 位于 `app\build\outputs\apk\debug\app-debug.apk`。

Windows 上构建已签名 Release APK：

首次初始化签名材料需要 OpenSSL CLI 可从 `PATH` 调用；后续构建直接复用本机保存的签名密钥。

```powershell
# 首次运行：创建长期使用的签名密钥，并加密保存口令；随后完成构建
.\tools\build-release.ps1 -InitializeSigning

# 后续版本：版本号和 versionCode 必须与 app/build.gradle.kts 一致
.\tools\build-release.ps1 -VersionName 1.5.4 -VersionCode 10
```

首发签名文件保存在 `%APPDATA%\Vesna\vesna-release.p12`，口令由当前 Windows 用户的 DPAPI 加密保存在同目录。签名文件和口令是未来 APK 原位升级所必需的，必须妥善备份；不要将它们提交到 GitHub。需要备份口令时，可在原 Windows 账户下运行 `.\tools\build-release.ps1 -ShowSigningPassword`，并把口令存入自己的密码管理器。Release APK 输出到 `dist\vesna-v<版本号>-release.apk`。

## 模块结构

```text
app/src/main/java/com/littletaro/vesna/
├── MainActivity.kt              主页：一个开关 + 两项必需权限 + 设置入口
├── SettingsActivity.kt          设置：运行条件 / 按钮外观 / 显示范围 / 更新 / 运行记录
├── GameOptimizerActivity.kt     游戏特调：按游戏保存自动返回与实验性体力条设置
├── AppPickerActivity.kt         挑选「只在哪些应用里显示」
├── a11y/
│   └── BackgroundAccessibilityService.kt   核心：GLOBAL_ACTION_RECENTS
├── overlay/
│   ├── OverlayService.kt        前台服务 + WindowManager 悬浮按钮 + 前台应用轮询 + 存活状态回报
│   └── ToggleButtonView.kt      自绘圆形按钮（短按触发 / 长按拖动）
├── core/
│   ├── RuntimeProtection.kt     权限体检 / 系统设置跳转 / 诊断文本导出
│   ├── OverlayPrefs.kt          按钮配置（位置按屏幕比例存）
│   ├── GameSpecialPrefs.kt      按包名保存特调方案，并保证同时只有一个生效游戏
│   ├── RecentsVisibility.kt     控制本应用任务在最近任务里的可见性（AppTask 路线）
│   ├── ForegroundAppTracker.kt  前台应用识别（UsageStatsManager）
│   ├── OperationLog.kt          环形缓冲运行日志（最多 300 条）
│   └── ThemeColors.kt           深浅色配色
├── update/
│   ├── UpdatePolicy.kt          版本比较 / 资产选择 / SHA-256 规则（纯逻辑，可单测）
│   └── UpdateController.kt      检查 / 下载 / 四重校验 / 安装交接
└── ui/UiKit.kt                  三个页面共用的控件工厂（含「重建页面保留滚动位置」）
```

界面全部用代码构建（无 XML 布局）：元素少、结构简单，代码构建能让深浅色与状态刷新逻辑集中在一处。页面状态刷新一律走「整体重建 + 还原滚动位置」，见 `UiKit.replaceContentPreservingScroll()`。

## 自动更新的资产命名规则

发布到 GitHub Release 时，APK 资产必须按这个规则命名，否则应用会拒绝下载。公开仓库为 `littletaro97-arch/Vesna`，与 `UpdatePolicy.REPOSITORY` 一致：

```
vesna-v<版本号>-debug.apk
vesna-v<版本号>-release.apk
```

并且 Release 的 `digest` 字段必须是 `sha256:<64 位十六进制>`（GitHub 会自动提供）。
`UpdatePolicy.selectApk()` 会同时校验文件名、下载地址前缀和 SHA-256 格式，三者缺一即拒绝。

若要更改仓库名，只改 `update/UpdatePolicy.kt` 的 `REPOSITORY` 常量，并重新构建 APK。

## 已知限制

- **必须开启无障碍服务**，这是系统层面唯一的实现路径。
- 部分厂商（OPLUS 系）会周期性回收无障碍服务，表现为「点按钮没反应」。此时到系统设置里把 Vesna 的无障碍关掉再打开一次即可；主页检测到这种情况会给出提示。
- **默认不出现在最近任务列表里**（可在设置页随时关掉）。默认隐藏是为了降低被误杀概率；关掉后就能从最近任务切回主界面。
- **应用名就是 `Vesna, keep flying`**（含逗号），启动器标签与设置页标题一致。
- `GLOBAL_ACTION_RECENTS` 在系统侧是 `toggleRecentApps` 语义：如果当前已经在最近任务界面，再点一次会退回上一个应用。
- **部分带反作弊的手游可能检测无障碍服务或悬浮窗**，使用前请自行评估风险。
- 悬浮按钮在少数游戏的独占全屏画面（SurfaceView 独占 / 安全画面）上可能不显示，这是系统合成层限制。
- 体力识别默认关闭；当前只按用户提供的原神画面调整角色附近黄/红体力条识别，尚未完成真机验证，只适合作为实验功能。
- Release 与此前的 Debug APK 使用不同签名；已安装 Debug 版本的用户需要先卸载再安装首个 Release，应用内数据不会保留。
- 本项目目前只准备 GitHub APK 侧载分发，没有准备 Google Play 提交；截至 2026-09，Play 新应用和更新要求 target API 36 或更高。
- Google 的开发者身份验证计划说明：2026-09-30 首阶段不拦截 GitHub 直接侧载，但计划在 2027 年扩展到认证设备上的应用；届时需提前注册包名并证明签名密钥归属。

## 版本历史

- **未发布跟进（v1.5.4 之后）**：加深浅色模式复选框的可见度；更新小红书主页链接；改进实验室触发与返回倒计时；增加按游戏保存且互斥生效的特调方案和主页运行状态。该跟进尚未构建 Release。
- **v1.5.4**（2026-09-26，versionCode 10）：主页新增原神快捷启动和小红书、bilibili 反馈卡片；修正反馈入口在社交 App 已安装时只打开 App 首页的问题，现在通过 Android 链接处理打开相应个人主页。游戏特调入口并入主页。
- **v1.5.2**（2026-09-26，versionCode 8）：首个公开 Release。修复 Android 14+ 未启用屏幕录制时仍申请 MediaProjection 前台服务类型的问题；把 MediaProjection 初始化移到前台服务启动之后；首帧暂不可用时继续体力识别轮询；关闭 Android 云备份与设备迁移的数据提取。更新项目说明与隐私描述。
- **v1.4.0–v1.5.1**：工作区中留有这些版本的 Debug APK，但没有 Git 历史或逐版变更记录，因此不推测各版差异。

- **v1.3.0**（2026-09-25）：
  - **修复图标几何**：上一版图标被整体放大过头，三层线框几乎填满图标并被圆形遮罩切掉。现在整组图形缩放至 66×66 安全区内居中，圆形与圆角遮罩实测均无切角。
  - **修复线框裁切**：线框是「有洞的环形」，靠绘制顺序盖不住下层落在洞里的部分，之前会露出多余线头。现在每层用「本层线框 − 上层所有线框」的差集路径，只画真正可见的线段。
  - **「从最近任务隐藏」改成设置页的一个开关**，可自由选择显示或隐藏，切换立刻生效（详见上文 AppTask 那节）。
  - 应用名改为 **`Vesna, keep flying`**。
- **v1.2.0**（2026-09-25）：
  - **换图标**：按设计稿重建矢量自适应图标（三张层叠线框卡片：浅蓝 `#8CBDFB` → 纯蓝 `#0069FC` → 青绿 `#01CABF`），几何按设计稿逐像素还原（卡片 76×58 / 圆角 12 / 描边 5、逐层偏移 10.5×8.5）。
  - **图标随系统深浅色切换**：新增 `res/drawable-night/ic_launcher_background.xml`（底色换成 `#16181D`）。这是系统原生机制，不需要代码判断。
  - 通知栏小图标同步改成同一套三卡片线框（24dp 画布重排，非等比缩放）。
  - 设置页新增「从最近任务隐藏」的自检入口：「打开最近任务验证」按钮 + 实时列出最近任务里当前有哪些应用（若 Vesna 出现在里面会直接标出来）。
- **v1.1.0**（2026-09-25）：
  - 新增「从最近任务列表隐藏」（`android:excludeFromRecents`），降低一键清理后台时被连服务一起清掉的概率。
  - 修复设置页整体重建导致滚动位置弹回顶部：点「立即检查更新」、切换开关、从系统设置页返回都会跳回顶部。现在重建后会自动还原位置。
  - 更新状态文案从整页最底部挪到「应用更新」卡片内，点完就能看到结果。
  - 修复偶发的「点启动没反应」：启动是异步的，界面此前在服务创建完成前就去读存活状态，只会得到「未开启」，要切走再回来才刷新。现在服务真正挂上按钮后会主动回报，点完即变。
- **v1.0.0**（2026-09-25）：首个可用版本。悬浮按钮 + 一键切后台 + 权限引导 + 设置页 + 自动更新。lint 0 error，assembleDebug 通过。
