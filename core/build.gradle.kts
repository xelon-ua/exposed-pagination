/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

plugins {
    id("kotlin-library")
    id("maven-publish")
}

group = project.properties["group"] as String
version = project.properties["version"] as String

// Module-specific properties for Maven publishing.
ext {
    set("artifactId", "exposed-pagination-core")
    set("pomName", "Exposed Pagination Core")
    set("pomDescription", "Core models for Exposed ORM pagination support.")
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(libs.kotlinx.serialization)
    detektPlugins(libs.detekt.formatting)
    testImplementation(kotlin("test"))
}
