package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.Logger
import net.thunderbird.components.core.logging.console.ConsoleLogSink
import net.thunderbird.components.ui.bolt.atom.Surface
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.dsl.onClose

private const val FP4_UI_DENSITY = 2.75f

fun main() {
    prepareCache()
    val isUbuntuTouch = System.getenv("APP_ID")?.startsWith("mail-demo.thomasredstone_") == true
    // Xwayland/Mir maximises the outer frame without resizing Compose's initial
    // canvas. Match the actual screen explicitly, as in the mobileapp port.
    val screenSize = if (isUbuntuTouch) Toolkit.getDefaultToolkit().screenSize else null
    val koin = startApplicationKoin(isUbuntuTouch)
    initializeMailRuntime(koin.get())
    val keyboard = if (isUbuntuTouch) koin.get<MaliitKeyboard>() else null
    application {
        val scope = rememberCoroutineScope()
        val lifecycle = remember { MailWindowLifecycle(scope) }
        var viewModel by remember { mutableStateOf<DemoMailViewModel?>(null) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            runCatching { withContext(Dispatchers.IO) { koin.get<DemoMailRepository>().load() } }
                .onSuccess { viewModel = DemoMailViewModel(it) }
                .onFailure { failed = true }
        }
        Window(
            onCloseRequest = { lifecycle.requestClose(::exitApplication) },
            title = "Mail demo — Ubuntu Touch",
            state = rememberWindowState(
                width = screenSize?.width?.dp ?: 420.dp,
                height = screenSize?.height?.dp ?: 800.dp,
            ),
            onKeyEvent = {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                    viewModel?.event(DemoMailEvent.Back)
                    true
                } else {
                    false
                }
            },
        ) {
            KeyboardWindowFocus(window, keyboard, lifecycle)
            val density = if (isUbuntuTouch) Density(FP4_UI_DENSITY) else LocalDensity.current
            CompositionLocalProvider(LocalDensity provides density) {
                ThunderbirdBoltTheme {
                    Surface(Modifier.fillMaxSize()) {
                        val loaded = viewModel
                        if (loaded == null) {
                            LoadingDemo(failed)
                        } else {
                            MailApplicationScreen(
                                loaded,
                                keyboard,
                                createAccounts = { koin.get<AccountSetupViewModel>() },
                                createMailbox = { koin.get<LiveMailViewModel>() },
                                createComposer = { koin.get<ComposerViewModel>() },
                                lifecycle = lifecycle,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun startApplicationKoin(isUbuntuTouch: Boolean) = startKoin {
    modules(
        module {
            single { DemoMailRepository() }
            single { MailDatabase(mailDatabasePath()) }
            single { AccountSettingsStorage(get()) }
            single<CredentialStore> {
                if (isUbuntuTouch) SignonCredentialStore(SignonIdentityIndex(get())) else SecretServiceCredentialStore()
            }
            single { OAuthProviderRegistry() }
            single { UtAccountManager(get(), get(), get()) }
            single { ManagedOAuthCredentialStore(get()) }
            single { OAuthTokenStore(get<ManagedOAuthCredentialStore>()) }
            single { JdkOAuthTokenHttp() } onClose { it?.close() }
            single { OAuthTokenClient(get<JdkOAuthTokenHttp>()) }
            single<OAuthIdentityVerifier> { HttpsOAuthIdentityVerifier(get<JdkOAuthTokenHttp>()) }
            single { OAuthTokenCoordinator(get(), get()) }
            single { OAuthMailAuthentication(get(), get()) }
            single<OAuthBrowser> { if (isUbuntuTouch) LomiriOAuthBrowser() else DesktopOAuthBrowser() }
            single { OAuthAccountAuthorization(get(), get(), get(), get(), identity = get()) }
            factory { AccountSetupViewModel(get(), authorization = get(), providers = get()) }
            single { ForegroundMailSessionFactory(get(), get(), JvmTrustedSocketFactory(), get(), oauth = get()) }
            single<LiveMailRepository> { PersistentLiveMailRepository(get(), get()) }
            single { MailSortPreferences(mailDatabasePath().resolveSibling("mail-sort")) }
            factory { LiveMailViewModel(get(), get(), sortPreferences = get()) }
            single { OutgoingMailStore(get()) }
            single<OutgoingTransport> { SessionOutgoingTransport(get()) }
            single { SentCopyStore(get()) }
            single<SentCopyTransport> { SessionSentCopyTransport(get()) }
            single { SentCopyFiler(get(), get()) }
            single { OutgoingMailSender(get(), get(), get()) }
            factory { ComposerViewModel(get(), get(), get(), filing = get()) }
            single<Logger> { DefaultLogger(sink = ConsoleLogSink()) }
            single { MaliitKeyboard(get()) }
        },
    )
}.koin

@Composable
private fun KeyboardWindowFocus(window: java.awt.Window, keyboard: MaliitKeyboard?, lifecycle: MailWindowLifecycle) {
    DisposableEffect(window, keyboard) {
        val listener = object : WindowAdapter() {
            override fun windowLostFocus(event: WindowEvent) {
                keyboard?.windowFocusChanged(false)
                lifecycle.background()
            }
            override fun windowGainedFocus(event: WindowEvent) {
                keyboard?.windowFocusChanged(true)
            }
        }
        window.addWindowFocusListener(listener)
        onDispose {
            window.removeWindowFocusListener(listener)
            keyboard?.windowFocusChanged(false)
        }
    }
}

@Composable
private fun LoadingDemo(failed: Boolean) {
    TextBodyLarge(
        text = if (failed) "Unable to load demo messages. Please reopen the app." else "Loading demo messages…",
        modifier = Modifier.padding(24.dp),
    )
}
