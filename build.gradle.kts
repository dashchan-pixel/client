import groovy.json.JsonSlurper
import java.util.Properties

plugins {
	id("com.android.application") version "9.2.1"
}

tasks.withType<JavaCompile>().configureEach {
	options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
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
		val versionsData = JsonSlurper().parseText(providers.fileContents(
				layout.projectDirectory.file("metadata/versions.json")).asText.get())
				as Map<String, List<Map<String, Any>>>
		val latestVersion = versionsData.getValue("versions")
				.maxByOrNull { (it.getValue("code") as Number).toLong() }!!
		versionCode = (latestVersion.getValue("code") as Number).toInt()
		versionName = latestVersion.getValue("name") as String

		minSdk = 36
		targetSdk = 36

		buildConfigField("String", "VERSION_DATE", "\"${latestVersion["date"]}\"")
		buildConfigField("String", "URI_UPDATES", "\"//raw.githubusercontent.com/" +
				"TrixiEther/DashchanFork/experimental/update/data.json\"")
		buildConfigField("String", "URI_THEMES", "\"//raw.githubusercontent.com/" +
				"TrixiEther/DashchanFork/experimental/update/themes.json\"")
		buildConfigField("String", "GITHUB_URI_METADATA", "\"//github.com/dashchan-pixel/meta\"")
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

	val locales = listOf(file("res"), file("lang"))
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

	val keystoreProperties = Properties().apply {
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
