package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.components.vault.SecretRefField

/**
 * S3 连接信息的表单项，备份和媒体上传的配置共用。
 */
fun CardGroupScope.s3ConnectionItems(
    config: S3Config,
    onUpdate: (S3Config) -> Unit,
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.backup_page_s3_endpoint)) },
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = config.endpoint,
            onValueChange = { onUpdate(config.copy(endpoint = it.trim())) },
            placeholder = { Text("https://s3.amazonaws.com") },
            singleLine = true,
        )
    }
    formItem(
        headlineContent = { Text(stringResource(R.string.backup_page_s3_access_key_id)) },
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = config.accessKeyId,
            onValueChange = { onUpdate(config.copy(accessKeyId = it.trim())) },
            singleLine = true,
        )
    }
    formItem(
        headlineContent = { Text(stringResource(R.string.backup_page_s3_secret_access_key)) },
    ) {
        SecretRefField(
            value = config.secretAccessKey,
            onValueChange = { onUpdate(config.copy(secretAccessKey = it.trim())) },
            label = stringResource(R.string.backup_page_s3_secret_access_key),
            nameHint = "S3_SECRET_KEY",
            singleLine = true,
        )
    }
    formItem(
        headlineContent = { Text(stringResource(R.string.backup_page_s3_bucket)) },
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = config.bucket,
            onValueChange = { onUpdate(config.copy(bucket = it.trim())) },
            placeholder = { Text(stringResource(R.string.s3_config_bucket_placeholder)) },
            singleLine = true,
        )
    }
    switchItem(
        checked = config.pathStyle,
        onCheckedChange = { onUpdate(config.copy(pathStyle = it)) },
        headlineContent = { Text(stringResource(R.string.backup_page_s3_path_style)) },
        supportingContent = { Text(stringResource(R.string.backup_page_s3_path_style_desc)) },
    )
    formItem(
        headlineContent = { Text(stringResource(R.string.backup_page_s3_region)) },
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = config.region,
            onValueChange = { onUpdate(config.copy(region = it.trim())) },
            placeholder = { Text("auto") },
            singleLine = true,
        )
    }
}
