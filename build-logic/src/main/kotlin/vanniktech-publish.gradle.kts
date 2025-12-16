/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

import java.util.Locale

plugins {
    //id("org.jetbrains.kotlin.jvm")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
}

// https://central.sonatype.com/account
// https://central.sonatype.com/publishing/deployments
// https://vanniktech.github.io/gradle-maven-publish-plugin/central/#automatic-release
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    val artifactId: String = project.properties["artifactId"] as String
    val repository: String = project.properties["repository"] as String
    val repositoryConnection: String = project.properties["repositoryConnection"] as String
    val developer: String = project.properties["developer"] as String
    val pomName: String = project.properties["pomName"] as String
    val pomDescription: String = project.properties["pomDescription"] as String

    coordinates(
        groupId = project.group as String,
        artifactId = artifactId,
        version = project.version as String
    )

    pom {
        name.set(pomName)
        description.set(pomDescription)
        url.set("https://$repository/$artifactId")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set(developer)
                name.set(developer.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })
                url.set("https://$repository")
            }
        }
        scm {
            connection.set("scm:git:git://$repository/$artifactId.git")
            developerConnection.set("scm:git:ssh://$repositoryConnection/$artifactId.git")
            url.set("https://$repository/$artifactId")
        }
    }
}

// Javadoc jar for Maven Central, based on Dokka HTML output
tasks.register<Jar>("javadocJar") {
    dependsOn("dokkaGeneratePublicationHtml")
    from(layout.buildDirectory.dir("dokka/html"))
    archiveClassifier.set("javadoc")
}
