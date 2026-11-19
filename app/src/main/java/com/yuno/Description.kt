package com.yuno

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Video details and description in the same draggable panel used by comments and transcript. */
@Composable
fun DescriptionPanel(state: PlayerState, partialTop: Dp, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val details = state.details ?: return
    val item = details.item
    val openChannel = LocalChannelOpener.current
    val channelUrl = item.channelUrl ?: state.current?.channelUrl
    val scrollState = rememberScrollState(state.descriptionScroll)
    DisposableEffect(item.id, scrollState) {
        onDispose {
            if (state.current?.id == item.id) state.descriptionScroll = scrollState.value
        }
    }

    PlayerPanel(
        partialTop = partialTop,
        onClose = onClose,
        closeLabel = stringResource(R.string.player_description_close),
        title = {
            Text(
                stringResource(R.string.player_description_title),
                fontFamily = RoundedHeavy,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 21.sp,
            )
        },
    ) { listModifier ->
        Column(
            listModifier
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                item.title,
                fontFamily = RoundedHeavy,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                letterSpacing = (-0.5).sp,
            )
            Spacer(Modifier.height(14.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(enabled = channelUrl != null, pressedScale = 0.97f) {
                        channelUrl?.let(openChannel)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelAvatar(item.avatar, item.channel, size = 36)
                Spacer(Modifier.width(10.dp))
                Text(item.channel, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                details.likes.takeIf { it > 0 }?.let {
                    DescriptionStat(formatCount(it), stringResource(R.string.player_description_likes), Modifier.weight(1f))
                }
                item.views.takeIf { it >= 0 }?.let {
                    DescriptionStat(formatCount(it), stringResource(R.string.player_description_views), Modifier.weight(1f))
                }
                YouTube.uploadedLabel(item)?.ifBlank { null }?.let {
                    DescriptionStat(it, stringResource(R.string.player_description_uploaded), Modifier.weight(1.35f))
                }
            }

            Spacer(Modifier.height(18.dp))
            if (details.description.isNotBlank()) {
                LinkedText(
                    text = details.description,
                    links = details.descriptionLinks,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(c.surfaceContainer)
                        .padding(16.dp),
                    style = androidx.compose.material3.LocalTextStyle.current.copy(
                        color = c.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            } else {
                Text(
                    stringResource(R.string.player_live_description_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(c.surfaceContainer)
                        .padding(16.dp),
                    color = c.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun DescriptionStat(value: String, label: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            label,
            color = c.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
