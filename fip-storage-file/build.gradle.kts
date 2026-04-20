plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":fip-core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
