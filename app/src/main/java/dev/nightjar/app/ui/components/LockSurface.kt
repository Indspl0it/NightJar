package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.security.AppLockResult
import dev.nightjar.app.core.security.authenticateForAppLock
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.findActivity
import kotlinx.coroutines.launch

/**
 * The opaque surface that covers the app while the lock is on.
 *
 * It replaces the whole navigation host, so no screen's ViewModel is alive and no row can flash behind it. The
 * credential prompt runs as soon as the surface appears; the button retries after a cancel, because the platform
 * dialog cannot be re-shown on its own once it is dismissed.
 *
 * [onNoCredential] is the fail-open path: there is nothing enrolled to authenticate against, so the caller
 * unlocks and records why.
 */
@Composable
fun LockSurface(
    onAuthenticated: () -> Unit,
    onNoCredential: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)
    var prompting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val attempt: () -> Unit = {
        if (!prompting) {
            if (activity == null) {
                // No window to authenticate against: behave like "no credential" rather than trapping the user.
                onNoCredential()
            } else {
                prompting = true
                failed = false
                scope.launch {
                    when (authenticateForAppLock(activity, title, subtitle)) {
                        AppLockResult.AUTHENTICATED -> onAuthenticated()
                        AppLockResult.NO_CREDENTIAL -> onNoCredential()
                        AppLockResult.CANCELLED -> failed = true
                    }
                    prompting = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { attempt() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(NjSpacing.l + NjSpacing.s),
            verticalArrangement = Arrangement.spacedBy(NjSpacing.m, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(88.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    NjIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (failed) {
                NjInfoBanner(text = stringResource(R.string.lock_failed), kind = NjBannerKind.WARN)
            }
            NjPrimaryButton(
                text = stringResource(R.string.lock_unlock),
                onClick = attempt,
                enabled = !prompting,
                icon = NjIcons.Lock,
            )
        }
    }
}
