plugins {
    kotlin("jvm") version "2.3.0"
    id("java-library")
    id("java")
    id("com.gradleup.shadow") version "9.3.1"
}

group = "i.mrhua269"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")
    apply(plugin = "com.gradleup.shadow")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://repo.menthamc.org/repository/maven-public/")
        maven("https://maven.nostal.ink/repository/maven-snapshots/")
    }

    tasks {
        compileJava {
            options.encoding = "UTF-8"
        }
        processResources {
            filesMatching("**/plugin.yml") {
                expand(rootProject.project.properties)
            }
            outputs.upToDateWhen { false }
        }
    }
}
