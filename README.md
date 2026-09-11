# 贤 - 古风早期版本与任务管理

一款中国古风主题的 Android 应用，集成任务清单、番茄钟（贤时）、锁机专注、个性化复盘与日记功能。

## 项目信息

- **应用名称**：贤
- **包名**：`com.xian.focus`
- **当前版本**：v1.2.6（versionCode 126）
- **最低 SDK**：26（Android 8.0）
- **目标 SDK**：35（Android 15）
- **开发语言**：Kotlin
- **构建工具**：Gradle + Android Gradle Plugin

## 目录结构

```
Xian/
├── PomodoroFocus/          # Android 项目源码
│   ├── app/                 # 应用模块
│   │   ├── src/main/        # 源代码、资源、Manifest
│   │   └── build/           # 构建输出
│   ├── gradle/              # Gradle Wrapper
│   ├── build.gradle.kts     # 项目构建配置
│   ├── settings.gradle.kts  # 项目设置
│   ├── local.properties     # 本地 SDK 路径
│   ├── keystore.properties  # 签名密钥配置
│   └── release.keystore     # 发布签名密钥
├── release/                  # 发布的 APK 安装包
│   ├── 贤.apk               # 最新版本
│   └── 早期版本包  # 历史版本
├── docs/                     # 文档
│   └── 更新日志.md           # 详细版本更新日志（v0.1 - v1.2.6）
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
10. **uTakePhoto** - 拍照/选图

## 构建说明

```bash
cd PomodoroFocus
./gradlew assembleRelease
```

构建产物：`app/build/outputs/apk/release/app-release.apk`

## 版本规则

每次交付 APK，版本号 +0.0.1（如 1.2.6 → 1.2.7），versionCode 对应 +1。
