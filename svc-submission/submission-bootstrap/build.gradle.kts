// submission-bootstrap (TPL-01, TPL-18): the Spring Boot application and its composition.
plugins {
    id("gst.bootstrap")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":submission-adapters"))
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.impl)
}
