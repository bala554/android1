@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.itsaky.androidide.plugins.AndroidIDEAssetsPlugin
import com.itsaky.androidide.plugins.tasks.AddFileToAssetsTask
import org.adfa.constants.GRADLE_API_NAME_JAR_BR
import org.adfa.constants.GRADLE_API_NAME_JAR_ZIP
import org.adfa.constants.GRADLE_DISTRIBUTION_ARCHIVE_NAME
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
	id("com.android.application")
	id("kotlin-android")
	id("kotlin-kapt")
	id("kotlin-parcelize")
	id("androidx.navigation.safeargs.kotlin")
	id("com.itsaky.androidide.desugaring")
	// Sentry gradle plugin; the SDK it wires up reports to our GlitchTip backend.
	alias(libs.plugins.sentry)
	alias(libs.plugins.google.services)
	alias(libs.plugins.kotlin.compose)
}

fun propOrEnv(name: String): String =
	project.findProperty(name) as String?
		?: System.getenv(name)
		?: ""

val props =
	Properties().apply {
		val file = rootProject.file("local.properties")
		if (file.exists()) load(file.inputStream())
	}

val glitchtipDsn = props.getProperty("glitchtipDsn") ?: propOrEnv("GLITCHTIP_DSN")

// Make CI flag available early so functions declared below can reference it
val isCiCd = System.getenv("GITHUB_ACTIONS") == "true"

apply {
	plugin(AndroidIDEAssetsPlugin::class.java)
}

buildscript {
	dependencies {
		classpath(libs.composite.desugaringCore)
		classpath(libs.org.json)
	}
}

