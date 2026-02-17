plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation("io.grpc:grpc-netty-shaded:1.69.0")
    implementation("io.grpc:grpc-services:1.69.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("software.amazon.awssdk:dynamodb:2.34.0")
    implementation("software.amazon.awssdk:ec2:2.34.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.executor.scheduler.Main")
}
