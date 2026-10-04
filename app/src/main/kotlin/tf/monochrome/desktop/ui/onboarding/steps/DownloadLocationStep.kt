package tf.monochrome.desktop.ui.onboarding.steps

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.desktop.ui.components.bounceClick
import tf.monochrome.desktop.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.desktop.ui.onboarding.OnboardingViewModel
import tf.monochrome.desktop.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import tf.monochrome.desktop.R

/**
 * Choose where downloaded tracks land. Default is app-scoped storage
 * (no permissions needed, removed on uninstall); a custom SAF folder takes
 * a persisted read+write grant, matching the Settings → Downloads picker.
 *
 * Desktop: the default is a folder under the user's Music folder
 * (`AppPaths.downloadsDir`), which an uninstall leaves alone, and the custom
 * folder is a path from the folder dialog. Both are shown by path, as
 * Settings shows them.
 */
@Composable
fun DownloadLocationStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val downloadUri by viewModel.downloadFolderUri.collectAsStateWithLifecycle()

    val downloadFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadFolder(uri.toString())
        }
    }

    OnboardingStepScaffold(
        title = stringResource(R.string.page_downloads),
        subtitle = stringResource(R.string.onboarding_downloads_subtitle),
        primaryLabel = stringResource(R.string.action_continue),
        onPrimary = { viewModel.next() }
    ) {
        // Desktop: not "App storage", which it is not here; "Default" and the
        // folder it is.
        LocationOption(
            selected = downloadUri == null,
            title = stringResource(R.string.settings_font_default),
            description = context.paths.downloadsDir.absolutePath,
            onClick = { viewModel.setDownloadFolder(null) }
        )
        LocationOption(
            selected = downloadUri != null,
            title = stringResource(R.string.settings_custom_folder),
            description = downloadUri?.let { friendlyTreeUriLabel(it) }
                ?: stringResource(R.string.onboarding_custom_folder_desc),
            onClick = { downloadFolderPicker.launch(null) }
        )
    }
}

@Composable
private fun LocationOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MonoDimens.cardAlpha)
            }
        ),
        shape = MonoDimens.shapeMd,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MonoDimens.spacingSm)
            .bounceClick(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(MonoDimens.spacingLg)
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.RadioButtonChecked
                else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MonoDimens.iconSm)
            )
            Column(modifier = Modifier.padding(start = MonoDimens.spacingMd)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * "content://…/tree/primary%3AMusic" → "Music" for display.
 *
 * Desktop: the picker hands back a `file:` URI and a stored setting may be a
 * plain path; both are shown as the whole path, because on a computer a
 * folder's name alone does not say where it is. A tree URI from an Android
 * backup still gets the Android treatment.
 */
private fun friendlyTreeUriLabel(uriString: String): String = runCatching {
    when {
        uriString.startsWith("file:") -> java.io.File(java.net.URI(uriString)).absolutePath
        uriString.startsWith("content://") -> {
            val decoded = Uri.decode(uriString)
            decoded.substringAfterLast(':').substringAfterLast('/').ifBlank { decoded }
        }
        else -> java.io.File(uriString).absolutePath
    }
}.getOrDefault(uriString)
