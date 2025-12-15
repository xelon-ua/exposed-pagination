/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.configurationcache.extensions.capitalized
import java.io.File

plugins {
    signing
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

// https://central.sonatype.com/account
// https://central.sonatype.com/publishing/deployments
// https://vanniktech.github.io/gradle-maven-publish-plugin/central/#automatic-release
mavenPublishing {
    val artifactId: String = project.properties["artifactId"] as String
    val repository: String = project.properties["repository"] as String
    val repositoryConnection: String = project.properties["repositoryConnection"] as String
    val developer: String = project.properties["developer"] as String
    val pomName: String = project.properties["pomName"] as String
    val pomDescription: String = project.properties["pomDescription"] as String

    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        )
    )

    coordinates(
        groupId = project.group as String,
        artifactId = artifactId,
        version = project.version as String
    )

    publishToMavenCentral(
        host = SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = false
    )

    signAllPublications()

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
                name.set(developer.capitalized())
                email.set(System.getenv("DEVELOPER_EMAIL"))
                url = "https://$repository"
            }
        }
        scm {
            connection.set("scm:git:git://$repository/$artifactId.git")
            developerConnection.set("scm:git:ssh://$repositoryConnection/$artifactId.git")
            url.set("https://$repository/$artifactId")
        }
    }
}

signing {
    val privateKeyPath: String? = System.getenv("MAVEN_SIGNING_KEY_PATH")
    val passphrase: String? = System.getenv("MAVEN_SIGNING_KEY_PASSPHRASE")

    if (privateKeyPath.isNullOrBlank()) {
        println("MAVEN_SIGNING_KEY_PATH is not set. Skipping signing.")
    } else if (passphrase.isNullOrBlank()) {
        println("MAVEN_SIGNING_KEY_PASSPHRASE is not set. Skipping signing.")
    } else {
        val privateKey: String = File(privateKeyPath).readText()
        useInMemoryPgpKeys(privateKey, passphrase)
        sign(publishing.publications)
    }
}
