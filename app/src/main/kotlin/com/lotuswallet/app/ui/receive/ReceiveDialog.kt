package com.lotuswallet.app.ui.receive

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lotuswallet.app.R
import com.lotuswallet.app.util.Clipboard
import com.lotuswallet.app.util.QrCode
import com.lotuswallet.app.util.Sharing
import com.lotuswallet.core.designsystem.component.MonoText

/**
 * 收款二维码。
 *
 * ## 为什么二维码要做成「白底黑块」而不是跟随主题
 * 深色主题下用浅色前景 + 深色背景的二维码，很多扫码器识别率会显著下降
 * （尤其是支付类 App 的扫码器假设了白底黑块）。因此这里硬编码白底，
 * 与主题无关 —— 可用性优先于视觉一致性。
 */
@Composable
fun ReceiveDialog(
    address: String,
    chainName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember(address) { QrCode.encode(address, sizePx = 640, marginModules = 1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallet_receive_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = chainName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = address,
                            modifier = Modifier.size(200.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.wallet_qr_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                MonoText(text = address, small = true, maxLines = 3)

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { Clipboard.copy(context, address, "address") }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                    }
                    IconButton(onClick = { Sharing.shareText(context, address, chainName) }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Clipboard.copy(context, address, "address")
                onDismiss()
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.wallet_copy_address))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
