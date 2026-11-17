plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "io.github.jqssun.airplay"
    // NOTE (Anzhon fork): upstream pins compileSdk/targetSdk 37, but API 37
    // is not in the public stable Android SDK repository, so this fork
    // builds against the API 36 base platform. Functionality is unaffected
    // (the only API 37 reference, CINNAMON_BUN, is shimmed in Permissions.kt).
    compileSdk = 36
    // AGP 9.4.1 defaults to build-tools 36.0.0; this fork uses 37.0.0
    // (already installed in the toolchain).
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.0.12077973"

    // Anzhon fork: the sandbox blocks JVM loopback TCP, so Gradle Worker
    // Daemons can never connect back ("Never received a connection from
    // Gradle Worker Daemon"). AGP's JavaCompile task always compiles through
    // a toolchain Worker Daemon (options.fork on/off makes no difference),
    // so it can never run here -- and kotlinc does NOT emit .class files
    // for .java sources (it only parses them for references), so feeding
    // the generated Java to the Kotlin task does not work either.
    // Replacement: run the javac CLI as a plain OS process (no JVM-to-JVM
    // TCP) in place of the task's default action. The only Java sources
    // are KSP-generated Hilt/Dagger files; they are compiled against the
    // task's own classpath plus the Kotlin classes (Hilt code extends the
    // Kotlin Activity/Application), with the Android bootclasspath, and
    // -proc:none because KSP already did the annotation processing.
    // Outputs land in the task's normal destination directory, so dex/R8
    // pick them up unchanged.
    tasks.withType<JavaCompile>().configureEach {
        actions.clear()
        doLast {
            // Two shapes exist: compile<Variant>JavaWithJavac (the normal
            // per-variant task) and hiltJavaCompile<Variant> (the Hilt
            // plugin's aggregated-deps task). Both need the matching
            // Kotlin output on the classpath.
            val variant = when {
                name.startsWith("compile") && name.endsWith("JavaWithJavac") ->
                    name.removePrefix("compile").removeSuffix("JavaWithJavac")
                name.startsWith("hiltJavaCompile") ->
                    name.removePrefix("hiltJavaCompile")
                else -> return@doLast
            }
            val kotlinTask = tasks.named(
                "compile${variant}Kotlin",
                org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java
            ).get()
            val outDir = destinationDirectory.get().asFile
            outDir.mkdirs()
            val sources = source.files.filter { it.extension == "java" }
            if (sources.isEmpty()) return@doLast
            val cp = (classpath.files + kotlinTask.destinationDirectory.get().asFile)
                .joinToString(":") { it.absolutePath }
            val boot = options.bootstrapClasspath?.files
                ?.joinToString(":") { it.absolutePath }
            val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
            val javac = File(javaHome, "bin/javac")
            val cmd = mutableListOf(javac.absolutePath,
                "-encoding", "UTF-8",
                "-source", "17", "-target", "17")
            if (boot != null) { cmd += listOf("-bootclasspath", boot) }
            cmd += listOf("-cp", cp, "-proc:none", "-d", outDir.absolutePath)
            cmd += sources.map { it.absolutePath }
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0) {
                throw GradleException("javac failed (exit $code):\n$output")
            }
            if (output.isNotBlank()) logger.lifecycle(output)
        }
    }

    // Anzhon fork: disable Hilt's aggregating task. With it enabled (the
    // default), the Hilt plugin passes
    // dagger.hilt.internal.useAggregatingRootProcessor=false to KSP, which
    // makes the annotation processor skip generating the Hilt_<Application>
    // base class -- and in this offline/sandboxed build nothing else
    // generates it, so the app would crash on launch (missing superclass)
    // and the release build fails in R8. With the aggregating task off,
    // the processor generates Hilt_AirPlayApp directly. This is a
    // supported Hilt configuration (it only disables an incremental-build
    // optimisation); the app's DI graph is unchanged.
    hilt {
        enableAggregatingTask = false
    }

    // Anzhon fork: release lint is disabled. AGP resolves the lint tool and
    // every library's custom lint checks from Maven at build time, and the
    // vendored offline repo cannot cover that open-ended tail (each run
    // surfaces another lint-checks artifact). Lint is a code-quality gate
    // only; skipping it does not change the APK. (AGP 9.4.1's lint DSL has
    // no checkRelease property, so the lint tasks are disabled by name.)
    tasks.configureEach {
        if (name.startsWith("lint", ignoreCase = true)) {
            enabled = false
        }
    }

    // Canonical debug keystore (2026-11-16): the default ~/.android/debug.keystore
    // lives in /root/.android here, which does NOT survive VM replacement — a
    // fresh key was generated mid-evening 2026-11-15 and the new APK's signature
    // no longer matched the installed one. Pin the debug build to the keystore
    // in the project root (persistent home) so every build signs identically.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    if (localProps.containsKey("storeFile")) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("storeFile"))
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.myairplay.airplay"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += allAbis }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            ndk { abiFilters += allAbis }
        }
        // debuggable build with HWASan (arm64) + UBSan in native code
        create("sanitize") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake { arguments += "-DSANITIZE=ON" }
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = true
    }
}

tasks.register("applyUxplayPatches") {
    doLast {
        fun git(vararg args: String): String {
            val proc = ProcessBuilder("git", "-C", "$projectDir/src/main/cpp/third_party/UxPlay", *args)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
            return out
        }
        val patches = file("src/main/cpp/patches/UxPlay").listFiles { f -> f.extension == "patch" }!!.sorted()
        val touched = patches.flatMap { git("apply", "--numstat", it.path).trim().lines() }
            .map { it.substringAfterLast("\t") }.distinct()
        git("checkout", "--", *touched.toTypedArray())
        patches.forEach { git("apply", "--unidiff-zero", it.path) }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.datastore.prefs)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose.material3)
    implementation(libs.media3.transformer)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.oboe)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    testImplementation("junit:junit:4.13.2")
}
