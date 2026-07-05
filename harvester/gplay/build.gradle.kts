plugins {
    kotlin("jvm") version "2.1.21"
    application
}

repositories {
    mavenCentral()
}

// gplayapi ships only as an Android library, so we consume its extracted
// classes.jar directly and supply tiny android.* stubs (see src/main/java/android).
// Transitive deps mirror gplayapi's POM, with coroutines-core instead of -android.
dependencies {
    implementation(files("libs/gplayapi-3.4.2.jar"))
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.0.20")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.protobuf:protobuf-javalite:4.28.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

application {
    mainClass.set("MainKt")
}
