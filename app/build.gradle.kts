// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.artifact.SingleArtifact
import com.mikepenz.aboutlibraries.plugin.StrictMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

// Licenses a shipped dependency may use (ARCHITECTURE §12, "Allowed").
val allowedAndroidLicenses = listOf(
    "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "MIT", "ISC", "Zlib", "MPL-2.0",
    "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later", "GPL-3.0-only", "GPL-3.0-or-later",
    "GPL-2.0-or-later", "OFL-1.1", "Unicode-DFS-2016", "Unicode-3.0",
)
// go-licenses reports only these for the Go side.
val allowedGoLicenses = "Apache-2.0,BSD-2-Clause,BSD-3-Clause,MIT,ISC,MPL-2.0"

val repoRoot: Directory = rootProject.layout.projectDirectory
val coreRoot: Directory = repoRoot.dir("core")
val coreAar: RegularFile = layout.projectDirectory.file("libs/core.aar")

// ---- Source link for the About screen (GPL-3.0 §6) --------------------------

fun git(vararg args: String): Provider<String> =
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
        workingDir = repoRoot.asFile
    }.standardOutput.asText.map { it.trim() }

val gitTag = git("describe", "--tags", "--exact-match", "HEAD")
val gitCommit = git("rev-parse", "HEAD")
val sourceUrl: Provider<String> = gitTag.zip(gitCommit) { tag, commit ->
    "https://github.com/dennisklein/sshovel/tree/" + tag.ifEmpty { commit.ifEmpty { "main" } }
}

// ---- Debug-only test-env credentials ----------------------------------------
// The debug build's hardcoded profile (M2) logs into test-env with a key
// generated here, and pins the host key test-env generated on first start.

val testEnvKeys: Directory = repoRoot.dir("test-env/keys")
val debugHostKeyPub = providers.fileContents(repoRoot.file("test-env/hostkeys/ssh_host_ed25519_key.pub")).asText

