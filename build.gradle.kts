import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("com.android.application") version "9.3.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
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
                "dashchan-redacted/client/rework/update/data-v1.json\"",
        )
        buildConfigField(
            "String",
            "URI_UPDATES_EXTENSIONS",
            "\"//raw.githubusercontent.com/" +
                "dashchan-redacted/extensions/rework/update/data-v1.json\"",
        )
        buildConfigField(
            "String",
            "URI_THEMES",
            "\"//raw.githubusercontent.com/" +
                "dashchan-redacted/client/rework/update/themes.json\"",
        )
        buildConfigField("String", "GITHUB_URI_METADATA", "\"//github.com/dashchan-redacted/client\"")
        buildConfigField("String", "GITHUB_PATH_METADATA", "\"metadata\"")
    }

    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        // No `java.srcDirs` here: src/ is Kotlin-only since the J2K conversion. AIDL still
        // generates Java, but AGP registers those generated sources itself.
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
            versionNameSuffix = "-" +
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
        // AppCompatCustomView: the app runs on framework widgets and framework themes
        // (FragmentActivity + setActionBar + ?android:attr styles) with ThemeEngine doing the
        // tinting, so custom views deliberately extend the platform classes, not the AppCompat ones.
        disable += setOf("MissingTranslation", "ResourceType", "AppCompatCustomView")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ktlint {
    version = "1.8.0"
    // Formatting rules live in .editorconfig (that is what turns trailing commas on),
    // so there is deliberately no rule configuration here.
    android = true
    ignoreFailures = false
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

// ktlint has no JSON rules, so JSON rides its two entry points instead of growing its own:
// `ktlintFormat` pretty-prints it, `ktlintCheck` -- and therefore `check` and the pre-commit
// hook -- fails on it. The formatter is `jq --indent 4 .`, whose output reproduces the
// .editorconfig [*.json] style (4 spaces, LF, final newline) byte for byte, so there is no
// post-processing and no second style definition to drift out of sync. Invalid JSON fails
// the same task, which makes this a syntax gate on the theme repo and update manifests too.
abstract class JsonFormatTask : DefaultTask() {
    @get:InputFiles
    abstract val jsonFiles: ConfigurableFileCollection

    /** Report and fail rather than rewrite -- the only difference between check and format. */
    @get:Input
    abstract val checkOnly: Property<Boolean>

    @TaskAction
    fun format() {
        val unformatted = mutableListOf<String>()
        for (file in jsonFiles.files.sortedBy { it.invariantSeparatorsPath }) {
            val process =
                try {
                    ProcessBuilder("jq", "--indent", "4", ".", file.path).start()
                } catch (e: java.io.IOException) {
                    throw GradleException("jq is required to format JSON (brew install jq)", e)
                }
            val formatted = process.inputStream.use { it.readBytes() }
            val errors =
                process.errorStream
                    .use { it.readBytes() }
                    .decodeToString()
                    .trim()
            if (process.waitFor() != 0) {
                throw GradleException("${file.name} is not valid JSON: $errors")
            }
            if (formatted.contentEquals(file.readBytes())) {
                continue
            }
            if (checkOnly.get()) {
                unformatted += file.invariantSeparatorsPath
            } else {
                file.writeBytes(formatted)
                logger.lifecycle("jsonFormat: reformatted ${file.invariantSeparatorsPath}")
            }
        }
        if (unformatted.isNotEmpty()) {
            throw GradleException(
                unformatted.joinToString(
                    prefix = "not jq-formatted (fix with ./gradlew jsonFormat):\n  ",
                    separator = "\n  ",
                ),
            )
        }
    }
}

val jsonSources =
    fileTree(layout.projectDirectory) {
        include("**/*.json")
        // Build output, plus the dot-directories of Gradle, the IDE and the agent worktrees.
        exclude("build/**", "**/.*/**")
    }

val jsonFormat =
    tasks.register<JsonFormatTask>("jsonFormat") {
        description = "Pretty-prints every JSON file with jq."
        group = "formatting"
        jsonFiles.from(jsonSources)
        checkOnly = false
    }

val jsonCheck =
    tasks.register<JsonFormatTask>("jsonCheck") {
        description = "Fails on JSON that jq would reformat, or that does not parse."
        group = "verification"
        jsonFiles.from(jsonSources)
        checkOnly = true
    }

tasks.named("ktlintFormat") {
    finalizedBy(jsonFormat)
}

tasks.named("ktlintCheck") {
    dependsOn(jsonCheck)
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
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.1")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.brotli:dec:0.1.2")
}
