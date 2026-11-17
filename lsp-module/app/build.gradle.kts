plugins { id("com.android.application") }

android {
    namespace = "com.personal.g2lyricsbridge"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.personal.g2lyricsbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
