plugins {
    kotlin("jvm") version "2.3.10"
}

group = "top.nckim"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.6") {
        exclude("org.slf4j", "slf4j-api")
    }

    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
    testImplementation(kotlin("test"))
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
    val runtimeClasspath = configurations["runtimeClasspath"]
    from(provider { runtimeClasspath.files.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}