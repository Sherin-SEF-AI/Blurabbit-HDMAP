plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":domain"))
    api(project(":mapping"))
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
