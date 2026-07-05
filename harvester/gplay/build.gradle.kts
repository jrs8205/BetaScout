plugins {
    kotlin("jvm") version "2.1.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.auroraoss:gplayapi:3.4.2")
}

application {
    mainClass.set("MainKt")
}