android {
	namespace = BuildConfig.PACKAGE_NAME

	defaultConfig {
		applicationId = BuildConfig.PACKAGE_NAME
		vectorDrawables.useSupportLibrary = true
		testInstrumentationRunnerArguments["class"] = "com.itsaky.androidide.OrderedTestSuite"
	}

	signingConfigs {
		getByName("debug") {
			enableV2Signing = true
			enableV3Signing = true
		}
	}

	buildTypes {
		debug {
			signingConfig = signingConfigs.getByName("debug")
			manifestPlaceholders["sentryDsn"] = glitchtipDsn
		}
		release {
			manifestPlaceholders["sentryDsn"] = glitchtipDsn
		}
		create("profileable") {
			initWith(getByName("release"))
			isProfileable = true
			matchingFallbacks += "release"
			manifestPlaceholders["sentryDsn"] = glitchtipDsn
			signingConfig = signingConfigs.getByName("debug")
		}
	}

	testOptions {
		unitTests {
			isIncludeAndroidResources = true
			all {
				// Skip TreeSitter native library loading in tests
				it.systemProperty("java.library.path", System.getProperty("java.library.path"))
				it.systemProperty("androidide.test.mode", "true")
				// JUnit Platform, so JUnit Jupiter tests run; the vintage engine dependency
				// below keeps existing JUnit 4/Robolectric tests running unchanged.
				it.useJUnitPlatform()
			}
		}
	}

	androidResources {
		noCompress.add("tflite")
		generateLocaleConfig = true
	}

	buildFeatures {
		compose = true
	}

	sourceSets {
		getByName("androidTest") {
			manifest.srcFile("src/androidTest/AndroidManifest.xml")
		}
		getByName("profileable") {
			java.srcDir("src/release/java")
		}
	}

	lint {
		abortOnError = false
		disable.addAll(arrayOf("VectorPath", "NestedWeights", "ContentDescription", "SmallSp"))
	}

	packaging {
		resources {
			excludes += "META-INF/DEPENDENCIES"
			excludes += "META-INF/gradle/incremental.annotation.processors"

			// Beider-Morse phonetic rule data (commons-codec); unused, no code calls the phonetic encoders.
			excludes += "org/apache/commons/codec/language/bm/**"
			// JLine console resources bundled in the Kotlin analysis-api jar; used headlessly, no REPL/console.
			excludes += "org/jetbrains/kotlin/org/jline/**"
			// IntelliJ plugin K2-mode compatibility manifest; meaningless outside a real IntelliJ session.
			excludes += "pluginsCompatibleWithK2Mode.txt"
			// Raw .proto sources: protobuf well-known types from protobuf jars, plus our own
			// project-models/aapt2-proto/profiler/jvm-symbol-models modules, which the protobuf
			// gradle plugin bundles into "resources" by default. Only protoc/codegen reads these
			// at build time; nothing imports them across module boundaries, so they don't belong
			// in the shipped APK.
			excludes += "**/*.proto"
			// protobuf edition feature-default descriptors, used by protoc/codegen, not the lite runtime.
			excludes += "**/java_features_proto-descriptor-set.proto.bin"
			// httpclient's public-suffix cookie-domain data; unused, no code touches Apache HttpClient directly.
			excludes += "mozilla/public-suffix-list.txt"
			// Apache httpclient/httpcore version metadata, used only to build a default User-Agent string.
			excludes += "org/apache/http/client/version.properties"
			excludes += "org/apache/http/version.properties"
			// Version metadata for transitive google-http-client/google-auth-library deps.
			excludes += "com/google/api/client/http/google-http-client.properties"
			excludes += "com/google/auth/oauth2/google-auth-library.properties"
			// Version metadata for the shaded OpenTelemetry API in the Kotlin compiler embeddable jar.
			excludes += "org/jetbrains/kotlin/io/opentelemetry/**/version.properties"
			// unbescape's own version metadata; unused at runtime.
			excludes += "org/unbescape/unbescape.properties"
			// Shaded log4j NOTICE from the Kotlin compiler embeddable jar.
			excludes += "org/jetbrains/kotlin/org/apache/log4j/NOTICE"
			// IntelliJ platform plugin descriptors for the frontback PSI module; meaningless outside a real IntelliJ session.
			excludes += "intellij.java.frontback.psi.xml"
			excludes += "intellij.java.frontback.psi.impl.xml"
			// jgit's OSGi bundle localization data; unused outside an OSGi runtime.
			excludes += "OSGI-INF/l10n/plugin.properties"

			pickFirsts += "kotlin/internal/internal.kotlin_builtins"
			pickFirsts += "kotlin/reflect/reflect.kotlin_builtins"
			pickFirsts += "kotlin/kotlin.kotlin_builtins"
			pickFirsts += "kotlin/coroutines/coroutines.kotlin_builtins"
			pickFirsts += "kotlin/ranges/ranges.kotlin_builtins"
			pickFirsts += "kotlin/concurrent/atomics/atomics.kotlin_builtins"
			pickFirsts += "kotlin/collections/collections.kotlin_builtins"
			pickFirsts += "kotlin/annotation/annotation.kotlin_builtins"

			pickFirsts += "META-INF/FastDoubleParser-LICENSE"
			pickFirsts += "META-INF/thirdparty-LICENSE"
			pickFirsts += "META-INF/FastDoubleParser-NOTICE"
			pickFirsts += "META-INF/thirdparty-NOTICE"
		}

		jniLibs {
			// Debug default: uncompressed libs, loaded straight from the APK. Bundled-assets
			// variants (release/instrumentation) override this to true in AndroidModuleConf.kt
			// so their libs ship deflate-compressed and extract at install time (ADFA-2306).
			useLegacyPackaging = false
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
		isCoreLibraryDesugaringEnabled = true
	}
}

// DoD coverage gate. Two path traps are baked in below; both fail silently rather
// than erroring, so each is spelled out here to stop the next measurement repeating one.
//
// executionData: the root build attaches the jacoco agent to every Test task, but for an
// Android module the exec lands under outputs/unit_test_code_coverage/<variant>UnitTest/
// not build/jacoco/. A report pointed at build/jacoco/ SKIPs and measures nothing.
//
// classDirectories: :app runs transformDebugClassesWithAsm, so the bytecode the tests
// executed is NOT tmp/kotlin-classes/debug. That path is right for the quickbuild
// modules (they run no ASM transform) and wrong here: measured 2026-09-10 it makes 138
// classes report "Execution data ... does not match" and count as fully uncovered, which
// reads as a real coverage hole rather than a misconfiguration. The transformed dirs
// below give 0 mismatches.
tasks.register<JacocoReport>("jacocoTestReport") {
	group = "verification"
	description = "JaCoCo line+branch coverage for the debug unit tests."
	dependsOn("testDebugUnitTest")

	reports {
		xml.required.set(true)
		html.required.set(true)
	}

	classDirectories.setFrom(
		fileTree(
			layout.buildDirectory.dir(
				"intermediates/classes/debug/transformDebugClassesWithAsm/dirs",
			),
		) {
			exclude("**/BuildConfig*")
		},
	)
	sourceDirectories.setFrom(files("src/main/java"))
	executionData.setFrom(
		layout.buildDirectory.file(
			"outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
		),
	)
}

// Sentry gradle plugin config (crash reporting to GlitchTip).
sentry {
	includeProguardMapping = false
}

kapt { arguments { arg("eventBusIndex", "${BuildConfig.PACKAGE_NAME}.events.AppEventsIndex") } }

desugaring {
	replacements {
		includePackage("org.eclipse.jgit")

		applyJavaIOReplacements()
	}
}

configurations.matching { it.name.contains("AndroidTest") }.configureEach {
	exclude(group = "com.google.protobuf", module = "protobuf-lite")
}

configurations.configureEach {
	exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
	// auto-value is an annotation processor fat jar (shaded with ASM, JavaPoet, Guava, etc.)
	// and must not appear on the runtime classpath. Each module runs it via annotationProcessor/kapt
	// during its own compilation; the app module doesn't need it at runtime.
	exclude(group = "com.google.auto.value", module = "auto-value")
}

// brotli4j ships its native decoder as a per-OS/arch artifact, so the JVM unit tests need the one
// matching whoever is building. Mirrors build-logic/plugins' dispatch, but degrades to null on an
// unrecognized host instead of throwing: this runs at configuration time, so throwing would fail
// every task in the build -- including :app:assembleDebug, which needs no desktop native at all
// -- rather than only the JVM unit-test tasks that actually consume this dependency.
fun brotli4jNativeForHost(): Provider<MinimalExternalModuleDependency>? {
	val arch = DefaultNativePlatform.getCurrentArchitecture()
	val os = DefaultNativePlatform.getCurrentOperatingSystem()
	val native =
		when {
			os.isMacOsX -> {
				when {
					arch.isArm64 -> libs.brotli4j.osx.aarch64
					arch.isAmd64 -> libs.brotli4j.osx.x64
					else -> null
				}
			}

			os.isWindows -> {
				when {
					arch.isArm64 -> libs.brotli4j.windows.aarch64
					arch.isAmd64 -> libs.brotli4j.windows.x64
					else -> null
				}
			}

			os.isLinux -> {
				when {
					arch.isArm64 -> libs.brotli4j.linux.aarch64
					arch.isAmd64 -> libs.brotli4j.linux.x64
					else -> null
				}
			}

			else -> {
				null
			}
		}
	if (native == null) {
		logger.warn(
			"brotli4j: no native decoder for {}/{} -- brotli4j-backed JVM unit tests " +
				"(e.g. BrotliDictionaryDecodeTest) will fail with UnsatisfiedLinkError on this host.",
			os,
			arch,
		)
	}
	return native
}

dependencies {
	debugImplementation(libs.common.leakcanary)

	// Annotation processors
	kapt(libs.common.glide.ap)
	kapt(libs.google.auto.service)
	kapt(projects.annotationProcessors)
	kapt(libs.room.compiler)

	implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

	implementation(platform(libs.sora.bom))
	implementation(libs.common.editor)
	implementation(libs.common.glide)
	implementation(libs.common.jsoup)
	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.common.retrofit)
	implementation(libs.common.retrofit.gson)
	implementation(libs.common.charts)
	implementation(libs.common.hiddenApiBypass)
	implementation(libs.commons.compress)

	implementation(libs.google.auto.service.annotations)
	implementation(libs.google.gson)
	implementation(libs.google.guava)

	// Room
	implementation(libs.room.ktx)

	// Git
	implementation(libs.git.jgit)

	// Compose (ADR 0009 - new IDE dialogs/screens are Compose)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.runtime)
	implementation(libs.compose.ui)
	implementation(libs.compose.foundation)
	implementation(libs.compose.material3)
	implementation(libs.compose.activity)
	implementation(libs.compose.lifecycle.runtime)
	implementation(projects.commonCompose)
	implementation(libs.compose.ui.tooling.preview)
	debugImplementation(libs.compose.ui.tooling)

	// AndroidX
	implementation(libs.androidx.splashscreen)
	implementation(libs.androidx.annotation)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.cardview)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.coordinatorlayout)
	implementation(libs.androidx.drawer)
	implementation(libs.androidx.grid)
	implementation(libs.androidx.nav.fragment)
	implementation(libs.androidx.nav.ui)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.transition)
	implementation(libs.androidx.vectors)
	implementation(libs.androidx.animated.vectors)
	implementation(libs.androidx.work)
	implementation(libs.androidx.work.ktx)
	implementation(libs.google.material)

	// Dependencies in composite build
	implementation(libs.composite.appintro)
	implementation(libs.composite.desugaringCore)
	implementation(libs.composite.javapoet)
	implementation(libs.composite.treeview)

	// Local projects here
	implementation(projects.actions)
	implementation(projects.buildInfo)
	implementation(projects.common)
	implementation(projects.commonUi)
	implementation(projects.editor)
	implementation(projects.termux.termuxApp)
	implementation(projects.termux.termuxView)
	implementation(projects.termux.termuxEmulator)
	implementation(projects.termux.termuxShared)
	implementation(projects.eventbus)
	implementation(projects.eventbusAndroid)
	implementation(projects.eventbusEvents)
	implementation(projects.gradlePluginConfig)
	implementation(projects.subprojects.aaptcompiler)
	implementation(projects.subprojects.javacServices)
	implementation(projects.subprojects.kotlinAnalysisApi)
	implementation(projects.subprojects.shizukuApi)
	implementation(projects.subprojects.shizukuManager)
	implementation(projects.subprojects.shizukuProvider)
	implementation(projects.subprojects.shizukuServerShared)
	implementation(projects.subprojects.xmlUtils)
	implementation(projects.subprojects.projects)
	implementation(projects.subprojects.toolingApi)
	implementation(projects.logsender)
	implementation(projects.lsp.api)
	implementation(projects.lsp.external)
	implementation(projects.lsp.java)
	implementation(projects.lsp.kotlin)
	implementation(projects.lsp.xml)
	implementation(projects.lexers)
	implementation(projects.lookup)
	implementation(projects.preferences)
	implementation(projects.resources)
	implementation(projects.treeview)
	implementation(projects.templatesApi)
	implementation(projects.templatesImpl)
	implementation(projects.uidesigner)
	implementation(projects.xmlInflater)
	implementation(projects.pluginApi)
	implementation(projects.pluginManager)

	implementation(projects.idetooltips)
	implementation(projects.floatingWindow)
	implementation(projects.gitCore)
	implementation(projects.profiler)
	implementation(projects.quickbuild.core)

	// This is to build the tooling-api-impl project before the app is built
	// So we always copy the latest JAR file to assets
	compileOnly(projects.subprojects.toolingApiImpl)

	testImplementation(projects.testing.unit)
	testImplementation(libs.core.tests.anroidx.arch)
	testImplementation(libs.tests.junit.jupiter)
	testRuntimeOnly(libs.tests.junit.platformLauncher)
	// Keeps existing JUnit 4/Robolectric tests running under the JUnit Platform.
	testRuntimeOnly(libs.tests.junit.vintageEngine)
	androidTestImplementation(projects.common)
	androidTestImplementation(projects.testing.android) {
		exclude(group = "com.google.protobuf", module = "protobuf-lite")
	}
	androidTestImplementation(libs.tests.kaspresso)
	androidTestImplementation(libs.tests.junit.kts)
	androidTestImplementation(libs.tests.androidx.test.runner)
	// androidTestUtil(libs.tests.orchestrator)
	testImplementation(libs.tests.kotlinx.coroutines)

	// brotli4j
	implementation(libs.brotli4j)
	// JVM unit tests (e.g. BrotliDictionaryDecodeTest) run brotli4j's real native decoder, not an
	// Android target -- without a desktop native on the test classpath, Brotli4jLoader has nothing
	// to load and every such test fails with UnsatisfiedLinkError. Pick the native for whoever is
	// building, so the suite runs off a Linux x64 CI runner too (same dispatch as build-logic/plugins').
	// Null on an unrecognized host just means those specific tests fail there -- see
	// brotli4jNativeForHost's own warning -- not that this whole build should refuse to configure.
	brotli4jNativeForHost()?.let { testImplementation(it) }

	implementation(libs.common.markwon.core)
	implementation(libs.common.markwon.linkify)
	implementation(libs.commons.text.v1140)

	// Koin for Dependency Injection
	implementation(libs.koin.android)
	implementation(libs.androidx.security.crypto)

	// Sentry Android SDK (core + replay for quality configuration); our GlitchTip client.
	implementation(libs.sentry.core)
	implementation(libs.sentry.android.core)

	// Firebase Analytics
	implementation(platform(libs.firebase.bom))
	implementation(libs.firebase.analytics)

	// Lifecycle Process for app lifecycle tracking
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	coreLibraryDesugaring(libs.desugar.jdk.libs.v215)
}

