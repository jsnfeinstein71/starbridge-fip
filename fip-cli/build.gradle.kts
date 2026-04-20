plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":fip-core"))
    implementation(project(":fip-storage-file"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.innovationstrategies.fip.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
