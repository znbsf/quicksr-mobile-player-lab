package dev.aisystems.quicksrplayerlab;

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;

/** Pins Media3 effects to the nonlinear SDR working-color contract used by Anime4K. */
@UnstableApi
final class DefaultWorkingColorRenderersFactory extends DefaultRenderersFactory {
    static final int SDR_WORKING_COLOR_SPACE =
            DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT;

    DefaultWorkingColorRenderersFactory(Context context) {
        super(context);
    }

    @Override
    protected void buildVideoRenderers(
            Context context,
            @ExtensionRendererMode int extensionRendererMode,
            MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            long allowedVideoJoiningTimeMs,
            ArrayList<Renderer> out) {
        if (extensionRendererMode != EXTENSION_RENDERER_MODE_OFF) {
            throw new IllegalStateException(
                    "Explicit working-color renderer does not support extension video renderers");
        }
        MediaCodecVideoRenderer.Builder builder = new MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(getCodecAdapterFactory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
        out.add(new DefaultWorkingColorVideoRenderer(builder));
    }

    private static final class DefaultWorkingColorVideoRenderer extends MediaCodecVideoRenderer {
        DefaultWorkingColorVideoRenderer(MediaCodecVideoRenderer.Builder builder) {
            super(builder);
        }

        @Override
        protected PlaybackVideoGraphWrapper createPlaybackVideoGraphWrapper(
                Context context,
                VideoFrameReleaseControl videoFrameReleaseControl) {
            VideoFrameProcessor.Factory frameProcessorFactory =
                    new DefaultVideoFrameProcessor.Factory.Builder()
                            .setSdrWorkingColorSpace(SDR_WORKING_COLOR_SPACE)
                            .build();
            return new PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
                    .setVideoGraphFactory(new SingleInputVideoGraph.Factory(frameProcessorFactory))
                    .setEnablePlaylistMode(true)
                    .setClock(getClock())
                    .build();
        }
    }
}
