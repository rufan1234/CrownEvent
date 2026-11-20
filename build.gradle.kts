plugins {
    java
}

group = "com.smp"
version = "1.0.0"

// Paper API surumunu buradan veya komut satirindan degistirebilirsin:
//   gradle build -PpaperVersion=26.2.build.+
// Tam dogru surum dizesini https://docs.papermc.io/paper/dev/getting-started/paper-plugins adresinden kontrol et.
val paperVersion: String = providers.gradleProperty("paperVersion").getOrElse("26.2.build.+")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
