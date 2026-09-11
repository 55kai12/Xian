# ============================================================
# 贤 (PomodoroFocus) —— R8 / ProGuard 规则
# 之前 release 构建完全没有这个文件，也没有开启压缩，本文件为新增。
# ============================================================

# ---------- 通过 XML 反射实例化的自定义 View ----------
# AAPT2 一般会为布局中出现的自定义 View 自动生成 keep 规则，
# 这里再显式保留一次，避免布局里的控件被裁掉后在启动时抛 ClassNotFoundException。
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class com.xian.focus.CircleMonthView { *; }
-keep class com.xian.focus.CircleWeekView { *; }
-keep class com.xian.focus.MonthCalendarView { *; }
-keep class com.xian.focus.WeeklyBarChartView { *; }
-keep class com.xian.focus.XianWeekBar { *; }
-keep class com.xian.focus.TrendLineChartView { *; }
-keep class com.xian.focus.WeekCalendarStripView { *; }
-keep class com.xian.focus.SwipeInterceptorLayout { *; }

# ---------- 第三方控件（多数靠 XML 声明，且没有 consumer rules） ----------
-keep class com.haibin.calendarview.** { *; }
-keep class com.jaredrummler.materialspinner.** { *; }
-keep class com.andrognito.pinlockview.** { *; }
-keep class com.kyleduo.switchbutton.** { *; }
-keep class com.flask.colorpicker.** { *; }
-keep class com.wang.avi.** { *; }
-keep class de.hdodenhof.circleimageview.** { *; }
-keep class com.github.mikephil.charting.** { *; }

# ---------- 数据层 ----------
# Room / Hilt 自带 consumer rules；这里额外保住实体与 DAO，
# 避免字段/方法名被混淆后 SQLite 列名与字段映射对不上。
-keep class com.xian.focus.data.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# ---------- 无障碍服务与组件 ----------
# 组件由 AndroidManifest 声明，AGP 会生成对应 keep 规则，这里保留回调方法签名。
-keep class com.xian.focus.FocusLockAccessibilityService { *; }
-keep class com.xian.focus.LockAlarmReceiver { *; }
-keep class com.xian.focus.BootReceiver { *; }

# ---------- OkHttp 平台适配器的可选依赖 ----------
# okhttp 的 Platform 实现会引用 Conscrypt / BouncyCastle / OpenJSSE 这些
# 只在特定运行环境才存在的 TLS 提供者，运行时通过反射探测、缺失即跳过。
# 编译期它们并不在 classpath 上，必须显式 dontwarn，否则 R8 直接报 Missing classes。
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# ---------- 常见必需属性 ----------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 枚举的 valueOf/values 会被反射调用（如 TimerStatus.valueOf），不能裁
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留行号，方便线上崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
