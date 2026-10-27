package net.thunderbird.app.ubuntutouch

import java.nio.file.Files
import java.nio.file.Path

/** Explicit test executable. All credential operations must run inside the installed app's enforcing profile. */
object ConfinedSignonProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val profile = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(profile.startsWith("mail-demo.thomasredstone_mail-demo_") && profile.endsWith("(enforce)")) {
            "Expected enforcing Mail demo profile"
        }
        println("Verified enforcing Mail demo profile: $profile")
        when (args[0]) {
            "lifecycle" -> SignonProbe.main(arrayOf(args[1]))
            "prepare" -> AccountRestartProbe.main(arrayOf("prepare", args[1]))
            "verify-remove" -> AccountRestartProbe.main(arrayOf("verify-remove", args[1]))
            else -> error("Unknown probe operation")
        }
    }
}
