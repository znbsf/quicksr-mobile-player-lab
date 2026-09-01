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
val dcr256ModelName = "quicksrnet-small-2x-fixed256-dcr.onnx"
val dcr256ModelBytes = 93938L
val dcr256ModelSha256 = "f791fdc975862b2556eca8113cfb9139c0b3c32303c86ce85497298024ce89be"
val dcr256x144ModelName = "quicksrnet-small-2x-fixed256x144-dcr.onnx"
val dcr256x144ModelBytes = 93955L
val dcr256x144ModelSha256 = "1706078f92f19ab12aa91c932dbcbbdfb984e8d1167ef395d5a52286a03a9e4d"
val dcr512x288ModelName = "quicksrnet-small-2x-fixed512x288-dcr.onnx"
val dcr512x288ModelBytes = 93955L
val dcr512x288ModelSha256 = "79e6b64b28ba9abe4b140b9c5760eda702679ecaf347ce16950f5fe56b3a5629"
val dcr640x360ModelName = "quicksrnet-small-2x-fixed640x360-dcr.onnx"
val dcr640x360ModelBytes = 93955L
val dcr640x360ModelSha256 = "ad7634d8bd831370c018c2570475cbe71b7f136ccd4da860c509f4619d0c42c1"
val fixed640x3603xModelName = "quicksrnet-small-3x-fixed640x360.onnx"
val fixed640x3603xModelBytes = 111296L
val fixed640x3603xModelSha256 = "c03d551eec48f4d419290ba774164102cab964a2a65576f8d78a24a42013b077"
val fixed640x3604xModelName = "quicksrnet-small-4x-fixed640x360.onnx"
val fixed640x3604xModelBytes = 135573L
val fixed640x3604xModelSha256 = "ca3afce1aaad216e30297b0ffce608304cd66e394314c4090a659a963f2f05e2"
val dcr512ModelName = "quicksrnet-small-2x-fixed512-dcr.onnx"
val dcr512ModelBytes = 94039L
val dcr512ModelSha256 = "d77f22e6a94274ecb66d3ad2fff389381a714d9bd2c90d209b8070f6f7cada34"
val derivedModelsManifestBytes = 15614L
val derivedModelsManifestSha256 = "8ed648d623a15acb0cf42dac00622a492a23b6ff223b04de24cd9c8f33d03430"
val derived256ManifestBytes = 5799L
val derived256ManifestSha256 = "c7e20ebf21f87b08368f0c6fbaf8fa6a0e0b6287ce6ff816a9917bf7a7cd117f"
val derived256x144ManifestBytes = 5738L
val derived256x144ManifestSha256 = "d184764cc605e932595c6319c46d8792520270095c630aab6db099c58b3f8e79"
val derived512x288ManifestBytes = 5744L
val derived512x288ManifestSha256 = "6ba5837eddfca1627a4fca908546ec0bcf36e0602d196e21fb6da01dfc3239f5"
val derived640x360ManifestBytes = 5744L
val derived640x360ManifestSha256 = "4be16e9ba0e6235b9af32770715c8f6329f99bb6a1599cc945ff52650c7f3c8e"
val derived512ManifestBytes = 6214L
val derived512ManifestSha256 = "4f46c9e8a61e6402dbf1e2f5c2b3a7c37339e2187affa4393cc531b716d07a98"
val frozenPlanSha256 = "44852e9245c46959af438b64dff75db3489f09ac94ac3913277af9d361a00859"
val qnnPlanSha256 = "2b7b888ba95949c92d3ea6df0852bb07fb4ef890227d0ed1842c795aed49af86"
val p4PlanSha256 = "90d05b4cf9837a8a421fc35d144847a4cd727dcf39c8ca50f85aa128104a2fa8"
val ortDependencyVersion = "1.26.0"
val qnnPluginVersion = "2.5.0"
val qnnRuntimeVersion = "2.49.0"
val targetAbi = providers.gradleProperty("targetAbi").orElse("arm64-v8a").get()
require(targetAbi in setOf("arm64-v8a", "x86_64")) {
    "targetAbi must be arm64-v8a or x86_64, observed: $targetAbi"
}
val generatedModelAssets = layout.buildDirectory.dir("generated/quicksrModelAssets")
val generatedModelFile = generatedModelAssets.map { it.file(lockedModelName) }
val generatedCoreModelFile = generatedModelAssets.map { it.file(coreModelName) }
val generatedDcrModelFile = generatedModelAssets.map { it.file(dcrModelName) }
val generatedDcr256ModelFile = generatedModelAssets.map { it.file(dcr256ModelName) }
val generatedDcr256x144ModelFile = generatedModelAssets.map { it.file(dcr256x144ModelName) }
val generatedDcr512x288ModelFile = generatedModelAssets.map { it.file(dcr512x288ModelName) }
val generatedDcr640x360ModelFile = generatedModelAssets.map { it.file(dcr640x360ModelName) }
val generatedFixed640x3603xModelFile = generatedModelAssets.map { it.file(fixed640x3603xModelName) }
val generatedFixed640x3604xModelFile = generatedModelAssets.map { it.file(fixed640x3604xModelName) }
val generatedDcr512ModelFile = generatedModelAssets.map { it.file(dcr512ModelName) }

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
    rootProject.file("derived-models/derive_quicksrnet_fixed64.py"),
    rootProject.file("derived-models/derivation-manifest-fixed256.json"),
    rootProject.file("derived-models/derive_quicksrnet_fixed256.py"),
    rootProject.file("derived-models/derivation-manifest-fixed256x144.json"),
    rootProject.file("derived-models/derive_quicksrnet_fixed256x144.py"),
    rootProject.file("derived-models/derivation-manifest-fixed512x288.json"),
    rootProject.file("derived-models/derive_quicksrnet_fixed512x288.py"),
    rootProject.file("derived-models/derivation-manifest-fixed640x360.json"),
    rootProject.file("derived-models/derive_quicksrnet_fixed640x360.py"),
    rootProject.file("derived-models/derivation-manifest-fixed512.json"),
    rootProject.file("derived-models/derive_quicksrnet_fixed512.py")
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
    val dcr256Model = rootProject.file("derived-models/$dcr256ModelName")
    val dcr256x144Model = rootProject.file("derived-models/$dcr256x144ModelName")
    val dcr512x288Model = rootProject.file("derived-models/$dcr512x288ModelName")
    val dcr640x360Model = rootProject.file("derived-models/$dcr640x360ModelName")
    val fixed640x3603xModel = rootProject.file("derived-models/$fixed640x3603xModelName")
    val fixed640x3604xModel = rootProject.file("derived-models/$fixed640x3604xModelName")
    val dcr512Model = rootProject.file("derived-models/$dcr512ModelName")
    val derivedManifest = rootProject.file("derived-models/derivation-manifest.json")
    val derived256Manifest = rootProject.file("derived-models/derivation-manifest-fixed256.json")
    val derived256x144Manifest = rootProject.file("derived-models/derivation-manifest-fixed256x144.json")
    val derived512x288Manifest = rootProject.file("derived-models/derivation-manifest-fixed512x288.json")
    val derived640x360Manifest = rootProject.file("derived-models/derivation-manifest-fixed640x360.json")
    val derived512Manifest = rootProject.file("derived-models/derivation-manifest-fixed512.json")

    inputs.files(
        sourceModel,
        coreModel,
        dcrModel,
        dcr256Model,
        dcr256x144Model,
        dcr512x288Model,
        dcr640x360Model,
        fixed640x3603xModel,
        fixed640x3604xModel,
        dcr512Model,
        derivedManifest,
        derived256Manifest,
        derived256x144Manifest,
        derived512x288Manifest,
        derived640x360Manifest,
        derived512Manifest
    )
    outputs.files(
        generatedModelFile,
        generatedCoreModelFile,
        generatedDcrModelFile,
        generatedDcr256ModelFile,
        generatedDcr256x144ModelFile,
        generatedDcr512x288ModelFile,
        generatedDcr640x360ModelFile,
        generatedFixed640x3603xModelFile,
        generatedFixed640x3604xModelFile,
        generatedDcr512ModelFile
    )
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
        verify(dcr256Model, dcr256ModelBytes, dcr256ModelSha256, "fixed256 DCR model")
        verify(
            dcr256x144Model,
            dcr256x144ModelBytes,
            dcr256x144ModelSha256,
            "fixed256x144 DCR model"
        )
        verify(
            dcr512x288Model,
            dcr512x288ModelBytes,
            dcr512x288ModelSha256,
            "fixed512x288 DCR model"
        )
        verify(
            dcr640x360Model,
            dcr640x360ModelBytes,
            dcr640x360ModelSha256,
            "fixed640x360 DCR model"
        )
        verify(
            fixed640x3603xModel,
            fixed640x3603xModelBytes,
            fixed640x3603xModelSha256,
            "fixed640x360 3x model"
        )
        verify(
            fixed640x3604xModel,
            fixed640x3604xModelBytes,
            fixed640x3604xModelSha256,
            "fixed640x360 4x model"
        )
        verify(dcr512Model, dcr512ModelBytes, dcr512ModelSha256, "fixed512 DCR model")
        verify(
            derivedManifest,
            derivedModelsManifestBytes,
            derivedModelsManifestSha256,
            "derived-model manifest"
        )
        verify(
            derived256Manifest,
            derived256ManifestBytes,
            derived256ManifestSha256,
            "fixed256 derived-model manifest"
        )
        verify(
            derived256x144Manifest,
            derived256x144ManifestBytes,
            derived256x144ManifestSha256,
            "fixed256x144 derived-model manifest"
        )
        verify(
            derived512x288Manifest,
            derived512x288ManifestBytes,
            derived512x288ManifestSha256,
            "fixed512x288 derived-model manifest"
        )
        verify(
            derived640x360Manifest,
            derived640x360ManifestBytes,
            derived640x360ManifestSha256,
            "fixed640x360 derived-model manifest"
        )
        verify(
            derived512Manifest,
            derived512ManifestBytes,
            derived512ManifestSha256,
            "fixed512 derived-model manifest"
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
            Triple(dcrModel, generatedDcrModelFile.get().asFile, dcrModelSha256),
            Triple(dcr256Model, generatedDcr256ModelFile.get().asFile, dcr256ModelSha256),
            Triple(
                dcr256x144Model,
                generatedDcr256x144ModelFile.get().asFile,
                dcr256x144ModelSha256
            ),
            Triple(
                dcr512x288Model,
                generatedDcr512x288ModelFile.get().asFile,
                dcr512x288ModelSha256
            ),
            Triple(
                dcr640x360Model,
                generatedDcr640x360ModelFile.get().asFile,
                dcr640x360ModelSha256
            ),
            Triple(
                fixed640x3603xModel,
                generatedFixed640x3603xModelFile.get().asFile,
                fixed640x3603xModelSha256
            ),
            Triple(
                fixed640x3604xModel,
                generatedFixed640x3604xModelFile.get().asFile,
                fixed640x3604xModelSha256
            ),
            Triple(dcr512Model, generatedDcr512ModelFile.get().asFile, dcr512ModelSha256)
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
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aisystems.quicksrplayerlab"
        minSdk = 27
        targetSdk = 35
        versionCode = 18
        versionName = "0.14.0"

        ndk {
            abiFilters += targetAbi
        }

        testInstrumentationRunner = "android.app.Instrumentation"
        buildConfigField("String", "MODEL_SHA256", "\"$lockedModelSha256\"")
        buildConfigField("long", "MODEL_BYTES", "${lockedModelBytes}L")
        buildConfigField("String", "MODEL_FILE", "\"$lockedModelName\"")
        buildConfigField("String", "CORE_MODEL_SHA256", "\"$coreModelSha256\"")
        buildConfigField("long", "CORE_MODEL_BYTES", "${coreModelBytes}L")
        buildConfigField("String", "DCR_MODEL_SHA256", "\"$dcrModelSha256\"")
        buildConfigField("long", "DCR_MODEL_BYTES", "${dcrModelBytes}L")
        buildConfigField("String", "DCR256_MODEL_FILE", "\"$dcr256ModelName\"")
        buildConfigField("String", "DCR256_MODEL_SHA256", "\"$dcr256ModelSha256\"")
        buildConfigField("long", "DCR256_MODEL_BYTES", "${dcr256ModelBytes}L")
        buildConfigField("String", "DCR256X144_MODEL_FILE", "\"$dcr256x144ModelName\"")
        buildConfigField("String", "DCR256X144_MODEL_SHA256", "\"$dcr256x144ModelSha256\"")
        buildConfigField("long", "DCR256X144_MODEL_BYTES", "${dcr256x144ModelBytes}L")
        buildConfigField("String", "DCR512X288_MODEL_FILE", "\"$dcr512x288ModelName\"")
        buildConfigField("String", "DCR512X288_MODEL_SHA256", "\"$dcr512x288ModelSha256\"")
        buildConfigField("long", "DCR512X288_MODEL_BYTES", "${dcr512x288ModelBytes}L")
        buildConfigField("String", "DCR640X360_MODEL_FILE", "\"$dcr640x360ModelName\"")
        buildConfigField("String", "DCR640X360_MODEL_SHA256", "\"$dcr640x360ModelSha256\"")
        buildConfigField("long", "DCR640X360_MODEL_BYTES", "${dcr640x360ModelBytes}L")
        buildConfigField("String", "FIXED640X360_3X_MODEL_FILE", "\"$fixed640x3603xModelName\"")
        buildConfigField("String", "FIXED640X360_3X_MODEL_SHA256", "\"$fixed640x3603xModelSha256\"")
        buildConfigField("long", "FIXED640X360_3X_MODEL_BYTES", "${fixed640x3603xModelBytes}L")
        buildConfigField("String", "FIXED640X360_4X_MODEL_FILE", "\"$fixed640x3604xModelName\"")
        buildConfigField("String", "FIXED640X360_4X_MODEL_SHA256", "\"$fixed640x3604xModelSha256\"")
        buildConfigField("long", "FIXED640X360_4X_MODEL_BYTES", "${fixed640x3604xModelBytes}L")
        buildConfigField("String", "DCR512_MODEL_FILE", "\"$dcr512ModelName\"")
        buildConfigField("String", "DCR512_MODEL_SHA256", "\"$dcr512ModelSha256\"")
        buildConfigField("long", "DCR512_MODEL_BYTES", "${dcr512ModelBytes}L")
        buildConfigField(
            "String",
            "DERIVED_MODELS_MANIFEST_SHA256",
            "\"$derivedModelsManifestSha256\""
        )
        buildConfigField(
            "String",
            "DERIVED256_MANIFEST_SHA256",
            "\"$derived256ManifestSha256\""
        )
        buildConfigField(
            "String",
            "DERIVED512_MANIFEST_SHA256",
            "\"$derived512ManifestSha256\""
        )
        buildConfigField("String", "PLAN_SHA256", "\"$frozenPlanSha256\"")
        buildConfigField("String", "QNN_PLAN_SHA256", "\"$qnnPlanSha256\"")
        buildConfigField("String", "P4_PLAN_SHA256", "\"$p4PlanSha256\"")
        buildConfigField("String", "ORT_DEPENDENCY_VERSION", "\"$ortDependencyVersion\"")
        buildConfigField("String", "QNN_PLUGIN_VERSION", "\"$qnnPluginVersion\"")
        buildConfigField("String", "QNN_RUNTIME_VERSION", "\"$qnnRuntimeVersion\"")
        buildConfigField("String", "TARGET_ABI", "\"$targetAbi\"")
        buildConfigField("boolean", "QNN_RUNTIME_EXPECTED", (targetAbi == "arm64-v8a").toString())
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
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("androidx.media3:media3-effect:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
