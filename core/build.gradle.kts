/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

plugins {
    //alias(libs.plugins.kotlin.jvm)
    id("kotlin-library")
    id("vanniktech-publish")
    //alias(libs.plugins.vanniktech)
}

group = project.properties["group"] as String
version = project.properties["version"] as String

// Module-specific properties for Maven publishing.
ext {
    set("pomName", "Exposed Pagination Core")
    set("pomDescription", "Core models for Exposed ORM pagination support.")
}

dependencies {
    implementation(libs.kotlinx.serialization)
    detektPlugins(libs.detekt.formatting)
    testImplementation(kotlin("test"))
}
