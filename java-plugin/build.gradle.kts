plugins {
    java
}

group = "com.andresq.meteorsmash"
version = "0.1.0"

dependencies {
    implementation(files(rootProject.layout.projectDirectory.file("libs/pumpkin-stub.jar")))
    implementation(files(rootProject.layout.projectDirectory.file("libs/pumpkin-abs.jar")))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.named<JavaCompile>("compileJava") {
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("target/java/classes"))
    doFirst {
        logger.lifecycle("[1/4] Compiling Java sources to target/java/classes")
    }
}
