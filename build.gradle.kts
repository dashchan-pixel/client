import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("com.android.application") version "9.2.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
}

kotlin {
    compilerOptions {
        // Any warning fails the build: an AI-generated patch cannot leave behind a
        // deprecation or unchecked-cast warning for a human to notice later.
        allWarningsAsErrors = true
        // Progressive mode: apply new-version semantics for deprecated constructs
        // immediately instead of keeping the lenient legacy behaviour.
        progressiveMode = true
        freeCompilerArgs.addAll(
            // Trust the @Nullable/@NonNull annotations on Java APIs (AndroidX, JSR-305)
            // instead of silently widening them to lenient platform types, so a null
            // coming out of Java is a compile error at the point of misuse.
            "-Xjsr305=strict",
            "-Xtype-enhancement-improvements-strict-mode",
            "-Xenhance-type-parameter-types-to-def-not-null",
        )
    }
}

android {
    namespace = "com.mishiranu.dashchan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mishiranu.dashchan"

        // metadata/versions.json is the single source of truth for the app version:
        // the newest entry there defines versionCode/versionName, so a release bump
        // is one atomic edit (versions.json + changelogs) and the build can never
        // disagree with the metadata. Read via providers.fileContents so the
        // configuration cache is invalidated when the file changes.
        @Suppress("UNCHECKED_CAST")
        val versionsData =
            JsonSlurper().parseText(
                providers.fileContents(layout.projectDirectory.file("metadata/versions.json")).asText.get(),
            )
                as Map<String, List<Map<String, Any>>>
        val latestVersion =
            versionsData
                .getValue("versions")
                .maxByOrNull { (it.getValue("code") as Number).toLong() }!!
        versionCode = (latestVersion.getValue("code") as Number).toInt()
        versionName = latestVersion.getValue("name") as String

        minSdk = 36
        targetSdk = 36

        buildConfigField("String", "VERSION_DATE", "\"${latestVersion["date"]}\"")
        // Client updates come from this repo itself; extension updates from a separate,
        // independently configurable source (see Preferences.getUriUpdatesExtensions).
        buildConfigField(
            "String",
            "URI_UPDATES",
            "\"//raw.githubusercontent.com/" +
                "dashchan-pixel/client/rework/update/data-v1.json\"",
        )
        buildConfigField(
            "String",
            "URI_UPDATES_EXTENSIONS",
            "\"//raw.githubusercontent.com/" +
                "TrixiEther/Dashchan-Meta/master/update/data.json\"",
        )
        buildConfigField(
            "String",
            "URI_THEMES",
            "\"//raw.githubusercontent.com/" +
                "dashchan-pixel/client/rework/update/themes.json\"",
        )
        buildConfigField("String", "GITHUB_URI_METADATA", "\"//github.com/dashchan-pixel/client\"")
        buildConfigField("String", "GITHUB_PATH_METADATA", "\"metadata\"")
    }

    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.setSrcDirs(listOf("src"))
        kotlin.setSrcDirs(listOf("src"))
        aidl.setSrcDirs(listOf("src"))
        res.setSrcDirs(listOf("res", "lang"))
        assets.setSrcDirs(listOf("assets"))
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    val locales =
        listOf(file("res"), file("lang"))
            .flatMap { (it.listFiles() ?: emptyArray()).asIterable() }
            .filter { it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .map { it.name.substringAfter('-') }
            .sorted()
    defaultConfig {
        buildConfigField("String[]", "LOCALES", "{\"${locales.joinToString("\", \"")}\"}")
    }
    androidResources {
        localeFilters += locales
    }

    val keystoreProperties =
        Properties().apply {
            val propsFile = file("keystore.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { load(it) }
            }
        }
    val storeFilePath = keystoreProperties.getProperty("store.file") ?: System.getenv("KEYSTORE_FILENAME")
    if (storeFilePath != null) {
        signingConfigs.create("release") {
            storeFile = file(storeFilePath)
            storePassword = keystoreProperties.getProperty("store.password") ?: System.getenv("KEYSTORE_PASSWORD")
            keyAlias = keystoreProperties.getProperty("key.alias") ?: System.getenv("RELEASE_SIGN_KEY_ALIAS")
            keyPassword = keystoreProperties.getProperty("key.password") ?: System.getenv("RELEASE_SIGN_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Tag test builds with the git revision so the installed version is identifiable.
            versionNameSuffix = "-r" +
                providers
                    .exec {
                        commandLine("git", "rev-parse", "--short", "HEAD")
                    }.standardOutput.asText
                    .get()
                    .trim()
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes += "META-INF/*.version"
    }

    lint {
        disable += setOf("MissingTranslation", "ResourceType")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ktlint {
    version = "1.6.0"
    // Formatting rules live in .editorconfig (that is what turns trailing commas on),
    // so there is deliberately no rule configuration here.
    android = true
    ignoreFailures = false
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$projectDir/detekt.yml"))
    // This project keeps sources in a flat `src/`, not the `src/main/kotlin` that detekt
    // assumes. Without this the detekt task resolves to NO-SOURCE and cheerfully reports
    // BUILD SUCCESSFUL while analysing nothing at all.
    source.setFrom(files("src"))
    // The legacy J2K output trips the strict rules thousands of times. The baseline
    // freezes those existing findings so the gate fails only on *newly written* code;
    // it is a shrinking debt ledger, not a permanent exemption.
    baseline = file("$projectDir/detekt-baseline.xml")
    ignoreFailures = false
    parallel = true
}

// The rules that actually catch Java-flavoured Kotlin -- UnsafeCallOnNullableType,
// CanBeNonNullable, VarCouldBeVal, NullableToStringCall -- are type-resolution rules.
// They report *nothing* unless detekt is handed a compile classpath, so this wiring is
// what makes the nullability half of the gate real rather than decorative.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = JavaVersion.VERSION_17.toString()
    reports {
        html.required = true
        txt.required = false
        sarif.required = false
        md.required = false
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = JavaVersion.VERSION_17.toString()
}

// Advisory '!!' pass: reports every double-bang but never fails the build. Kept separate
// because detekt 1.x fails on any finding within a config, so "warn but do not block"
// is not expressible as a severity -- it needs its own config and its own task.
val detektDoubleBang =
    tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektDoubleBang") {
        description = "Reports '!!' usages without failing the build."
        group = "verification"
        setSource(files("src"))
        config.setFrom(files("$projectDir/detekt-doublebang.yml"))
        buildUponDefaultConfig = false
        ignoreFailures = true
        parallel = true
        reports {
            html.required = false
            xml.required = false
            txt.required = false
            sarif.required = false
            md.required = false
        }
    }

// Deferred: AGP creates the per-variant `debugCompileClasspath` configuration inside its
// own afterEvaluate, so this cannot be resolved at the top level of the script. AGP 9 also
// dropped `android.bootClasspath` -- android.jar now comes from the SDK components provider.
afterEvaluate {
    val detektClasspath =
        files(
            configurations.getByName("debugCompileClasspath"),
            androidComponents.sdkComponents.bootClasspath,
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        )
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        classpath.setFrom(detektClasspath)
    }
    tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
        classpath.setFrom(detektClasspath)
    }
}

// `check` runs the strict gate; the advisory pass rides along so '!!' stays visible.
tasks.named("check") {
    dependsOn(detektDoubleBang)
}

dependencies {
    compileOnly("org.ccil.cowan.tagsoup:tagsoup:1.2.1")
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.0")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.brotli:dec:0.1.2")
}