/** SHA256 fingerprint of an OpenSSH public key line, as ssh-keygen -l prints it. */
fun sshFingerprint(pubLine: String): String {
    val blob = Base64.getDecoder().decode(pubLine.trim().split(Regex("\\s+"))[1])
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

android {
    namespace = "com.github.dennisklein.sshovel"
    compileSdk = 37
    // Pinned so the toolbox image (tools/android-env/Dockerfile) already has it
    // and AGP doesn't download its own default on every run.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.github.dennisklein.sshovel"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0-m2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SOURCE_URL", "\"${sourceUrl.get()}\"")
        // core.aar is built for these only (buildGoCore); drop other ABIs'
        // native libs from dependencies instead of shipping an APK that
        // installs on 32-bit devices and then can't load libgojni.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug {
            val pub = debugHostKeyPub.orNull
            buildConfigField("String", "DEBUG_HOST_KEY_FP", if (pub != null) "\"${sshFingerprint(pub)}\"" else "\"\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // Keep .so files uncompressed and 16 KB-aligned in the APK.
        jniLibs { useLegacyPackaging = false }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // Strings arrive with their screens; the handoff copy lives in docs/design.
        disable += "MissingTranslation"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

aboutLibraries {
    offlineMode = true
    collect {
        fetchRemoteLicense = false
    }
    license {
        strictMode = StrictMode.FAIL
        allowedLicenses.addAll(allowedAndroidLicenses)
    }
}

dependencies {
    implementation(files(coreAar))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // tools/android-env/m2.sh proves checkLicenses fails on a forbidden license
    // (GPL-2.0 with FOSS exception) by building with -Psshovel.licenseProbe.
    if (providers.gradleProperty("sshovel.licenseProbe").isPresent) {
        implementation("com.mysql:mysql-connector-j:9.4.0")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ---- Go core → AAR (IMPLEMENTATION_PLAN §4) ---------------------------------

val buildGoCore = tasks.register<Exec>("buildGoCore") {
    group = "build"
    description = "Builds app/libs/core.aar from core/ with gomobile."
    inputs.files(
        fileTree(coreRoot) {
            include("**/*.go", "go.mod", "go.sum")
            exclude("**/*_test.go", "cmd/**", "internal/testutil/**")
        },
    ).withPropertyName("goSources")
    inputs.property("version", android.defaultConfig.versionName)
    outputs.file(coreAar)
    workingDir = coreRoot.asFile
    environment("ANDROID_HOME", androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath)
    val ldflags = "-X github.com/dennisklein/sshovel/core/mobile.version=${android.defaultConfig.versionName}"
    // gomobile execs gobind from PATH; `go install` in core/ installs the version pinned in go.mod.
    commandLine(
        "sh", "-c",
        "go install golang.org/x/mobile/cmd/gobind && " +
            "PATH=\"\$(go env GOPATH)/bin:\$PATH\" go tool gomobile bind " +
            "-target=android/arm64,android/amd64 -androidapi 26 " +
            "-javapkg=com.github.dennisklein.sshovel.core -ldflags='$ldflags' " +
            "-o '${coreAar.asFile.absolutePath}' ./mobile",
    )
}

/** Fails if any .so has an ELF LOAD segment aligned below 16 KB. */
abstract class VerifyPageAlignment : DefaultTask() {
    @get:InputFiles
    abstract val archives: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val bad = mutableListOf<String>()
        var checked = 0
        archives.files.filter { it.exists() }.forEach { archive ->
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".so") }.forEach { entry ->
                    val align = minLoadAlign(zip.getInputStream(entry).readBytes())
                    checked++
                    val line = "${archive.name}!${entry.name}: min LOAD align 0x${align.toString(16)}"
                    if (align < 0x4000) bad += line else logger.lifecycle("OK   $line")
                }
            }
        }
        if (checked == 0) throw GradleException("verifyPageAlignment: no .so files found")
        if (bad.isNotEmpty()) throw GradleException("16 KB page alignment violated:\n" + bad.joinToString("\n"))
    }

    private fun minLoadAlign(elf: ByteArray): Long {
        val b = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        require(elf[4].toInt() == 2) { "not a 64-bit ELF" }
        val phoff = b.getLong(0x20)
        val phentsize = b.getShort(0x36).toInt()
        val phnum = b.getShort(0x38).toInt()
        var min = Long.MAX_VALUE
        for (i in 0 until phnum) {
            val off = (phoff + i * phentsize).toInt()
            if (b.getInt(off) == 1 /* PT_LOAD */) min = minOf(min, b.getLong(off + 0x30))
        }
        return min
    }
}

// core.aar is checked before every build; each variant's APK after it's packaged.
val verifyCorePageAlignment = tasks.register<VerifyPageAlignment>("verifyCorePageAlignment") {
    group = "verification"
    description = "Checks that core.aar's native libraries are 16 KB page-aligned."
    archives.from(buildGoCore.map { coreAar })
}
tasks.named("preBuild") { dependsOn(verifyCorePageAlignment) }

val verifyPageAlignment = tasks.register("verifyPageAlignment") {
    group = "verification"
    description = "Checks that every native library in core.aar and the APKs is 16 KB page-aligned."
    dependsOn(verifyCorePageAlignment)
}

// ---- Licenses (ARCHITECTURE §12, IMPLEMENTATION_PLAN §4) ---------------------

/** Collects Go dependency licenses into an asset the licenses screen reads. */
abstract class CollectGoLicenses @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    abstract val goSources: ConfigurableFileCollection

    @get:Internal
    abstract val coreDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    private fun run(vararg cmd: String): String {
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine(*cmd)
            workingDir = coreDir.get().asFile
            environment("GOOS", "android")
            environment("GOARCH", "arm64")
            standardOutput = out
        }
        return out.toString(Charsets.UTF_8)
    }

    @TaskAction
    fun collect() {
        val save = workDir.get().dir("save").asFile
        val ignore = "github.com/dennisklein/sshovel"
        run("go-licenses", "save", "./mobile", "--ignore", ignore, "--save_path", save.absolutePath, "--force")
        val report = run("go-licenses", "report", "./mobile", "--ignore", ignore)
        val goroot = run("go", "env", "GOROOT").trim()
        val goVersion = run("go", "env", "GOVERSION").trim()

        data class Entry(val module: String, val url: String, val license: String, val text: String)
        val entries = mutableListOf(
            Entry("Go $goVersion (runtime and standard library)", "https://go.dev/LICENSE", "BSD-3-Clause",
                File(goroot, "LICENSE").readText()),
        )
        for (line in report.lines().filter { it.isNotBlank() }) {
            val (module, url, license) = line.split(",", limit = 3)
            val dir = File(save, module)
            val texts = dir.walkTopDown()
                .filter { it.isFile && Regex("(?i)^(LICEN[CS]E|COPYING|NOTICE)").containsMatchIn(it.name) }
                .sortedBy { it.path }
                .joinToString("\n\n") { it.readText() }
            entries += Entry(module, url, license, texts)
        }
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""
        val json = entries.joinToString(",\n", "[\n", "\n]\n") {
            """{"module":${q(it.module)},"url":${q(it.url)},"license":${q(it.license)},"text":${q(it.text)}}"""
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        File(out, "go_licenses.json").writeText(json)
        logger.lifecycle("collectGoLicenses: ${entries.size} entries")
    }
}

val collectGoLicenses = tasks.register<CollectGoLicenses>("collectGoLicenses") {
    group = "licenses"
    description = "Collects Go dependency licenses (go-licenses) into an app asset."
    goSources.from(fileTree(coreRoot) { include("**/*.go", "go.mod", "go.sum"); exclude("**/*_test.go") })
    coreDir.set(coreRoot)
    outputDir.set(layout.buildDirectory.dir("generated/goLicenses/assets"))
    workDir.set(layout.buildDirectory.dir("goLicenses"))
}

