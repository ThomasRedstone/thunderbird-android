package net.thunderbird.app.ubuntutouch

import java.nio.file.Files
import java.nio.file.Path

/** Test-only entry point. Caller must apply the installed Click profile with aa-exec. */
object ConfinedStorageProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val profile = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(profile.startsWith("mail-demo.thomasredstone_mail-demo_")) { "Click profile was not applied" }
        check(profile.endsWith("(enforce)")) { "Click profile is not enforcing" }
        println("Verified enforcing Mail demo Click profile")
        PhoneStorageProbe.main(args)
        SecretServiceProbe.main(emptyArray())
    }
}
