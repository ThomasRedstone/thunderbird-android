plugins {
    id(ThunderbirdPlugins.Library.kmpCompose)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.mail.message.list.internal"
        androidResources.enable = true
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.mail.message.list.api)
            implementation(projects.core.common)
            implementation(projects.core.preference.api)
            implementation(projects.feature.account.api)
            implementation(projects.feature.account.profile.api)
            implementation(projects.feature.mail.folder.api)
            implementation(projects.feature.notification.api)
        }
        androidMain.dependencies {
            implementation(projects.backend.api)
            implementation(projects.core.android.account)
            implementation(projects.core.android.common)
            implementation(projects.core.ui.compose.common)
            implementation(projects.core.ui.theme.api)
            implementation(projects.feature.mail.account.api)
            implementation(projects.legacy.mailstore)
            implementation(projects.mail.common)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        jvmTest.dependencies {
            implementation(libs.tb.mobile.components.ui.testing)
        }
        androidHostTest.dependencies {
            implementation(projects.core.android.account.fake)
            implementation(projects.core.ui.compose.testing)
            implementation(projects.feature.notification.testing)
            implementation(libs.mockito.kotlin)
        }
    }
}

codeCoverage {
    branchCoverage = 4
    lineCoverage = 9
}