// Quick Build (ADFA-4128): stage the runtime AAR + daemon (jar + runtime classpath)
// into APK assets, mirroring the LogSender AAR flow in AndroidIDEAssetsPlugin. The
// artifacts are extracted to <ANDROIDIDE_HOME>/quickbuild/ at session start
// (QuickBuildArtifactStager).
evaluationDependsOn(":quickbuild:runtime")
evaluationDependsOn(":quickbuild:daemon")

val quickBuildDaemonZip =
	tasks.register<Zip>("quickBuildDaemonZip") {
		archiveFileName.set("quickbuild-daemon.zip")
		destinationDirectory.set(layout.buildDirectory.dir("intermediates/quickbuild"))
		val daemonProject = rootProject.project(":quickbuild:daemon")
		// Use findByName to avoid throwing if the task is missing in an included build
		daemonProject.tasks.findByName("daemonJar")?.let { dependsOn(it) }
		// Safe from throwing; if the jar task doesn't exist, we won't add from(...)
		daemonProject.tasks.findByName("daemonJar")?.let { from(it) }
		// The daemon jar's manifest Class-Path names these by file name; they must sit
		// next to the jar after extraction.
		from(daemonProject.configurations.named("runtimeClasspath"))
		// Compose compiler plugin, version-matched to the daemon's compiler; the stable
		// name is the contract EnvironmentQuickBuildPaths.composeCompilerPlugin reads.
		from(daemonProject.configurations.named("composeCompilerPlugin")) {
			rename { "compose-compiler-plugin.jar" }
		}
	}

