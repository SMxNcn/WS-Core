plugins {
    kotlin("jvm") version "2.3.10"
}

group = "top.nckim"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.java-websocket:Java-WebSocket:1.5.6")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    from(configurations.runtimeClasspath.get().filter {
        it.name.contains("okhttp") || it.name.contains("okio")
    }.map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}