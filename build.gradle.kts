plugins { java }
group = "dev.openai"
version = "0.7.2"

repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.243-stable")
    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile> { options.encoding = "UTF-8"; options.release.set(25) }
tasks.test { useJUnitPlatform() }

val verification by sourceSets.creating {
    java.srcDir("tools/verification/java")
    resources.srcDir("tools/verification/resources")
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("verificationJar") {
    archiveBaseName.set("HoloMapVerifier")
    from(verification.output)
}

val gpuVerification by sourceSets.creating {
    java.srcDir("tools/gpu-verification/java")
    resources.srcDir("tools/gpu-verification/resources")
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("gpuVerificationJar") {
    archiveBaseName.set("HoloMapGpuVerifier")
    from(gpuVerification.output)
}
