plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation("io.grpc:grpc-netty-shaded:1.69.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("info.picocli:picocli:4.7.6")
}

application {
    mainClass.set("dev.executor.sidecar.Main")
}