androidComponents.onVariants { variant ->
	val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)

	val copyRuntimeAar =
		tasks.register<AddFileToAssetsTask>("copy${variantName}QuickBuildRuntimeAar") {
			val runtimeProject = rootProject.project(":quickbuild:runtime")
			// Use findByName to avoid throwing when the included build doesn't expose this task
			runtimeProject.tasks.findByName("assembleRelease")?.let { dependsOn(it) }
			inputFile.set(
				runtimeProject.layout.buildDirectory.file(
					"outputs/aar/quickbuild-runtime-release.aar",
				),
			)
			baseAssetsPath.set("data/common")
			// Stable asset name: the stager doesn't care how the runtime AAR was built.
			fileName.set("quickbuild-runtime.aar")
		}
	variant.sources.assets?.addGeneratedSourceDirectory(
		copyRuntimeAar,
		AddFileToAssetsTask::outputDirectory,
	)

	val copyDaemonZip =
		tasks.register<AddFileToAssetsTask>("copy${variantName}QuickBuildDaemonZip") {
			dependsOn(quickBuildDaemonZip)
			inputFile.set(quickBuildDaemonZip.flatMap { it.archiveFile })
			baseAssetsPath.set("data/common")
		}
	variant.sources.assets?.addGeneratedSourceDirectory(
		copyDaemonZip,
		AddFileToAssetsTask::outputDirectory,
	)
}

