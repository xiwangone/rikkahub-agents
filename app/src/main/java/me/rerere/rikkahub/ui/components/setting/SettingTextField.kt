package me.rerere.rikkahub.ui.components.setting

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch

/**
 * 聚焦时把字段滚进可视范围：键盘弹出后获焦字段不被遮挡。
 *
 * 与键盘批的容器级 [imePadding] 互补：`imePadding` 只在键盘可见时给容器底部加内边距、
 * 让页面「可以被拉上去」，本身不触发滚动；本修饰符在获焦时主动 `bringIntoView()`，
 * 才是自动顶起。`imePadding` 仍由页面 / 弹窗容器自己加，不在字段上重复加。
 *
 * 本文件之前，`AutoCompressDialog` 与 `SshKeyPairDialog` 各有一份同款私有实现，
 * 已收拢到这里；其余同款实现（如 `CompressContextDialog`、`BackupEncryptionSection`）
 * 不在设置页内，不在此处收拢。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bringIntoViewOnFocus(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged { state ->
            if (state.isFocused) scope.launch { requester.bringIntoView() }
        }
}

/**
 * 设置页共享输入框：与 `OutlinedTextField` 同形签名，可直接改名迁移。
 *
 * 内建 [bringIntoViewOnFocus]（获焦自动顶起）；容器级 `imePadding` 仍由页面 / 弹窗自己加。
 * 键盘遮挡批只加了容器内边距、获焦不会自动顶起，本组件补上后半段。
 */
@Composable
fun SettingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.bringIntoViewOnFocus(),
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
    )
}
