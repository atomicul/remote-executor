plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation("io.grpc:grpc-netty-shaded:1.69.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("info.picocli:picocli:4.7.6")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.grpc:grpc-inprocess:1.69.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.executor.sidecar.Main")
}
