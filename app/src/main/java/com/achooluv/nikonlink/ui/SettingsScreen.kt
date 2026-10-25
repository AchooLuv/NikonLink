package com.achooluv.nikonlink.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achooluv.nikonlink.BuildConfig
import com.achooluv.nikonlink.NikonLinkRepository
import com.achooluv.nikonlink.data.SettingsStore

@Composable
fun SettingsScreen(repo: NikonLinkRepository) {
    val settings = repo.settings
    val autoPull by settings.autoPull.collectAsStateWithLifecycle()
    val keepAlive by settings.keepAlive.collectAsStateWithLifecycle()
    val keepAliveSeconds by settings.keepAliveSeconds.collectAsStateWithLifecycle()
    val rawThumbs by settings.showRawThumbnails.collectAsStateWithLifecycle()
    val chunk by settings.chunkSize.collectAsStateWithLifecycle()
    val eventsSupported by repo.eventsSupported.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 120.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("设置", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text("只保留必要选项", color = NikonLinkTheme.colors.dim, fontSize = 12.sp)

        Spacer(Modifier.height(22.dp))
        SectionTitle("传输")
        Spacer(Modifier.height(10.dp))
        NikonCard {
            SettingSwitch(
                title = "拍摄后自动拉取",
                subtitle = if (eventsSupported) {
                    "相机拍到新照片时自动下载到手机。默认关闭；开启时只拉之后拍的照片，不会把卡里旧照片一起下载。"
                } else {
                    "这台相机没有开放 PTP/IP 事件通道，自动拉取不可用；仍可在「浏览」里手动下载。"
                },
                checked = autoPull,
                onCheckedChange = { repo.setAutoPull(it) },
            )
            HorizontalDivider(color = NikonLinkTheme.colors.line, modifier = Modifier.padding(vertical = 14.dp))
            Caption("分块大小")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsStore.CHUNK_CHOICES.forEach { size ->
                    FilterChip(
                        selected = chunk == size,
                        onClick = { settings.setChunkSize(size) },
                        label = { Text(SettingsStore.chunkLabel(size), fontSize = 13.sp) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Ink,
                            labelColor = PaperDim,
                            selectedContainerColor = NikonYellow,
                            selectedLabelColor = Color(0xFF161200),
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = chunk == size,
                            borderColor = NikonLinkTheme.colors.line,
                            selectedBorderColor = NikonYellow,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "块越大往返次数越少、速度越快；网络不稳时用 1–2 MiB 更平滑。断点续传始终按块边界恢复。",
                color = PaperDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("连接")
        Spacer(Modifier.height(10.dp))
        NikonCard {
            SettingSwitch(
                title = "保持连接活跃",
                subtitle = "定期发一次轻量心跳，掉线后自动重连（指数退避，最长 30 秒一次）。信号弱时把间隔调大更省电。",
                checked = keepAlive,
                onCheckedChange = {
                    settings.setKeepAlive(it)
                    repo.restartKeepAlive()
                },
            )
            if (keepAlive) {
                Spacer(Modifier.height(10.dp))
                Caption("心跳间隔")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 30, 60).forEach { seconds ->
                        FilterChip(
                            selected = keepAliveSeconds == seconds,
                            onClick = {
                                settings.setKeepAliveSeconds(seconds)
                                repo.restartKeepAlive()
                            },
                            label = { Text("${seconds}s", fontSize = 12.sp) },
                            shape = RoundedCornerShape(12.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Ink,
                                labelColor = PaperDim,
                                selectedContainerColor = NikonYellow,
                                selectedLabelColor = Color(0xFF161200),
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = keepAliveSeconds == seconds,
                                borderColor = NikonLinkTheme.colors.line,
                                selectedBorderColor = NikonYellow,
                            ),
                        )
                    }
                }
            }
            HorizontalDivider(color = NikonLinkTheme.colors.line, modifier = Modifier.padding(vertical = 14.dp))
            SettingSwitch(
                title = "RAW 使用相机缩略图",
                subtitle = "RAW 只下载完整文件不解码；预览统一用相机内嵌 JPEG 缩略图。",
                checked = rawThumbs,
                onCheckedChange = { settings.setShowRawThumbnails(it) },
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("存储")
        Spacer(Modifier.height(10.dp))
        NikonCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("保存位置", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("Pictures/NikonLink · Movies/NikonLink", color = PaperDim, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = NikonLinkTheme.colors.line)
            Spacer(Modifier.height(12.dp))
            Text(
                "下载记录（用于跳过已下载文件）",
                color = PaperDim,
                fontSize = 13.sp,
                modifier = Modifier.clickable { confirmClear = true }.padding(vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("诊断")
        Spacer(Modifier.height(10.dp))
        NikonCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("复制诊断信息", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "包含相机型号、固件、连接步骤与最近 150 条协议日志。出问题时贴到 GitHub Issue 即可定位。",
                        color = PaperDim,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "复制到剪贴板",
                color = NikonYellow,
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    clipboard.setText(
                        androidx.compose.ui.text.AnnotatedString(com.achooluv.nikonlink.Diagnostics.dump(context)),
                    )
                    android.widget.Toast.makeText(context, "诊断信息已复制", android.widget.Toast.LENGTH_SHORT).show()
                }.padding(vertical = 4.dp),
            )
            HorizontalDivider(color = NikonLinkTheme.colors.line, modifier = Modifier.padding(vertical = 10.dp))
            Text(
                "清空日志",
                color = PaperDim,
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    com.achooluv.nikonlink.Diagnostics.clear()
                    android.widget.Toast.makeText(context, "日志已清空", android.widget.Toast.LENGTH_SHORT).show()
                }.padding(vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("关于")
        Spacer(Modifier.height(10.dp))
        NikonCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("NikonLink", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Mono("v${BuildConfig.VERSION_NAME}", color = PaperDim, size = 12)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "尼康 Z 系列无线图传 · PTP/IP 直连 · 仅 arm64",
                color = PaperDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "github.com/AchooLuv/NikonLink",
                color = NikonYellow,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AchooLuv/NikonLink")),
                        )
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "协议实现参考了开源项目 AeroShutter（MIT）对 PTP/IP 与 Nikon 厂商操作的逆向成果。",
                color = PaperDim.copy(alpha = 0.75f),
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
            )
        }

        Spacer(Modifier.height(40.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = InkRaised,
            title = { Text("清除下载记录？", color = Paper, fontSize = 16.sp) },
            text = {
                Text(
                    "只会忘记「哪些照片已经下载过」，不会删除手机里已保存的文件。",
                    color = PaperDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false }) { Text("知道了", color = NikonYellow) }
            },
        )
    }
}

/** Kept as a helper so the clear-records row reads as destructive but stays calm. */
@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = PaperDim, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF161200),
                checkedTrackColor = NikonYellow,
                uncheckedThumbColor = PaperDim,
                uncheckedTrackColor = InkLine,
            ),
        )
    }
}
