package net.thunderbird.app.ubuntutouch

import java.awt.Frame
import java.awt.Robot
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Opt-in actual-window regression probe. Run only against the documented isolated synthetic database under Xvfb. */
object DesktopDraftCloseProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(System.getenv("UT_DRAFT_CLOSE_FIXTURE") == "synthetic-only")
        val root = Path.of(checkNotNull(System.getenv("XDG_DATA_HOME"))).toAbsolutePath().normalize()
        require(root.parent == Path.of("/tmp") && root.fileName.toString().startsWith("mail-composer-visual-"))
        check(Files.readString(root.resolve("synthetic.fixture")) == "synthetic-only")
        check(System.getenv("WAYLAND_DISPLAY") == null && System.getenv("XDG_SESSION_TYPE") == "x11")
        thread(name = "synthetic-close-timeout", isDaemon = true) {
            Thread.sleep(20000)
            exitProcess(2)
        }
        thread(name = "synthetic-draft-close", isDaemon = true) {
            val robot = Robot().apply { autoDelay = 15 }
            Thread.sleep(3000)
            val frame = Window.getWindows().filterIsInstance<Frame>().single { it.isShowing }
            val location = frame.locationOnScreen
            fun click(x: Int, y: Int) {
                robot.mouseMove(location.x + x, location.y + y)
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                println("Synthetic click queued: $x,$y")
                robot.waitForIdle()
                println("Synthetic click processed")
                Thread.sleep(300)
            }
            click(350, 24) // Write
            click(100, 130) // Synthetic account
            click(140, 238) // Existing draft
            click(170, 396) // Subject
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_A)
            robot.keyRelease(KeyEvent.VK_A)
            robot.keyRelease(KeyEvent.VK_CONTROL)
            robot.waitForIdle()
            Thread.sleep(100)
            "draft survives close".forEach { character ->
                val code = KeyEvent.getExtendedKeyCodeForChar(character.code)
                robot.keyPress(code)
                robot.keyRelease(code)
            }
            println("Synthetic input queued")
            robot.waitForIdle()
            println("Synthetic input processed; posting window close")
            SwingUtilities.invokeLater {
                println("Synthetic window closing dispatched")
                frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
            }
        }
        net.thunderbird.app.ubuntutouch.main()
    }
}
