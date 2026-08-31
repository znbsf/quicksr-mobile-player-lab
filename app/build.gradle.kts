import java.security.MessageDigest

plugins {
    id("com.android.application")
}

val lockedModelName = "quicksrnet-small-2x-opset17.onnx"
val lockedModelBytes = 93994L
val lockedModelSha256 = "3db92151af52808135024faf6abdec69e75ca13b5112b6521a9681a27c63f6ce"
val coreModelName = "quicksrnet-small-2x-fixed64-core.onnx"
val coreModelBytes = 93809L
val coreModelSha256 = "9a35f235ac9dc36447764a58a2d1511720dc346360f76b77fee490b347f9e3b6"
val dcrModelName = "quicksrnet-small-2x-fixed64-dcr.onnx"
val dcrModelBytes = 93923L
val dcrModelSha256 = "c902565d3ec55de1fbfa66aac8e283890c7b77eab0e39c60ba35022691148a5f"
val derivedModelsManifestBytes = 15614L
val derivedModelsManifestSha256 = "8ed648d623a15acb0cf42dac00622a492a23b6ff223b04de24cd9c8f33d03430"
val frozenPlanSha256 = "44852e9245c46959af438b64dff75db3489f09ac94ac3913277af9d361a00859"
val qnnPlanSha256 = "2b7b888ba95949c92d3ea6df0852bb07fb4ef890227d0ed1842c795aed49af86"
val p4PlanSha256 = "90d05b4cf9837a8a421fc35d144847a4cd727dcf39c8ca50f85aa128104a2fa8"
val ortDependencyVersion = "1.26.0"
val qnnPluginVersion = "2.5.0"
val qnnRuntimeVersion = "2.49.0"
val generatedModelAssets = layout.buildDirectory.dir("generated/quicksrModelAssets")
val generatedModelFile = generatedModelAssets.map { it.file(lockedModelName) }
val generatedCoreModelFile = generatedModelAssets.map { it.file(coreModelName) }
val generatedDcrModelFile = generatedModelAssets.map { it.file(dcrModelName) }

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sourceIdentity(files: Collection<File>, baseDirectory: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.relativeTo(baseDirectory).invariantSeparatorsPath }.forEach { file ->
        val relativePath = file.relativeTo(baseDirectory).invariantSeparatorsPath
        digest.update(relativePath.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val sourceIdentityFiles = fileTree("src/main") {
    include("**/*.java", "**/*.xml")
}.files + setOf(
    project.file("build.gradle.kts"),
    rootProject.file("prototype-plan.json"),
    rootProject.file("prototype-plan-p1.json"),
    rootProject.file("prototype-plan-p2.json"),
    rootProject.file("prototype-plan-p3-qnn.json"),
    rootProject.file("contracts/p4-real-image-roi-plan.json"),
    rootProject.file("derived-models/derivation-manifest.json"),
    rootProject.file("derived-models/derive_quicksrnet_fixed64.py")
)
val appSourceSha256 = sourceIdentity(sourceIdentityFiles, rootProject.projectDir)
val prototypeBuildId = providers.gradleProperty("prototypeBuildId").orElse("manual-unlinked").get()

val prepareQuickSrModel by tasks.registering {
    group = "prototype"
    description = "Fail-closed verification and build-local staging of the locked QuickSRNetSmall 2x ONNX file."

    val defaultModel = rootProject.file("models/$lockedModelName")
    val sourceModel = providers.gradleProperty("quickSrModelPath")
        .map { rootProject.file(it) }
        .orElse(defaultModel)
    val coreModel = rootProject.file("derived-models/$coreModelName")
    val dcrModel = rootProject.file("derived-models/$dcrModelName")
    val derivedManifest = rootProject.file("derived-models/derivation-manifest.json")

    inputs.files(sourceModel, coreModel, dcrModel, derivedManifest)
    outputs.files(generatedModelFile, generatedCoreModelFile, generatedDcrModelFile)
    outputs.upToDateWhen { false }

    doLast {
        fun verify(file: File, expectedBytes: Long, expectedSha256: String, label: String) {
            if (!file.isFile) {
                throw GradleException("Locked $label is missing: ${file.absolutePath}")
            }
            if (file.length() != expectedBytes) {
                throw GradleException(
                    "Locked $label byte mismatch: expected $expectedBytes, observed ${file.length()} at ${file.absolutePath}"
                )
            }
            val observedSha = sha256(file)
            if (!observedSha.equals(expectedSha256, ignoreCase = true)) {
                throw GradleException(
                    "Locked $label SHA-256 mismatch: expected $expectedSha256, observed $observedSha at ${file.absolutePath}"
                )
            }
        }

        fun verifyShaOnly(file: File, expectedSha256: String, label: String) {
            if (!file.isFile) {
                throw GradleException("Frozen $label is missing: ${file.absolutePath}")
            }
            val observedSha = sha256(file)
            if (!observedSha.equals(expectedSha256, ignoreCase = true)) {
                throw GradleException(
                    "Frozen $label SHA-256 mismatch: expected $expectedSha256, observed $observedSha at ${file.absolutePath}"
                )
            }
        }

        val source = sourceModel.get()
        verify(source, lockedModelBytes, lockedModelSha256, "canonical model")
        verify(coreModel, coreModelBytes, coreModelSha256, "fixed core model")
        verify(dcrModel, dcrModelBytes, dcrModelSha256, "fixed DCR model")
        verify(
            derivedManifest,
            derivedModelsManifestBytes,
            derivedModelsManifestSha256,
            "derived-model manifest"
        )
        verifyShaOnly(rootProject.file("prototype-plan-p2.json"), frozenPlanSha256, "P2 plan")
        verifyShaOnly(rootProject.file("prototype-plan-p3-qnn.json"), qnnPlanSha256, "P3 QNN plan")
        verifyShaOnly(
            rootProject.file("contracts/p4-real-image-roi-plan.json"),
            p4PlanSha256,
            "P4 real-image plan"
        )

        val targets = listOf(
            Triple(source, generatedModelFile.get().asFile, lockedModelSha256),
            Triple(coreModel, generatedCoreModelFile.get().asFile, coreModelSha256),
            Triple(dcrModel, generatedDcrModelFile.get().asFile, dcrModelSha256)
        )
        targets.forEach { (input, target, digest) ->
            target.parentFile.mkdirs()
            input.copyTo(target, overwrite = true)
            logger.lifecycle(
                "Verified and staged ${target.name} (${input.length()} bytes, SHA-256 $digest)"
            )
        }
    }
}

android {
    namespace = "dev.aisystems.quicksrplayerlab"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aisystems.quicksrplayerlab"
        minSdk = 27
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "android.app.Instrumentation"
        buildConfigField("String", "MODEL_SHA256", "\"$lockedModelSha256\"")
        buildConfigField("long", "MODEL_BYTES", "${lockedModelBytes}L")
        buildConfigField("String", "MODEL_FILE", "\"$lockedModelName\"")
        buildConfigField("String", "CORE_MODEL_SHA256", "\"$coreModelSha256\"")
        buildConfigField("long", "CORE_MODEL_BYTES", "${coreModelBytes}L")
        buildConfigField("String", "DCR_MODEL_SHA256", "\"$dcrModelSha256\"")
        buildConfigField("long", "DCR_MODEL_BYTES", "${dcrModelBytes}L")
        buildConfigField(
            "String",
            "DERIVED_MODELS_MANIFEST_SHA256",
            "\"$derivedModelsManifestSha256\""
        )
        buildConfigField("String", "PLAN_SHA256", "\"$frozenPlanSha256\"")
        buildConfigField("String", "QNN_PLAN_SHA256", "\"$qnnPlanSha256\"")
        buildConfigField("String", "P4_PLAN_SHA256", "\"$p4PlanSha256\"")
        buildConfigField("String", "ORT_DEPENDENCY_VERSION", "\"$ortDependencyVersion\"")
        buildConfigField("String", "QNN_PLUGIN_VERSION", "\"$qnnPluginVersion\"")
        buildConfigField("String", "QNN_RUNTIME_VERSION", "\"$qnnRuntimeVersion\"")
        buildConfigField("String", "APP_SOURCE_SHA256", "\"$appSourceSha256\"")
        buildConfigField("String", "PROTOTYPE_BUILD_ID", "\"$prototypeBuildId\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main").assets.srcDir(generatedModelAssets)
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareQuickSrModel)
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortDependencyVersion")
    implementation("com.qualcomm.qti:onnxruntime-android-qnn:$qnnPluginVersion")
    implementation("com.qualcomm.qti:qnn-runtime:$qnnRuntimeVersion")
    testImplementation("junit:junit:4.13.2")
}