val checkGoLicenses = tasks.register<Exec>("checkGoLicenses") {
    group = "licenses"
    description = "Fails if a Go dependency of core/mobile uses a license not on the allowed list."
    workingDir = coreRoot.asFile
    environment("GOOS", "android")
    environment("GOARCH", "arm64")
    commandLine(
        "go-licenses", "check", "./mobile", "--ignore", "github.com/dennisklein/sshovel",
        "--allowed_licenses=$allowedGoLicenses",
    )
}

val checkLicenses = tasks.register("checkLicenses") {
    group = "licenses"
    description = "Fails on any shipped Android or Go dependency whose license isn't allowed."
    dependsOn(checkGoLicenses)
    // AboutLibraries strict mode fails these when a license isn't allowed.
    dependsOn(tasks.matching { it.name.startsWith("prepareLibraryDefinitions") })
}

abstract class ReuseLint @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val repoDir: DirectoryProperty

    @TaskAction
    fun lint() {
        val root = repoDir.get().asFile
        val hasReuse = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "reuse").canExecute() }
        if (hasReuse) {
            // reuse needs git to skip ignored files (build outputs, keys). If git
            // refuses the repo, e.g. "dubious ownership" in a container, say so
            // instead of listing every build output as unlicensed.
            val git = exec.exec {
                commandLine("git", "rev-parse", "--git-dir")
                workingDir = root
                isIgnoreExitValue = true
                standardOutput = ByteArrayOutputStream()
                errorOutput = ByteArrayOutputStream()
            }
            if (git.exitValue != 0) {
                throw GradleException(
                    "reuseLint: git can't read ${root.path}; if it's owned by another user, run " +
                        "git config --global --add safe.directory ${root.path}",
                )
            }
            exec.exec { commandLine("reuse", "lint"); workingDir = root }
            return
        }
        // Fallback (ARCHITECTURE §12): SPDX headers in source files.
        logger.warn("reuse not installed; checking SPDX headers only")
        val exts = setOf("go", "kt", "kts", "xml", "sh")
        val skip = listOf("/build/", "/.gradle/", "/docs/design/", "/.git/")
        val missing = root.walkTopDown()
            .filter { it.isFile && it.extension in exts && skip.none { s -> it.path.contains(s) } }
            .filter { f -> f.bufferedReader().use { r -> (1..10).mapNotNull { r.readLine() } }.none { "SPDX-License-Identifier" in it } }
            .toList()
        if (missing.isNotEmpty()) {
            throw GradleException("Missing SPDX headers:\n" + missing.joinToString("\n") { it.relativeTo(root).path })
        }
    }
}

val reuseLint = tasks.register<ReuseLint>("reuseLint") {
    group = "licenses"
    description = "REUSE/SPDX compliance (reuse lint, or a header check if reuse isn't installed)."
    repoDir.set(repoRoot)
}

tasks.named("check") { dependsOn(checkLicenses, collectGoLicenses, reuseLint, verifyPageAlignment) }
// Release builds don't ship without a passing license check (IMPLEMENTATION_PLAN §4);
// collectGoLicenses already feeds every variant's assets.
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkLicenses, reuseLint) }

// ---- Generated assets ---------------------------------------------------------

val debugTestKey = tasks.register<Exec>("debugTestKey") {
    group = "test-env"
    description = "Creates the debug build's test-env client key (test-env/keys/debug_client_key)."
    val key = testEnvKeys.file("debug_client_key").asFile
    val authorized = testEnvKeys.file("debug_authorized_keys").asFile
    outputs.files(key, authorized)
    onlyIf { !key.exists() || !authorized.exists() }
    commandLine(
        "sh", "-c",
        "rm -f '$key' '$key.pub' && ssh-keygen -q -t ed25519 -N '' -C sshovel-debug -f '$key' && " +
            "echo \"restrict,port-forwarding \$(cat '$key.pub')\" > '$authorized'",
    )
}

val debugKeyAsset = tasks.register<Copy>("debugKeyAsset") {
    dependsOn(debugTestKey)
    from(testEnvKeys.file("debug_client_key")) { rename { "test_env_client_key" } }
    into(layout.buildDirectory.dir("generated/debugKey/assets"))
}

androidComponents {
    onVariants { variant ->
        val verifyApk = tasks.register<VerifyPageAlignment>("verify${variant.name.replaceFirstChar { it.uppercase() }}PageAlignment") {
            group = "verification"
            description = "Checks that the ${variant.name} APK's native libraries are 16 KB page-aligned."
            archives.from(variant.artifacts.get(SingleArtifact.APK).map { dir -> dir.asFileTree.matching { include("*.apk") } })
        }
        verifyPageAlignment.configure { dependsOn(verifyApk) }
        variant.sources.assets?.addGeneratedSourceDirectory(collectGoLicenses, CollectGoLicenses::outputDir)
        if (variant.buildType == "debug") {
            variant.sources.assets?.addStaticSourceDirectory(
                layout.buildDirectory.dir("generated/debugKey/assets").get().asFile.absolutePath,
            )
        }
    }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(debugKeyAsset) }
