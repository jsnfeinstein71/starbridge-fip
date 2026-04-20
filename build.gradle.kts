plugins {
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "com.innovationstrategies.fip"
    version = "0.1.0"
}

subprojects {
    repositories {
        mavenCentral()
    }
}
