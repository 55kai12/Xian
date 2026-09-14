import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use(::load)
    }
}

android {
    namespace = "com.xian.focus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xian.focus"
        minSdk = 26
        targetSdk = 35
        versionCode = 254
        versionName = "2.0.54"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
            // targetSdk >= 30 时 AGP 默认关掉 v1（JAR）签名，只留 v2。
            // 安卓系统本身认 v2 就够了，但国产 ROM 的安装器（小米 / vivo / 华为等）
            // 仍有校验 v1 的，缺了会直接报「安装包损坏 / 解析包出错」。
            // 多签一层 v1 没有代价：同一个 keystore，v2 照旧在。
            enableV1Signing = true
        }
    }

    buildTypes {
        release {
            // 正式包开启代码压缩 / 混淆与资源压缩。
            // 之前 release 是完全不处理的裸包：体积偏大，且逻辑对反编译几乎不设防。
            // 规则见 proguard-rules.pro（第三方控件通过 XML 反射实例化，必须保留）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

android.buildFeatures { viewBinding = true }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")

    // A 组：高价值 UI 库
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.jaredrummler:material-spinner:1.3.1")
    implementation("com.github.QuadFlask:colorpicker:0.0.15")
    implementation("com.kyleduo.switchbutton:library:2.1.0")
    implementation("com.andrognito.pinlockview:pinlockview:2.1.0")

    // B 组：可选增强库
    implementation("de.hdodenhof:circleimageview:3.0.1")
    implementation("com.squareup.picasso:picasso:2.8")
    implementation("com.wang.avi:library:2.1.3")
    implementation("com.haibin:calendarview:3.7.1")
    // 已移除 io.github.songlonggithub:uTakePhoto:1.2.0
    // 原因：该库的 manifest 会在合并时带进 CAMERA / READ_EXTERNAL_STORAGE /
    // WRITE_EXTERNAL_STORAGE / ACCESS_COARSE_LOCATION 四个敏感权限。
    // 唯二用到它的地方（任务插图、壁纸）早就改用系统相册选择器，属于纯多余的依赖。
}

