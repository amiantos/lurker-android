import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The last versionCode set by hand (Play build 3). Debug builds keep it; a release APK that can't
// count commits falls back to it. The release versionCode proper is derived at the bottom.
val handSetVersionCode = 3

// Push (lurker-android#16). google-services.json ties a build to the Firebase project that can push to
// it, and only the publisher's project can push to the published app (an FCM token is scoped to the
// project in the APK, MismatchSenderId otherwise) — so the file is gitignored, not committed, and a
// build without it is a normal build with push switched off: CI, and anyone building the app
// themselves. `PushRegistrar` reads that as "Firebase isn't here" and never asks for permission.
//
// The PLAY bundle without it would be the published app silently unable to receive push, so that
// refuses. Only the bundle: Play takes nothing else, and `./gradlew build` (which assembles a
// release APK) has to keep working without the file.
val googleServicesJson = file("google-services.json")
if (googleServicesJson.exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}
gradle.taskGraph.whenReady {
    val bundlesRelease = allTasks.any { it.project == project && it.name == "bundleRelease" }
    if (bundlesRelease && !googleServicesJson.exists()) {
        throw GradleException(
            "app/google-services.json is missing: a Play bundle built without it can't receive push. " +
                "Download it from the Firebase console (project lurker-4cec0).",
        )
    }
    if (bundlesRelease) {
        // Same shape as the push guard: a release APK falls back (the source says so), the Play
        // bundle refuses. Reading the count here makes it an input of the bundle's configuration-
        // cache entry, so a new commit reconfigures a bundle build (rare) and never anything else.
        val counted = gitCommitCount.get()
        val count = counted.toIntOrNull() ?: throw GradleException("release versionCode: $counted")
        fun git(vararg args: String): String {
            val run = providers.exec {
                workingDir = rootDir
                commandLine("git", *args)
                isIgnoreExitValue = true
            }
            if (run.result.get().exitValue != 0) {
                throw GradleException("release versionCode: git ${args.joinToString(" ")} failed: ${run.standardError.asText.get().trim()}")
            }
            return run.standardOutput.asText.get().trim()
        }
        // A Play bundle comes from the published main, clean. Uncommitted changes (a new source file
        // too, even where status.showUntrackedFiles says no) would make the number name a commit
        // that isn't what was uploaded. HEAD must be what origin/main points at: that is where
        // `git switch main && git pull` leaves it, and it admits a detached checkout of that
        // commit; an unpushed commit would have the tag name one that isn't on GitHub, and a
        // branch counts higher than the main it was cut from, so a bundle from one takes a number
        // main only reaches later, or already has. (A main not fetched since the last merge still
        // passes: that is the one step of the recipe the build can't check without the network.)
        val dirty = git("status", "--porcelain", "--untracked-files=all")
        if (dirty.isNotEmpty()) {
            throw GradleException("release versionCode: uncommitted changes. A Play bundle comes from a clean main, so the tag names what was uploaded.\n$dirty")
        }
        val head = git("rev-parse", "HEAD")
        val published = git("rev-parse", "refs/remotes/origin/main")
        if (head != published) {
            throw GradleException("release versionCode: HEAD is ${head.take(7)}, origin/main is ${published.take(7)}. A Play bundle comes from the published main: git switch main && git pull (and push first if main is ahead).")
        }
        // The count alone doesn't name a commit (an amend keeps it), so the hash goes next to it,
        // in the log and in a file beside the bundle for a build whose log scrolled away (Android
        // Studio's wizard, which also sends the bundle elsewhere: its location resolves against
        // this project, as AGP resolves it, and the wizard's default is inside the checkout, so
        // the file is gitignored or the next bundle would refuse it as untracked). Locals only:
        // the action is serialized into the cache.
        val sha = head.take(7)
        val redirected = providers.gradleProperty("android.injected.apk.location").orNull
        val bundleDir = if (redirected != null) file(redirected).resolve("release") else layout.buildDirectory.dir("outputs/bundle/release").get().asFile
        val record = File(bundleDir, "versionCode.txt")
        tasks.named("bundleRelease") {
            doLast {
                logger.lifecycle("versionCode $count at $sha")
                record.parentFile.mkdirs()
                record.writeText("versionCode $count at $sha\n")
            }
        }
    }
}

