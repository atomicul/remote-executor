plugins {
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.jar {
    enabled = false
}

dependencies {
    implementation(project(":common"))
    implementation("io.grpc:grpc-netty-shaded:1.69.0")
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
    implementation("com.amazonaws:aws-lambda-java-events:3.14.0")
    implementation("software.amazon.awssdk:ec2:2.34.0")
    implementation("software.amazon.awssdk:dynamodb:2.34.0")
    implementation("software.amazon.awssdk:sqs:2.34.0")
    implementation("software.amazon.awssdk:ssm:2.34.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
