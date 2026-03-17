/*
 * Copyright 2026 by Patryk Goworowski and Patrick Michalik.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.patrykandpatrick.vico.compose.cartesian

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.data.*
import com.patrykandpatrick.vico.compose.cartesian.layer.MutableCartesianLayerDimensions
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController.Lock
import com.patrykandpatrick.vico.compose.cartesian.marker.Interaction
import com.patrykandpatrick.vico.compose.common.*
import com.patrykandpatrick.vico.compose.common.Defaults.CHART_HEIGHT
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val VICO_SWITCH_DEBUG_LOGS = false

private inline fun vicoSwitchDebugLog(message: () -> String) {
  if (VICO_SWITCH_DEBUG_LOGS) println(message())
}

/** Per-frame chart metrics emitted by [CartesianChartHost]. */
public data class ChartFrameMetrics(
  val scroll: Float,
  val maxScroll: Float,
  val zoom: Float,
  val chartBoundsWidth: Float,
)

/**
 * Displays a [CartesianChart].
 *
 * @param chart the [CartesianChart].
 * @param modelProducer creates and updates the [CartesianChartModel].
 * @param modifier the modifier to be applied to the chart.
 * @param scrollState houses information on the [CartesianChart]’s scroll value. Allows for scroll
 *   customization and programmatic scrolling.
 * @param zoomState houses information on the [CartesianChart]’s zoom factor. Allows for zoom
 *   customization.
 * @param animationSpec the [AnimationSpec] for difference animations.
 * @param animateIn whether to run an initial animation when the [CartesianChartHost] enters
 *   composition. The animation is skipped for previews.
 * @param placeholder shown when no [CartesianChartModel] is available.
 * @param onScrollMetricsUpdated called after [VicoScrollState.update] and before drawing.
 * @param onFrameCommitted called after drawing and cache purge.
 */
@Composable
public fun CartesianChartHost(
  chart: CartesianChart,
  modelProducer: CartesianChartModelProducer,
  modifier: Modifier = Modifier,
  scrollState: VicoScrollState = rememberVicoScrollState(),
  zoomState: VicoZoomState = rememberDefaultVicoZoomState(scrollState.scrollEnabled),
  animationSpec: AnimationSpec<Float>? = defaultCartesianDiffAnimationSpec,
  animateIn: Boolean = true,
  placeholder: @Composable BoxScope.() -> Unit = {},
  onScrollMetricsUpdated: ((ChartFrameMetrics) -> Unit)? = null,
  onFrameCommitted: ((ChartFrameMetrics) -> Unit)? = null,
) {
  val mutableRanges = remember { MutableCartesianChartRanges() }
  val modelWrapper by modelProducer.collectAsState(chart, animationSpec, animateIn, mutableRanges)
  val (model, previousModel, ranges, extraStore) = modelWrapper

  CartesianChartHostBox(modifier) {
    if (model != null) {
      CartesianChartHostImpl(
        chart,
        model,
        scrollState,
        zoomState,
        ranges,
        previousModel,
        extraStore,
        onScrollMetricsUpdated,
        onFrameCommitted,
      )
    } else {
      placeholder()
    }
  }
}

/**
 * Displays a [CartesianChart]. This function accepts a [CartesianChartModel]. For dynamic data, use
 * the function overload that accepts a [CartesianChartModelProducer] instance.
 *
 * @param chart the [CartesianChart].
 * @param model the [CartesianChartModel].
 * @param modifier the modifier to be applied to the chart.
 * @param scrollState houses information on the [CartesianChart]’s scroll value. Allows for scroll
 *   customization and programmatic scrolling.
 * @param zoomState houses information on the [CartesianChart]’s zoom factor. Allows for zoom
 *   customization.
 * @param onScrollMetricsUpdated called after [VicoScrollState.update] and before drawing.
 * @param onFrameCommitted called after drawing and cache purge.
 */
@Composable
public fun CartesianChartHost(
  chart: CartesianChart,
  model: CartesianChartModel,
  modifier: Modifier = Modifier,
  scrollState: VicoScrollState = rememberVicoScrollState(),
  zoomState: VicoZoomState = rememberDefaultVicoZoomState(scrollState.scrollEnabled),
  onScrollMetricsUpdated: ((ChartFrameMetrics) -> Unit)? = null,
  onFrameCommitted: ((ChartFrameMetrics) -> Unit)? = null,
) {
  val ranges = remember { MutableCartesianChartRanges() }
  remember(chart, model) {
    ranges.reset()
    chart.updateRanges(ranges, model)
  }
  CartesianChartHostBox(modifier) {
    CartesianChartHostImpl(
      chart,
      model,
      scrollState,
      zoomState,
      ranges.toImmutable(),
      onScrollMetricsUpdated = onScrollMetricsUpdated,
      onFrameCommitted = onFrameCommitted,
    )
  }
}

