plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    application
}

group = "pro.masterdoc"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    val ktor = "3.0.3"
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("pro.masterdoc.catalog.ApplicationKt") }

tasks.test { useJUnitPlatform() }
