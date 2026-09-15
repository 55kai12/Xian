# 隐私政策 · 贤（Xian）

**生效日期**：2026 年 9 月 15 日 ｜ **适用版本**：v2.0.65 及之后

---

## 一句话

**贤不联网，也不收集你的任何数据。** 任务、日记、专注记录全部存在你自己手机里 —— 没有服务器，也没有上传通道。

---

## 一、不收集什么

- 不收集任何个人信息（姓名、手机号、邮箱、设备标识、位置……）
- 不收集任何使用行为（埋点、崩溃上报、时长统计）
- 不接入广告 SDK、统计 SDK、推送 SDK
- 没有账号系统，不需要注册或登录

### 技术佐证

Release 版 APK 的 AndroidManifest 中**未声明 `INTERNET` 权限**。在 Android 上，没有这条权限的应用无法建立任何网络连接 —— 所以这不是"我们承诺不上传"，而是**系统层面就传不出去**。

你可以自己验证：用 `aapt2 dump badging Xian-v2.0.65.apk` 或任意 APK 分析工具查看权限列表。

---

## 二、数据存在哪里

全部写在应用自己的私有目录里，不对外可见：

| 数据 | 存放方式 |
| --- | --- |
| 任务、子任务、倒数日、专注记录 | 本地 Room 数据库（`AppDatabase`） |
| 设置项（主题、白名单、锁机时段、退出额度……） | `SharedPreferences` |
| 日记、便贴内容、自定义壁纸 | `SharedPreferences` |

应用已在 Manifest 中关闭 `allowBackup` —— 这些数据不会被系统自动备份到云端，也不会随"换机迁移"被带走。**卸载应用即彻底删除上述全部数据。**

---

## 三、权限逐条说明

### 必需权限

| 权限 | 用途 |
| --- | --- |
| `FOREGROUND_SERVICE`<br>`FOREGROUND_SERVICE_SPECIAL_USE` | 前台计时与锁机服务，让计时在切到后台后继续走 |
| `POST_NOTIFICATIONS` | 通知栏显示计时状态与完成提醒 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗，用于绘制锁机时的全屏遮罩 |
| `RECEIVE_BOOT_COMPLETED` | 开机后恢复锁机时间段与到期提醒 |
| `KILL_BACKGROUND_PROCESSES` | 「清空后台应用」按钮；只清缓存进程，且跳过贤自己与系统应用 |

### 可选权限（不授予也能用，只是功能受限）

#### 1. 无障碍服务（`AccessibilityService`）

**用途**：识别当前在哪个应用。锁机白名单与应用限额依赖它。

无障碍框架在 Android 里属于高敏感权限 —— 系统允许它读取屏幕内容。所以这里把边界讲清楚：

- **只监听一种事件**：`TYPE_WINDOW_STATE_CHANGED`（窗口切换）
- **只取一个字段**：当前窗口所属应用的**包名**（如 `com.tencent.mm`）
- **不读取**屏幕上的文字、**不记录**你输入的内容、**不截图**、不保存任何界面信息
- 拿到的包名只用于本机判断"要不要盖上锁机层"，用完即弃 —— 不落盘、不出机

配置见 `app/src/main/res/xml/accessibility_service.xml`，事件处理见 `app/src/main/java/com/xian/focus/FocusLockAccessibilityService.kt`。

> **技术细节**：配置中声明了 `canRetrieveWindowContent` 与 `flagRetrieveInteractiveWindows`。
> 原因是部分 ROM 在无障碍服务被重启后不再补发窗口事件，需要主动"问一句"当前前台是谁。
> 它们只是一个查询窗口列表的能力开关 —— 代码里**没有任何解析窗口内容树的调用**。

#### 2. 使用情况访问（`PACKAGE_USAGE_STATS`）

**用途**：备用的前台应用感知源。当无障碍服务未授予或被系统回收时，用系统使用记录兜底判断前台应用。

同样只取「应用包名 + 时间戳」用于本机判断。

---

## 四、数据导出

「我的 → 导出数据」由你主动点击触发，导出的 CSV 写到**应用专属外部存储**：

```
Android/data/com.xian.focus/files/xian_data_<时间戳>.csv
```

导出后文件如何处置（拷贝、分享、删除）完全由你决定 —— **应用不做任何自动上传**。

---

## 五、儿童

本应用不面向 13 岁以下儿童，也不收集年龄信息 —— 因为不收集任何信息。

---

## 六、政策变更

本政策若有修改，会随版本提交到本仓库。你可以通过
[PRIVACY.md 的提交历史](https://github.com/55kai12/Xian/commits/main/PRIVACY.md)
追溯每一次改动。

---

## 七、联系

发现任何与本政策不符的行为，欢迎提 [Issue](https://github.com/55kai12/Xian/issues)。