@Composable
internal fun CartesianChartHostImpl(
  chart: CartesianChart,
  model: CartesianChartModel,
  scrollState: VicoScrollState,
  zoomState: VicoZoomState,
  ranges: CartesianChartRanges,
  previousModel: CartesianChartModel? = null,
  extraStore: ExtraStore = ExtraStore.Empty,
  onScrollMetricsUpdated: ((ChartFrameMetrics) -> Unit)? = null,
  onFrameCommitted: ((ChartFrameMetrics) -> Unit)? = null,
) {
  var markerX by rememberSaveable { mutableStateOf<Double?>(null) }
  var lastAcceptedInteraction by
    rememberSaveable(saver = Interaction.Saver) { mutableStateOf(null) }
  val measuringContext =
    rememberCartesianMeasuringContext(
      extraStore = extraStore,
      model = model,
      ranges = ranges,
      scrollEnabled = scrollState.scrollEnabled,
      zoomEnabled = scrollState.scrollEnabled && zoomState.zoomEnabled,
      layerPadding =
        remember(chart.layerPadding, model.extraStore) { chart.layerPadding(model.extraStore) },
      markerX = markerX,
    )

  val coroutineScope = rememberCoroutineScope()
  var lastHandledModel by remember { ValueWrapper(model) }
  val layerDimensions = remember { MutableCartesianLayerDimensions() }
  var debugLastMaxValue by remember { mutableFloatStateOf(Float.NaN) }
  var debugFrameCounter by remember { mutableIntStateOf(0) }
  var debugLastPreparedWidth by remember { mutableFloatStateOf(Float.NaN) }
  var debugLastPreparedLayerBounds by remember { mutableStateOf(Rect.Zero) }
  var debugLastPreparedXSpacing by remember { mutableFloatStateOf(Float.NaN) }
  var debugLastDrawScroll by remember { mutableFloatStateOf(Float.NaN) }
  var debugLastDrawMax by remember { mutableFloatStateOf(Float.NaN) }
  var debugLastDrawZoom by remember { mutableFloatStateOf(Float.NaN) }
  var debugLastSeriesCount by remember { mutableIntStateOf(-1) }
  var debugLastSeriesFirstX by remember { mutableStateOf(Double.NaN) }
  var debugLastSeriesLastX by remember { mutableStateOf(Double.NaN) }
  var debugLastSeriesMinY by remember { mutableStateOf(Double.NaN) }
  var debugLastSeriesMaxY by remember { mutableStateOf(Double.NaN) }

  val onInteraction =
    remember(chart, layerDimensions, scrollState, ranges) {
      if (chart.marker != null) {
        { interaction: Interaction ->
          val x =
            measuringContext.value.pointerPositionToX(
              interaction.point,
              layerDimensions,
              chart.layerBounds,
              scrollState.value,
              ranges,
            )
          val targets =
            chart.getMarkerTargets(
              x,
              measuringContext.value.getVisibleXRange(
                layerDimensions,
                chart.layerBounds,
                scrollState.value,
              ),
            )
          if (chart.markerController.shouldAcceptInteraction(interaction, targets)) {
            val shouldShow = chart.markerController.shouldShowMarker(interaction, targets)
            lastAcceptedInteraction = interaction
            markerX = if (shouldShow) targets.firstOrNull()?.x else null
          }
        }
      } else {
        null
      }
    }

  fun onViewportChange() {
    lastAcceptedInteraction
      ?.takeIf { chart.markerController.lock == Lock.Position }
      ?.let { onInteraction?.invoke(it) }
  }

  LaunchedEffect(model) { onViewportChange() }

  LaunchedEffect(scrollState.consumedXDeltas, scrollState.unconsumedXDeltas) {
    merge(scrollState.consumedXDeltas, scrollState.unconsumedXDeltas).collect { onViewportChange() }
  }

  LaunchedEffect(zoomState, scrollState) {
    zoomState.pendingScroll.collect { (scroll, maxValue) ->
      vicoSwitchDebugLog {
        "VICO pendingScroll before: scrollValue=${scrollState.value} maxValue=${scrollState.maxValue} incomingMax=$maxValue"
      }
      scrollState.scroll(scroll, maxValue)
      vicoSwitchDebugLog {
        "VICO pendingScroll after: scrollValue=${scrollState.value} maxValue=${scrollState.maxValue}"
      }
      onViewportChange()
    }
  }

  DisposableEffect(scrollState) { onDispose { scrollState.clearUpdated() } }

  Canvas(
    modifier =
      Modifier.fillMaxSize()
        .pointerInput(
          scrollState = scrollState,
          consumeMoveEvents = chart.markerController.consumeMoveEvents,
          onInteraction = onInteraction,
          onZoom =
            remember(zoomState, scrollState, coroutineScope) {
              if (zoomState.zoomEnabled) {
                { factor, centroid ->
                  coroutineScope.launch { zoomState.zoom(factor, centroid.x) { scrollState.value } }
                }
              } else {
                null
              }
            },
          longPressEnabled = chart.markerController.acceptsLongPress,
        )
  ) {
    if (size.isEmpty()) return@Canvas
    debugFrameCounter += 1
    measuringContext.value.canvasSize = size

    layerDimensions.clear()
    chart.prepare(measuringContext.value, layerDimensions)
    val preparedChanged =
      debugLastPreparedWidth.isNaN() ||
        abs(size.width - debugLastPreparedWidth) >= 0.5f ||
        chart.layerBounds != debugLastPreparedLayerBounds ||
        debugLastPreparedXSpacing.isNaN() ||
        abs(layerDimensions.xSpacing - debugLastPreparedXSpacing) >= 0.01f
    if (preparedChanged) {
      vicoSwitchDebugLog {
        "VICO frame=$debugFrameCounter prepared: width=${size.width} layerBounds=${chart.layerBounds} xSpacing=${layerDimensions.xSpacing}"
      }
      debugLastPreparedWidth = size.width
      debugLastPreparedLayerBounds = chart.layerBounds
      debugLastPreparedXSpacing = layerDimensions.xSpacing
    }

    if (chart.layerBounds.isEmpty) return@Canvas

    val candlestickModel = model.models.filterIsInstance<CandlestickCartesianLayerModel>().firstOrNull()
    val seriesCount = candlestickModel?.series?.size ?: 0
    val seriesFirstX = candlestickModel?.minX ?: Double.NaN
    val seriesLastX = candlestickModel?.maxX ?: Double.NaN
    val seriesMinY = candlestickModel?.minY ?: Double.NaN
    val seriesMaxY = candlestickModel?.maxY ?: Double.NaN
    val modelDataChanged =
      seriesCount != debugLastSeriesCount ||
        seriesFirstX != debugLastSeriesFirstX ||
        seriesLastX != debugLastSeriesLastX ||
        seriesMinY != debugLastSeriesMinY ||
        seriesMaxY != debugLastSeriesMaxY
    if (modelDataChanged) {
      vicoSwitchDebugLog {
        "VICO frame=$debugFrameCounter modelData: count=$seriesCount firstX=$seriesFirstX lastX=$seriesLastX " +
          "minY=$seriesMinY maxY=$seriesMaxY"
      }
      debugLastSeriesCount = seriesCount
      debugLastSeriesFirstX = seriesFirstX
      debugLastSeriesLastX = seriesLastX
      debugLastSeriesMinY = seriesMinY
      debugLastSeriesMaxY = seriesMaxY
    }

    val maxBeforeUpdate = scrollState.maxValue
    val scrollBeforeUpdate = scrollState.value
    zoomState.update(measuringContext.value, layerDimensions, chart.layerBounds, scrollState.value)
    scrollState.update(measuringContext.value, chart.layerBounds, layerDimensions)
    onScrollMetricsUpdated?.invoke(
      ChartFrameMetrics(
        scroll = scrollState.value,
        maxScroll = scrollState.maxValue,
        zoom = zoomState.value,
        chartBoundsWidth = chart.layerBounds.width,
      )
    )
    if (
      model != lastHandledModel ||
        debugLastMaxValue.isNaN() ||
        abs(scrollState.maxValue - debugLastMaxValue) >= 0.5f
    ) {
      vicoSwitchDebugLog {
        "VICO frame=$debugFrameCounter updated: scrollBefore=$scrollBeforeUpdate maxBefore=$maxBeforeUpdate " +
          "scrollNow=${scrollState.value} maxNow=${scrollState.maxValue} zoom=${zoomState.value} modelChanged=${model != lastHandledModel}"
      }
      debugLastMaxValue = scrollState.maxValue
    }

    if (model != lastHandledModel) {
      vicoSwitchDebugLog {
        "VICO frame=$debugFrameCounter modelChanged: triggering autoScroll " +
          "previousWidth=${previousModel?.width} currentWidth=${model.width}"
      }
      coroutineScope.launch { scrollState.autoScroll(model, previousModel) }
      lastHandledModel = model
    }

    val drawingContext =
      CartesianDrawingContext(
        measuringContext.value,
        drawContext.canvas,
        layerDimensions,
        chart.layerBounds,
        scrollState.value,
        zoomState.value,
        MutableDrawScope(this),
      )

    val drawChanged =
      debugLastDrawScroll.isNaN() ||
        debugLastDrawMax.isNaN() ||
        debugLastDrawZoom.isNaN() ||
        abs(scrollState.value - debugLastDrawScroll) >= 0.5f ||
        abs(scrollState.maxValue - debugLastDrawMax) >= 0.5f ||
        abs(zoomState.value - debugLastDrawZoom) >= 1e-4f
    if (drawChanged) {
      vicoSwitchDebugLog {
        "VICO frame=$debugFrameCounter draw: scroll=${scrollState.value} max=${scrollState.maxValue} zoom=${zoomState.value}"
      }
      debugLastDrawScroll = scrollState.value
      debugLastDrawMax = scrollState.maxValue
      debugLastDrawZoom = zoomState.value
    }
    chart.draw(drawingContext)
    measuringContext.value.cacheStore.purge()
    onFrameCommitted?.invoke(
      ChartFrameMetrics(
        scroll = scrollState.value,
        maxScroll = scrollState.maxValue,
        zoom = zoomState.value,
        chartBoundsWidth = chart.layerBounds.width,
      )
    )
  }
}

@Composable
private fun CartesianChartHostBox(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(modifier = modifier.heightIn(max = CHART_HEIGHT.dp).fillMaxWidth(), content = content)
}
