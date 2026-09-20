package org.softosaurus.reactionspeed.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.softosaurus.reactionspeed.BuildConfig
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.ads.AdsManager
import org.softosaurus.reactionspeed.ui.common.BackTopBar
import org.softosaurus.reactionspeed.ui.common.BannerScaffold
import org.softosaurus.reactionspeed.ui.common.rememberActivity
import org.softosaurus.reactionspeed.ui.common.rememberResults

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val results = rememberResults()
    val settings by results.settings.collectAsStateWithLifecycle()
    val privacyOptionsRequired by AdsManager.privacyOptionsRequired.collectAsStateWithLifecycle()
    val activity = rememberActivity()
    val context = LocalContext.current
    val policyUrl = stringResource(R.string.privacy_policy_url)

    BannerScaffold {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(stringResource(R.string.settings_title), onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                SectionTitle(stringResource(R.string.settings_section_game))
                SwitchRow(
                    label = stringResource(R.string.use_target_sound),
                    checked = settings.targetSounds,
                    onCheckedChange = results::setTargetSounds,
                )
                SwitchRow(
                    label = stringResource(R.string.use_stone_sounds),
                    checked = settings.stoneSounds,
                    onCheckedChange = results::setStoneSounds,
                )
                SwitchRow(
                    label = stringResource(R.string.use_vibration),
                    checked = settings.vibration,
                    onCheckedChange = results::setVibration,
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                SectionTitle(stringResource(R.string.settings_section_about))

                if (privacyOptionsRequired && activity != null) {
                    ClickableRow(stringResource(R.string.settings_privacy_options)) {
                        AdsManager.showPrivacyOptions(activity)
                    }
                }
                ClickableRow(stringResource(R.string.settings_privacy_policy)) {
                    context.openUri(policyUrl)
                }
                ClickableRow(stringResource(R.string.settings_rate)) {
                    context.openStoreListing()
                }
                Text(
                    text = stringResource(
                        R.string.settings_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ClickableRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

private fun Context.openUri(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No browser (or no Play Store) on the device: nothing sensible to do but stay put.
    }
}

/** `market://` when the Play Store is installed, the web listing otherwise. */
private fun Context.openStoreListing() {
    val marketIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(marketIntent)
    } catch (e: ActivityNotFoundException) {
        openUri("https://play.google.com/store/apps/details?id=$packageName")
    }
}