// Packages the on-device installer payload (assets-<arch>.zip) consumed by
// SplitAssetsInstaller on debug builds. Entry names must match the installer
// contract in AssetsInstallationHelper.expectedEntries.
fun createAssetsZip(arch: String) {
	val outputDir =
		project.layout.buildDirectory
			.dir("outputs/assets")
			.get()
			.asFile
	if (!outputDir.exists()) {
		outputDir.mkdirs()
	}
	val zipFile = outputDir.resolve("assets-$arch.zip")

	val sourceDir = project.rootDir.resolve("assets")
	val bootstrapName = "bootstrap-$arch.zip"
	val androidSdkName = "android-sdk-$arch.zip"
	ZipOutputStream(zipFile.outputStream()).use { zipOut ->
		val requiredFiles = arrayOf(
			androidSdkName,
			"localMvnRepository.zip",
			"$GRADLE_DISTRIBUTION_ARCHIVE_NAME",
			"$GRADLE_API_NAME_JAR_ZIP",
			"documentation.db",
			bootstrapName,
			"plugin-artifacts.zip",
			"plugin-maven-repo.zip",
			"core.cgt",
		)

		for (fileName in requiredFiles) {
			val filePath = sourceDir.resolve(fileName)
			if (!filePath.exists()) {
				if (isCiCd) {
					project.logger.lifecycle("CI: skipping missing asset $fileName (expected ${filePath.absolutePath})")
					continue
				} else {
					throw FileNotFoundException(filePath.absolutePath)
				}
			}

			project.logger.lifecycle("Zipping $fileName from ${filePath.absolutePath}")
			val entryName =
				when (fileName) {
					bootstrapName -> "bootstrap.zip"
					androidSdkName -> "android-sdk.zip"
					else -> fileName
				}

			zipOut.putNextEntry(ZipEntry(entryName))
			filePath.inputStream().use { input -> input.copyTo(zipOut) }
			zipOut.closeEntry()
		}
	}
	project.logger.lifecycle("Created ${zipFile.name} at ${zipFile.parentFile.absolutePath}")
}

