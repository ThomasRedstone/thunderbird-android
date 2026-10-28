plugins {
    id(ThunderbirdPlugins.Library.kmpCompose)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.mail.message.list"
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.tb.mobile.components.core.outcome)
            api(projects.core.ui.contract)

            implementation(projects.core.common)
            implementation(projects.core.featureflag)
            implementation(projects.core.preference.api)
            implementation(projects.feature.account.api)
            implementation(projects.feature.mail.account.api)
            implementation(projects.feature.mail.folder.api)
            implementation(projects.feature.notification.api)
        }
        commonTest.dependencies {
            implementation(libs.tb.mobile.components.ui.testing)
        }
        androidMain.dependencies {
            implementation(projects.core.android.account)
            implementation(libs.androidx.fragment)
        }
    }
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
