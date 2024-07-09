import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("dev.clojurephant.clojure")
    `maven-publish`
    signing
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
}

publishing {
    publications.create("maven", MavenPublication::class) {
        pom {
            name.set("XTDB Cloudwatch Metrics")
            description.set("XTDB Cloudwatch Metrics")
        }
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

dependencies {
    api(project(":xtdb-api"))
    api(project(":xtdb-core"))

    api("io.micrometer", "micrometer-registry-cloudwatch2", "1.12.2")
    api("software.amazon.awssdk", "cloudwatch", "2.25.50")

    api(kotlin("stdlib-jdk8"))
}