tasks.register("assembleV8Assets") {
	dependsOn("createPluginArtifactsZip")
	dependsOn("createPluginMavenRepoZip")
	if (!isCiCd) {
		dependsOn("assetsDownloadDebug")
	}
	doLast {
		createAssetsZip("arm64-v8a")
	}
}

tasks.register("assembleV7Assets") {
	dependsOn("createPluginArtifactsZip")
	dependsOn("createPluginMavenRepoZip")
	if (!isCiCd) {
		dependsOn("assetsDownloadDebug")
	}
	doLast {
		createAssetsZip("armeabi-v7a")
	}
}

tasks.register("assembleAssets") {
	dependsOn("assembleV8Assets", "assembleV7Assets")
}

tasks.register("recompressApk") {
	doLast {
		// abi/buildName are set by the assemble finalizers below; the project
		// properties allow a standalone run against an already-built APK:
		//   ./gradlew :app:recompressApk -PrecompressAbi=v8 -PrecompressBuildName=release
		val extraProps = extensions.extraProperties
		val abi: String =
			if (extraProps.has("abi")) {
				extraProps["abi"].toString()
			} else {
				project.findProperty("recompressAbi")?.toString()
					?: error("recompressApk: set -PrecompressAbi=v7|v8")
			}
		val buildName: String =
			if (extraProps.has("buildName")) {
				extraProps["buildName"].toString()
			} else {
				project.findProperty("recompressBuildName")?.toString()
					?: error("recompressApk: set -PrecompressBuildName=debug|release")
			}
		val noCompressExtensions =
			if (extraProps.has("noCompressExtensions")) {
				@Suppress("UNCHECKED_CAST")
				(extraProps["noCompressExtensions"] as? Set<String>).orEmpty()
			} else if (buildName == "debug") {
				noCompressDebug
			} else {
				noCompressRelease
			}

		project.logger.lifecycle("Calling recompressApk abi:$abi buildName:$buildName")

		val start = System.nanoTime()
		recompressApk(abi, buildName, noCompressExtensions)
		val durationMs = "%.2f".format((System.nanoTime() - start) / 1_000_000.0)

		project.logger.lifecycle("recompressApk completed in ${durationMs}ms")
	}
}