android {
    namespace = "net.amiantos.lurker"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "net.amiantos.lurker"
        // 28 (Android 9) so the app installs on Fire OS 7 tablets, which are built on
        // Android 9, and on e-ink devices that lag the mainline API level. Nothing here
        // needs more: lint (NewApi) holds the app and, via :lurkerkit-device, the kit to it.
        minSdk = 28
        targetSdk = 36
        // Debug builds keep this constant, so switching branches on a test phone never fails as a
        // version downgrade and never asks for an uninstall. The release versionCode is set below.
        versionCode = handSetVersionCode
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// The release versionCode is the commit count of the branch being built, so a Play upload never
// needs a bump commit: every merge to main is the bump, and a release is pull, bundle, upload, tag
// (bundleRelease logs the number the bundle got). Play only requires the number to grow, so the
// jump from the last hand-set code is fine. Bundles come from the published main, clean, and the
// bundle guard above refuses anything else: uncommitted changes don't change the count, so the tag
// would name a commit that isn't what was uploaded, and a branch counts higher than the main it
// was cut from, so a bundle from one takes a number main only reaches later, or already has.
//
// A ValueSource set through the variant API, rather than a number read into defaultConfig: the
// count is resolved when the manifest task runs, so it is not a configuration-cache input and a
// new commit reruns that task, not the whole configuration. (A lambda in this script can't do it:
// a script-level val is a property of the script object, and the tasks that take versionCode
// serialize the provider into the cache, where a script reference can't go.) The source returns
// the count, or the reason there isn't one: no .git of its own (a source download; a tree nested
// in another repo, as this one is in lurker-dev, must not count that one), no git on PATH, a
// shallow clone (a real number, short), or git saying anything but a number. A release APK then
// falls back to the hand-set code and the source says so, so `./gradlew build` keeps working like
// the push guard above promises; the Play bundle refuses with the reason, in that guard.
abstract class GitCommitCount @Inject constructor(private val exec: ExecOperations) :
    ValueSource<String, GitCommitCount.Params> {
    interface Params : ValueSourceParameters {
        val repoDir: DirectoryProperty
    }

    // Said here, not in the guard: a task action replays it on a configuration-cache hit, a
    // whenReady block doesn't run on one.
    private fun reason(why: String): String {
        Logging.getLogger(GitCommitCount::class.java).warn("release versionCode: $why. A release APK gets the hand-set code; a Play bundle refuses.")
        return why
    }

    override fun obtain(): String {
        val repo = parameters.repoDir.get().asFile
        if (!repo.resolve(".git").exists()) return reason("$repo has no .git of its own")
        fun git(vararg args: String): Result<String> = runCatching {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val result = exec.exec {
                workingDir = repo
                commandLine("git", *args)
                standardOutput = out
                errorOutput = err
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) error("git ${args.joinToString(" ")} in $repo failed: ${err.toString().trim()}")
            out.toString().trim()
        }
        // Anything but "false" refuses: "true", or an old git (before 2.15) echoing the flag it
        // doesn't know, which can't say either way.
        when (val shallow = git("rev-parse", "--is-shallow-repository").getOrElse { return reason(it.message ?: "$it") }) {
            "false" -> {}
            "true" -> return reason("$repo is a shallow clone, so its commit count is short. Unshallow it: git fetch --unshallow")
            else -> return reason("git in $repo is too old to answer --is-shallow-repository (it said `$shallow`), so the commit count can't be trusted")
        }
        val count = git("rev-list", "--count", "HEAD").getOrElse { return reason(it.message ?: "$it") }
        if (count.toIntOrNull() == null) return reason("git rev-list --count HEAD in $repo printed `$count`, not a number")
        return count
    }
}
val gitCommitCount: Provider<String> = providers.of(GitCommitCount::class) { parameters.repoDir.set(rootDir) }
val releaseVersionCode: Provider<Int> = gitCommitCount.map { it.toIntOrNull() }.orElse(handSetVersionCode)
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { it.versionCode.set(releaseVersionCode) }
    }
}

dependencies {
    implementation(project(":lurkerkit"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.browser)
    implementation(libs.reorderable)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.okhttp)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}