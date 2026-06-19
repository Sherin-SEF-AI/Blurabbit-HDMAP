plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":export"))
    implementation(project(":hdmap"))
    implementation(libs.kotlinx.serialization.json)
    implementation("org.postgresql:postgresql:42.7.4")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation("com.h2database:h2:2.3.232")
}

application {
    mainClass.set("com.blurabbit.hdmap.backend.ServerKt")
}