val noCompress =
	setOf(
		"so",
		"ogg",
		"mp3",
		"mp4",
		"zip",
		"jar",
		"ttf",
		"otf",
		"br",
		"tflite",
		"binarypb",
		"bincfg",
		"conv_model",
		"lstm_model",
	)

// Debug APKs DEFLATE jar assets (tooling-api-all.jar, cogo-plugin.jar) for
// ~75% size reduction (ADFA-4188). Release keeps jars STORED because
// tooling-api-all.jar ships as a brotli-encoded .jar.br.
//
// "so" stays DEFLATED in both lists (ADFA-2306 release, ADFA-4729 CI debug): the app
// manifest hard-codes extractNativeLibs="true", so the installer extracts libs to
// nativeLibraryDir at install time and AGP already packages them deflate-compressed
// (~5.9 MB smaller per APK). Re-storing them here would silently undo that, which is
// exactly what happened to CI debug APKs before ADFA-4729. Local debug builds never
// run this task and get AGP's deflated packaging as-is.
val noCompressDebug = noCompress - "jar" - "so"
val noCompressRelease = noCompress - "so"

afterEvaluate {
	// Use non-throwing task matching so configuration doesn't fail if assemble tasks are absent
	tasks.matching { it.name == "assembleRelease" }.configureEach {
		finalizedBy("recompressApk")

		doLast {
			tasks.named("recompressApk").configure {
				extensions.extraProperties["abi"] = "v8"
				extensions.extraProperties["buildName"] = "release"
				extensions.extraProperties["noCompressExtensions"] = noCompressRelease
			}
		}

		if (!isCiCd) {
			dependsOn("assetsDownloadRelease")
		}
	}

	tasks.matching { it.name == "assembleDebug" }.configureEach {
		if (isCiCd) {
			finalizedBy("recompressApk")
		}

		doLast {
			if (isCiCd) {
				tasks.named("recompressApk").configure {
					extensions.extraProperties["abi"] = "v8"
					extensions.extraProperties["buildName"] = "debug"
					extensions.extraProperties["noCompressExtensions"] = noCompressDebug
				}
			}
		}

		if (!isCiCd) {
			dependsOn("assetsDownloadDebug")
		}
	}
}

@Suppress("UNUSED_PARAMETER")
fun recompressApk(
	abi: String,
	buildName: String,
	noCompressExtensions: Set<String>,
) {
	project.logger.lifecycle("Recompressing APK with exclusions: $noCompressExtensions")

	val apkDir: File =
		layout.buildDirectory
			.dir("outputs/apk/$buildName")
			.get()
			.asFile
	project.logger.lifecycle("Recompressing APK Dir: ${apkDir.path}")

	// advzip runs for release APKs and on CI (including CI debug); a dev
	// invoking recompressApk locally against a debug APK skips it. Local debug
	// assembles never run this task at all (ADFA-1167).
	val apkFile =
		File(apkDir, "${rootProject.name}-$buildName.apk")
	project.logger.lifecycle("Recompressing APK: ${apkFile.absolutePath}")

	val buf = ByteArray(32 * 1024)

	var len = 0

	FileOutputStream(apkFile).use { fos ->
		BufferedOutputStream(fos).use { bos ->
			ZipOutputStream(bos).use { zipOut ->
				val crc = CRC32()
				for (file in apkDir.listFiles().orEmpty()) {
					val name = file.name
					project.logger.lifecycle("Considering $name")
					if (name.endsWith(".apk").not()) continue
					if (noCompressExtensions.any { name.endsWith(".$it") }) {
						// store
						val bytes = file.readBytes()
						crc.reset(); crc.update(bytes); val size = bytes.size
						val entry = ZipEntry(name)
						entry.size = size.toLong()
						entry.crc = crc.value
						entry.method = ZipEntry.STORED
						zipOut.putNextEntry(entry)
						zipOut.write(bytes)
						zipOut.closeEntry()
					} else {
						// deflate
						FileInputStream(file).use { fis ->
							val entry = ZipEntry(name)
							entry.method = ZipEntry.DEFLATED
							zipOut.putNextEntry(entry)
							while (fis.read(buf).also { len = it } > 0) {
								zipOut.write(buf, 0, len)
							}
							zipOut.closeEntry()
						}
					}
				}
			}
		}
	}
}
