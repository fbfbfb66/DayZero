plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.google.devtools.ksp)
}

fun readEnvValue(name: String): String {
  val candidates = listOf(rootProject.file(".env"), rootProject.file(".env.example"))
  val line = candidates.asSequence()
    .filter { it.exists() }
    .flatMap { it.readLines().asSequence() }
    .map { it.trim() }
    .firstOrNull { it.startsWith("$name=") }
    ?: return ""
  return line.substringAfter("=").trim().trim('"')
}

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
  namespace = "com.example.core.network"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
    buildConfigField("String", "SUPABASE_URL", quoted(readEnvValue("SUPABASE_URL")))
    buildConfigField("String", "SUPABASE_ANON_KEY", quoted(readEnvValue("SUPABASE_ANON_KEY")))
  }

  buildFeatures {
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    isCoreLibraryDesugaringEnabled = true
  }
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  implementation(project(":core:model"))
  implementation(project(":core:domain"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.converter.moshi)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  "ksp"(libs.moshi.kotlin.codegen)
}
