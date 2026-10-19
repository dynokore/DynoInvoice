// tenancy-domain — pure Kotlin (TPL-01, ARC-03): the four organisation levels and their rules.
plugins { id("gst.domain") }

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(libs.platform.tenancyKernel.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}
