package us.lemesh.nodedb.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.HostOutreachSpec
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MapExportFormat
import us.lemesh.nodedb.data.MapExportSummary
import us.lemesh.nodedb.ui.theme.AlertErrorRed
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurface
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextMuted
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.views.MapView

private enum class ExportDelivery {
    SAVE_TO_FILES,
    SHARE
}

@Composable
fun MapExportHost(
    visible: Boolean,
    filePrefix: String,
    mapViewProvider: () -> MapView?,
    buildGeoJson: () -> String,
    buildSummary: () -> MapExportSummary,
    buildKml: (() -> String)? = null,
    buildNodeDbBackupJson: (() -> String)? = null,
    buildHostOutreachSpec: (() -> HostOutreachSpec)? = null,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFormat by remember(visible, buildKml != null) {
        mutableStateOf(if (buildKml != null) MapExportFormat.KML else MapExportFormat.GEOJSON)
    }
    var isExporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingSaveBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingSaveFileName by remember { mutableStateOf("") }

    fun handleSavedDocumentUri(uri: android.net.Uri?) {
        val bytes = pendingSaveBytes
        val fileName = pendingSaveFileName
        pendingSaveBytes = null
        if (uri == null || bytes == null) {
            isExporting = false
            return
        }
        scope.launch {
            val result = MapExportEngine.writeBytesToUri(context, uri, bytes)
            isExporting = false
            result.onSuccess {
                Toast.makeText(
                    context,
                    context.getString(R.string.export_saved_success, fileName),
                    Toast.LENGTH_SHORT
                ).show()
                onDismiss()
            }.onFailure { err ->
                errorMessage = err.message ?: "Unable to write export file."
            }
        }
    }

    val kmlCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MapExportFormat.KML.mimeType)
    ) { uri -> handleSavedDocumentUri(uri) }

    val nodeDbJsonCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MapExportFormat.NODEDB_JSON.mimeType)
    ) { uri -> handleSavedDocumentUri(uri) }

    val geoJsonCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MapExportFormat.GEOJSON.mimeType)
    ) { uri -> handleSavedDocumentUri(uri) }

    val pngCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MapExportFormat.PNG.mimeType)
    ) { uri -> handleSavedDocumentUri(uri) }

    fun triggerExport(delivery: ExportDelivery) {
        if (isExporting) return
        errorMessage = null
        isExporting = true
        val format = selectedFormat
        val effectivePrefix = when (format) {
            MapExportFormat.NODEDB_JSON -> "${filePrefix}_nodedb"
            MapExportFormat.HOST_OUTREACH_PNG -> "${filePrefix}_host_outreach"
            else -> filePrefix
        }
        val fileName = MapExportEngine.suggestedFilename(effectivePrefix, format)

        scope.launch {
            try {
                val bytes: ByteArray = when (format) {
                    MapExportFormat.KML -> withContext(Dispatchers.Default) {
                        (buildKml?.invoke() ?: error("KML export unavailable.")).toByteArray(Charsets.UTF_8)
                    }
                    MapExportFormat.NODEDB_JSON -> withContext(Dispatchers.Default) {
                        (buildNodeDbBackupJson?.invoke() ?: error("NodeDB backup unavailable.")).toByteArray(Charsets.UTF_8)
                    }
                    MapExportFormat.GEOJSON -> withContext(Dispatchers.Default) {
                        buildGeoJson().toByteArray(Charsets.UTF_8)
                    }
                    MapExportFormat.PNG -> {
                        val mapView = mapViewProvider()
                        val rawBitmap = mapView?.let { MapExportEngine.captureMapViewBitmap(it, scaleFactor = 2f) }
                        if (rawBitmap == null) {
                            isExporting = false
                            errorMessage = context.getString(R.string.export_map_unavailable)
                            return@launch
                        }
                        val summary = buildSummary()
                        withContext(Dispatchers.Default) {
                            val emblemBitmap = MapExportEngine.decodeBrandEmblemBitmap(context)
                            val composed = MapExportEngine.composeAnnotatedExportBitmap(rawBitmap, summary, emblemBitmap)
                            try {
                                MapExportEngine.encodeBitmapToPngBytes(composed)
                            } finally {
                                if (composed !== rawBitmap && !composed.isRecycled) {
                                    composed.recycle()
                                }
                                if (!rawBitmap.isRecycled) {
                                    rawBitmap.recycle()
                                }
                                if (emblemBitmap != null && !emblemBitmap.isRecycled) {
                                    emblemBitmap.recycle()
                                }
                            }
                        }
                    }
                    MapExportFormat.HOST_OUTREACH_PNG -> {
                        val mapView = mapViewProvider()
                        val rawBitmap = mapView?.let { MapExportEngine.captureMapViewBitmap(it, scaleFactor = 2f) }
                        if (rawBitmap == null) {
                            isExporting = false
                            errorMessage = context.getString(R.string.export_map_unavailable)
                            return@launch
                        }
                        val spec = buildHostOutreachSpec?.invoke() ?: error("Host Outreach Card unavailable.")
                        withContext(Dispatchers.Default) {
                            val emblemBitmap = MapExportEngine.decodeBrandEmblemBitmap(context)
                            val composed = MapExportEngine.composeHostOutreachCardBitmap(rawBitmap, spec, emblemBitmap)
                            try {
                                MapExportEngine.encodeBitmapToPngBytes(composed)
                            } finally {
                                if (composed !== rawBitmap && !composed.isRecycled) {
                                    composed.recycle()
                                }
                                if (!rawBitmap.isRecycled) {
                                    rawBitmap.recycle()
                                }
                                if (emblemBitmap != null && !emblemBitmap.isRecycled) {
                                    emblemBitmap.recycle()
                                }
                            }
                        }
                    }
                }

                when (delivery) {
                    ExportDelivery.SAVE_TO_FILES -> {
                        pendingSaveBytes = bytes
                        pendingSaveFileName = fileName
                        when (format) {
                            MapExportFormat.KML -> kmlCreateLauncher.launch(fileName)
                            MapExportFormat.NODEDB_JSON -> nodeDbJsonCreateLauncher.launch(fileName)
                            MapExportFormat.GEOJSON -> geoJsonCreateLauncher.launch(fileName)
                            MapExportFormat.PNG,
                            MapExportFormat.HOST_OUTREACH_PNG -> pngCreateLauncher.launch(fileName)
                        }
                    }
                    ExportDelivery.SHARE -> {
                        val shareResult = MapExportEngine.writeShareCacheFile(context, fileName, bytes)
                        isExporting = false
                        shareResult.onSuccess { uri ->
                            MapExportEngine.launchShareChooser(
                                context = context,
                                uri = uri,
                                mimeType = format.mimeType,
                                title = fileName
                            )
                            onDismiss()
                        }.onFailure { err ->
                            errorMessage = err.message ?: "Unable to prepare share file."
                        }
                    }
                }
            } catch (t: Throwable) {
                isExporting = false
                errorMessage = t.message ?: "Export failed."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        containerColor = TacticalSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = stringResource(R.string.export_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.export_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Text(
                    text = stringResource(R.string.export_format_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )

                if (buildKml != null) {
                    ExportFormatOptionCard(
                        title = stringResource(R.string.export_format_kml),
                        description = stringResource(R.string.export_format_kml_desc),
                        icon = Icons.Default.Public,
                        selected = selectedFormat == MapExportFormat.KML,
                        enabled = !isExporting,
                        onClick = {
                            selectedFormat = MapExportFormat.KML
                            errorMessage = null
                        },
                        modifier = Modifier.testTag("export_format_kml")
                    )
                }

                if (buildNodeDbBackupJson != null) {
                    ExportFormatOptionCard(
                        title = stringResource(R.string.export_format_nodedb_json),
                        description = stringResource(R.string.export_format_nodedb_json_desc),
                        icon = Icons.Default.DataObject,
                        selected = selectedFormat == MapExportFormat.NODEDB_JSON,
                        enabled = !isExporting,
                        onClick = {
                            selectedFormat = MapExportFormat.NODEDB_JSON
                            errorMessage = null
                        },
                        modifier = Modifier.testTag("export_format_nodedb_json")
                    )
                }

                ExportFormatOptionCard(
                    title = stringResource(R.string.export_format_geojson),
                    description = stringResource(R.string.export_format_geojson_desc),
                    icon = Icons.Default.Code,
                    selected = selectedFormat == MapExportFormat.GEOJSON,
                    enabled = !isExporting,
                    onClick = {
                        selectedFormat = MapExportFormat.GEOJSON
                        errorMessage = null
                    },
                    modifier = Modifier.testTag("export_format_geojson")
                )

                ExportFormatOptionCard(
                    title = stringResource(R.string.export_format_png),
                    description = stringResource(R.string.export_format_png_desc),
                    icon = Icons.Default.Image,
                    selected = selectedFormat == MapExportFormat.PNG,
                    enabled = !isExporting,
                    onClick = {
                        selectedFormat = MapExportFormat.PNG
                        errorMessage = null
                    },
                    modifier = Modifier.testTag("export_format_png")
                )

                if (buildHostOutreachSpec != null) {
                    ExportFormatOptionCard(
                        title = stringResource(R.string.export_format_host_outreach),
                        description = stringResource(R.string.export_format_host_outreach_desc),
                        icon = Icons.Default.HomeWork,
                        selected = selectedFormat == MapExportFormat.HOST_OUTREACH_PNG,
                        enabled = !isExporting,
                        onClick = {
                            selectedFormat = MapExportFormat.HOST_OUTREACH_PNG
                            errorMessage = null
                        },
                        modifier = Modifier.testTag("export_format_host_outreach")
                    )
                }

                if (isExporting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = NodeDirectGreen,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = stringResource(R.string.export_preparing),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = NodeDirectGreen
                        )
                    }
                }

                errorMessage?.let { msg ->
                    Surface(
                        color = AlertErrorRed.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AlertErrorRed.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertErrorRed,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { triggerExport(ExportDelivery.SAVE_TO_FILES) },
                        enabled = !isExporting,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = NodeMultiHopSky.copy(alpha = 0.12f),
                            contentColor = NodeMultiHopSky
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("export_save_files_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SaveAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.export_save_to_files),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = { triggerExport(ExportDelivery.SHARE) },
                        enabled = !isExporting,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NodeDirectGreen,
                            contentColor = TacticalCanvas
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("export_share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.export_share),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isExporting,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("export_cancel_button")
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = TextSecondary
                )
            }
        }
    )
}

@Composable
private fun ExportFormatOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) NodeDirectGreen else TacticalOutline
    val bgColor = if (selected) NodeDirectGreen.copy(alpha = 0.12f) else TacticalSurfaceElevated

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) NodeDirectGreen else NodeMultiHopSky,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (selected) NodeDirectGreen else TextPrimary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}
