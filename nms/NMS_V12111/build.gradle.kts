plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT", "dev.folia")
    implementation(project(":zutils-based-api"))
}

configurations.reobf {
    outgoing.artifact(layout.buildDirectory.file("libs/${project.name}.jar"))
    paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}