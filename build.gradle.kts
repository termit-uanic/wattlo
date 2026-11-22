plugins {
    id("com.android.application") version "9.5.1" apply false
    // Firebase Cloud Messaging (push about changed schedules); applied only where google-services.json exists
    id("com.google.gms.google-services") version "4.4.3" apply false
}

// the version of both apps, from gradle.properties
apply(from = "gradle/versioning.gradle.kts")
