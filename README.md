# 贤 - 古风早期版本与任务管理

一款中国古风主题的 Android 应用，集成任务清单、番茄钟（贤时）、锁机专注、个性化复盘与日记功能。

## 项目信息

- **应用名称**：贤
- **包名**：`com.xian.focus`
- **当前版本**：v2.0.21（versionCode 221）
- **最低 SDK**：26（Android 8.0）
- **目标 SDK**：35（Android 15）
- **开发语言**：Kotlin
- **构建工具**：Gradle + Android Gradle Plugin

> 版本说明：此前 README 长期停留在 v1.2.6，而实际构建号早已是 2.0.20；
> 更新日志也只记录到 v2.0.1。**v2.0.2 ~ v2.0.20 这 19 个版本没有留下任何记录**，
> 已无法追溯。现以 v2.0.21 作为重新对齐后的起点，后续每次发版同步更新此处与 `docs/更新日志.md`。

## 目录结构

```
Xian/
├── .gitignore              # Git 忽略规则（build/、local.properties、keystore 等）
├── .gitattributes          # 统一换行符
├── PomodoroFocus/          # Android 项目源码
│   ├── app/                 # 应用模块
│   │   ├── src/main/        # 源代码、资源、Manifest
│   │   ├── proguard-rules.pro  # Release 混淆规则
│   │   └── build/           # 构建输出（不入库）
│   ├── gradle/              # Gradle Wrapper
│   ├── build.gradle.kts     # 项目构建配置
│   ├── settings.gradle.kts  # 项目设置
│   ├── local.properties     # 本地 SDK 路径（不入库）
│   └── keystore.properties  # 签名密钥配置（不入库，需自行创建）
├── release/                  # 发布的 APK 安装包（不入库，体积大）
│   └── 贤.apk               # 最新版本
├── docs/                     # 文档
│   └── 更新日志.md           # 详细版本更新日志
├── screenshots/              # 截图
│   ├── reference/            # 用户提供的设计参考图
│   └── verification/         # 各版本模拟器验证截图
└── README.md                 # 本文件
```

## 核心功能

### 1. 任务清单
- 分类标签（自定义颜色）
- 截止日期、重复规则
- 子任务（可展开/收起，自动编号）
- 工作量估算
- 系统日历提醒
- 周视图 + 月历下拉展开
- 每日完成趋势折线图

### 2. 贤时（番茄钟）
- 自定义专注时长（小时+分钟制）
- 每日贤时目标
- 专注前权限自检（无障碍、悬浮窗）
- 贤时统计与重置

### 3. 锁机专注
- 立即开始锁机
- 自定义锁机时间段
- 白名单应用（锁机期间可用）
- 无障碍服务 + 悬浮窗实现
- 开机自启动
- 应用 PIN 锁

### 4. 个性化复盘
- 近 7 天贤时趋势
- 任务完成统计
- 日记形式的今日状态（可左右翻日期、日历选日期）
- 写了日记亮标，没写暗标

### 5. 我的
- 四套古风主题（水墨青绿/朱砂宫墙/黛蓝远山/玄墨素纸）
- 自定义壁纸
- 数据导出（CSV）
- 今日运势

## 主题配色

| 主题 | 主色 | 背景 | 点缀 |
|------|------|------|------|
| 水墨青绿（默认） | #3B5B4E 墨绿 | #F5EFE0 宣纸 | #C9A961 古铜金 |
| 朱砂宫墙 | - | - | - |
| 黛蓝远山 | - | - | - |
| 玄墨素纸 | - | - | - |

## 已接入开源库

1. **MPAndroidChart** - 折线图/趋势图
2. **CalendarView** (com.haibin) - 月历视图
3. **MaterialSpinner** - 下拉选择
4. **colorpicker** - 颜色选择器
5. **SwitchButton** - 开关按钮
6. **PinLockView** - PIN 锁
7. **CircleImageView** - 圆形图片
8. **picasso** - 图片加载
9. **AVLoadingIndicatorView** - 加载动画

> 已移除 **uTakePhoto**：该库会在 manifest 合并时带进 `CAMERA`、`READ_EXTERNAL_STORAGE`、
> `WRITE_EXTERNAL_STORAGE`、`ACCESS_COARSE_LOCATION` 四个敏感权限。选图功能早已改用
> 系统相册选择器（`ActivityResultContracts.OpenDocument`），属于纯多余的依赖。

## 权限说明

| 权限 | 用途 |
|------|------|
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 前台计时与锁机服务 |
| `POST_NOTIFICATIONS` | 计时状态与完成提醒 |
| `SYSTEM_ALERT_WINDOW` | 锁机悬浮遮罩 |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复锁机时间段 |

应用**不申请**相机、存储、定位权限；`allowBackup` 已关闭，任务库与日记不会被自动备份导出。

## 构建说明

```bash
cd PomodoroFocus
./gradlew assembleRelease        # Release 已开启 R8 混淆 + 资源压缩
./gradlew assembleDebug          # 日常调试
```

构建产物：`app/build/outputs/apk/release/app-release.apk`

**在其他机器上构建前要注意：**

- `local.properties` 需自行创建，写入 `sdk.dir=<你的 Android SDK 路径>`。
- `keystore.properties` 需自行创建（**不要提交到仓库**），内容：
  ```
  storeFile=release.keystore
  storePassword=***
  keyAlias=***
  keyPassword=***
  ```
  缺失时 Release 包会以未签名方式产出。
- `gradle.properties` 里目前硬编码了本机代理（`<本机地址>:<端口>`）与 JDK 绝对路径。
  换机器请删掉这几行，否则 Gradle 会因为连不上代理而失败。
- 依赖已缓存时可加 `--offline` 跳过联网检查。

## 版本规则

每次交付 APK，版本号 +0.0.1（如 1.2.6 → 1.2.7），versionCode 对应 +1。
