/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugins.kotlin.jvm.get().let { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    implementation(libs.plugins.kotlin.serialization.get().let { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    implementation(libs.plugins.dokka.get().let { "org.jetbrains.dokka:dokka-gradle-plugin:${it.version}" })
    implementation(libs.plugins.vanniktech.get().let { "com.vanniktech:gradle-maven-publish-plugin:${it.version}" })
    implementation(libs.plugins.detekt.get().let { "io.gitlab.arturbosch.detekt:detekt-gradle-plugin:${it.version}" })
}
