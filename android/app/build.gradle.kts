import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.aboutlibraries)
}

android {
  namespace = "io.github.yashwanthkamireddi.skykeep"
  compileSdk = 37
  // The NDK the engine is built with: Android's packaging uses its strip to take the symbols
  // (kept for the bindings) out of the app.
  ndkVersion = "30.0.16248370"
  defaultConfig {
    applicationId = "io.github.yashwanthkamireddi.skykeep"
    minSdk = 29
    targetSdk = 36
    // One version for every Skykeep: the desktop's (src-tauri/tauri.conf.json), so a release tag
    // names the same version on each. The code Android compares is that version as a number,
    // 0.1.21 as 1021 (major, minor, patch in thousands), never smaller for a later release.
    val release = groovy.json.JsonSlurper().parse(rootProject.file("../src-tauri/tauri.conf.json")) as Map<*, *>
    val version = release["version"] as String
    val (major, minor, patch) = version.split('.').map { it.takeWhile(Char::isDigit).toInt() }
    versionName = version
    versionCode = major * 1_000_000 + minor * 1_000 + patch
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Two apps from one code base: the suite as it is, and uNotes cloud, which is Notes alone.
  // A flavor sets only its id and the build flag; the manifest overlay, the sync list and the
  // start-up screens read the flag, so upstream's files gain a few checks, not a second copy.
  flavorDimensions += "app"
  productFlavors {
    create("suite") {
      // Today's app, unchanged: its id is the default one.
      buildConfigField("boolean", "NOTES_ONLY", "false")
    }
    create("unotes") {
      // Installs beside Skykeep, with its own sign-in and library.
      applicationId = "io.github.brenoavelheda.unotes"
      buildConfigField("boolean", "NOTES_ONLY", "true")
    }
  }

  buildTypes {
    release {
      // R8: the release app shrunk and its code optimised; what JNA and UniFFI reach by
      // reflection is kept (proguard-rules.pro).
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Phones only: every Android phone sold for years is arm64. The x86_64 engine (10 MB) is
      // the emulator's, kept in debug builds; in the release APK it was 40% of the download.
      ndk { abiFilters += listOf("arm64-v8a") }
    }
    // The release build exactly (shrunk by R8), signed with the debug key and installed beside
    // the real app under its own id: what is shipped is run and checked on a phone without
    // touching the signed-in copy there.
    create("verify") {
      initWith(getByName("release"))
      applicationIdSuffix = ".verify"
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
      // Run on the emulator too, so both engines, where the release keeps only the phones' one.
      ndk { abiFilters.clear(); abiFilters += listOf("arm64-v8a", "x86_64") }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    // BuildConfig.APPLICATION_ID names the account and sync authorities, so a second build
    // (verify) installs beside the real app without clashing.
    buildConfig = true
    resValues = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

androidComponents {
  // The account type and sync authorities follow the id the variant installs under, verify's
  // suffix included: BuildConfig.APPLICATION_ID, the sync XML and the Kotlin constants are one
  // value by construction. A mismatch makes sign-in create an account Android cannot sync.
  onVariants { variant ->
    val resources = tasks.register<VariantAccountResources>(
      "accountResources${variant.name.replaceFirstChar { it.uppercase() }}",
    ) {
      applicationId.set(variant.applicationId)
      output.set(layout.buildDirectory.dir("generated/accountResources/${variant.name}/res"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(resources) { it.output }
  }
  // unotesRelease is the owner's APK, built on his PC: signed with the debug key so it installs
  // without a release keystore, as the verify build type already is. suiteRelease stays as it
  // was, unsigned unless upstream's own release signing is configured: this fork does not sign
  // upstream's app with its own key.
  onVariants(selector().withFlavor("app", "unotes").withBuildType("release")) { variant ->
    variant.signingConfig.setConfig(android.signingConfigs.getByName("debug"))
  }
}

/**
 * One variant's account type and sync authorities, from the id that variant is installed under.
 * Generated because the manifest overlay and the verify suffix change the id per variant, and a
 * stale string here makes sign-in create an account the sync XML cannot reach.
 */
abstract class VariantAccountResources : DefaultTask() {
  @get:Input abstract val applicationId: Property<String>
  @get:OutputDirectory abstract val output: DirectoryProperty

  @TaskAction
  fun write() {
    val id = applicationId.get()
    val values = output.get().asFile.resolve("values").apply { mkdirs() }
    values.resolve("account.xml").writeText(
      buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        appendLine("<!-- Generated from the variant's application id (BuildConfig.APPLICATION_ID). -->")
        appendLine("<resources>")
        appendLine("  <string name=\"account_type\">$id.account</string>")
        for (s in listOf("reminders", "notes", "photos")) appendLine("  <string name=\"authority_$s\">$id.$s</string>")
        appendLine("</resources>")
      },
    )
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  // UniFFI's Kotlin bindings call the Rust engine through JNA.
  implementation("net.java.dev.jna:jna:5.19.1@aar")
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  // The note page: bundled assets served from the app's own https origin, no file access.
  implementation(libs.androidx.webkit)
  // Background work: syncs, photo backup, Drive saves and Send to iPhone before Android 14.
  implementation(libs.androidx.work.runtime)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Home screen widgets (Material 3 / Android 15 Glance)
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // org.json as the phone has it: unit tests would otherwise get Android's stubbed copy.
  testImplementation(libs.org.json)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

// The engine, built from the repository's Rust crates before every build: the library for each
// ABI (cargo-ndk), then its Kotlin side generated from that same code (uniffi-bindgen), so the
// two can never be from different versions.
val repo = rootProject.projectDir.parentFile
val engine = tasks.register<Exec>("buildEngine") {
  workingDir = repo
  environment("CARGO_TARGET_DIR", File(repo, "target-android").path)
  // Symbols kept here: the bindings are read from them (UniFFI's metadata), and the app's own
  // packaging strips them from the APK (stripDebugDebugSymbols / its release twin).
  environment("CARGO_PROFILE_RELEASE_STRIP", "debuginfo")
  commandLine(
    "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-P", "29",
    "-o", file("src/main/jniLibs").path, "build", "--release", "-p", "quince-mobile",
  )
}
val bindings = tasks.register<Exec>("engineBindings") {
  dependsOn(engine)
  workingDir = repo
  environment("CARGO_TARGET_DIR", File(repo, "target-android/host").path)
  // Read from the phone library just built (the 64-bit Arm one): the tool is its own small
  // crate, so nothing of the engine is built for this computer.
  commandLine(
    "cargo", "run", "-q", "-p", "uniffi-bindgen", "--",
    "generate", "--library", file("src/main/jniLibs/arm64-v8a/libquince_mobile.so").path,
    "--language", "kotlin", "--no-format", "--out-dir", file("src/main/kotlin").path,
  )
}
// The engine's crates and their licences (cargo-about, about.toml at the top of the repository),
// written into the app for Settings › Open-source licences beside the Android libraries'.
val engineLicences = tasks.register<Exec>("engineLicences") {
  workingDir = repo
  val out = file("src/main/res/raw/engine_licences.json")
  inputs.file(File(repo, "Cargo.lock"))
  inputs.file(File(repo, "about.toml"))
  outputs.file(out)
  commandLine(
    "cargo", "about", "generate", "--format", "json", "--fail",
    "-m", "crates/quince-mobile/Cargo.toml", "--target", "aarch64-linux-android", "-o", out.path,
  )
}
tasks.named("preBuild") { dependsOn(bindings, engineLicences) }

// The note page: the repository's own web project built with the notes editor's entry
// (vite.mobile.config.ts), into the APK's assets. Generated and git-ignored, like the engine's
// libraries; `npm ci` at the top of the repository is the developer's, as for the desktop.
val notesEditor = tasks.register<Exec>("buildNotesEditor") {
  workingDir = repo
  val npm = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) listOf("cmd", "/c", "npm") else listOf("npm")
  commandLine(npm + listOf("run", "build:mobile-notes"))
}
tasks.named("preBuild") { dependsOn(bindings, engineLicences, notesEditor) }

// The app's libraries and their licences, written into the app (res/raw/aboutlibraries.json)
// for Settings › Open-source licences. From the build's own dependency list, offline: nothing is
// fetched, and a library with no licence stated fails the build rather than ship unlisted.
aboutLibraries {
  offlineMode = true
  library {
    duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
  }
}
