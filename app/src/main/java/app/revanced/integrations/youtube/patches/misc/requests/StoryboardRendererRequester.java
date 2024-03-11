package app.revanced.integrations.youtube.patches.misc.requests;

import static app.revanced.integrations.youtube.patches.misc.requests.PlayerRoutes.ANDROID_INNER_TUBE_BODY;
import static app.revanced.integrations.youtube.patches.misc.requests.PlayerRoutes.GET_STORYBOARD_SPEC_RENDERER;
import static app.revanced.integrations.youtube.patches.misc.requests.PlayerRoutes.TV_EMBED_INNER_TUBE_BODY;
import static app.revanced.integrations.youtube.patches.misc.requests.PlayerRoutes.WEB_INNER_TUBE_BODY;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import app.revanced.integrations.youtube.patches.misc.StoryboardRenderer;
import app.revanced.integrations.youtube.requests.Requester;
import app.revanced.integrations.youtube.utils.LogHelper;
import app.revanced.integrations.youtube.utils.ReVancedUtils;

public class StoryboardRendererRequester {

    /**
     * For videos that have no storyboard.
     * Usually for low resolution videos as old as YouTube itself.
     * Does not include paid videos where the renderer fetch fails.
     */
    private static final StoryboardRenderer emptyStoryboard
            = new StoryboardRenderer(null, false, null);

    private StoryboardRendererRequester() {
    }

    private static void handleConnectionError(@NonNull String toastMessage, @Nullable Exception ex,
                                              boolean showToastOnIOException) {
        // if (showToastOnIOException) ReVancedUtils.showToastShort(toastMessage);
        LogHelper.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static JSONObject fetchPlayerResponse(@NonNull String requestBody, boolean showToastOnIOException) {
        final long startTime = System.currentTimeMillis();
        try {
            ReVancedUtils.verifyOffMainThread();
            Objects.requireNonNull(requestBody);

            final byte[] innerTubeBody = requestBody.getBytes(StandardCharsets.UTF_8);

            HttpURLConnection connection = PlayerRoutes.getPlayerResponseConnectionFromRoute(GET_STORYBOARD_SPEC_RENDERER);
            connection.getOutputStream().write(innerTubeBody, 0, innerTubeBody.length);

            final int responseCode = connection.getResponseCode();
            if (responseCode == 200) return Requester.parseJSONObject(connection);

            // Always show a toast for this, as a non 200 response means something is broken.
            handleConnectionError("Spoof storyboard not available: " + responseCode,
                    null, showToastOnIOException);
            connection.disconnect();
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Spoof storyboard temporarily not available (API timed out)",
                    ex, showToastOnIOException);
        } catch (IOException ex) {
            handleConnectionError("Spoof storyboard temporarily not available: " + ex.getMessage(),
                    ex, showToastOnIOException);
        } catch (Exception ex) {
            LogHelper.printException(() -> "Spoof storyboard fetch failed", ex); // Should never happen.
        } finally {
            LogHelper.printDebug(() -> "Request took: " + (System.currentTimeMillis() - startTime) + "ms");
        }

        return null;
    }

    private static String getPlayabilityStatus(@NonNull JSONObject playerResponse) {
        try {
            return playerResponse.getJSONObject("playabilityStatus").getString("status");
        } catch (JSONException e) {
            LogHelper.printDebug(() -> "Failed to get playabilityStatus for response: " + playerResponse);
        }

        // Prevent NullPointerException
        return "";
    }

    /**
     * Fetches the storyboardRenderer from the innerTubeBody.
     *
     * @param innerTubeBody The innerTubeBody to use to fetch the storyboardRenderer.
     * @return StoryboardRenderer or null if playabilityStatus is not OK.
     */
    @Nullable
    private static StoryboardRenderer getStoryboardRendererUsingBody(@NonNull String innerTubeBody,
                                                                     boolean showToastOnIOException,
                                                                     @NonNull String videoId) {
        final JSONObject playerResponse = fetchPlayerResponse(innerTubeBody, showToastOnIOException);
        if (playerResponse == null)
            return null;

        final String playabilityStatus = getPlayabilityStatus(playerResponse);

        if (playabilityStatus.equals("OK"))
            return getStoryboardRendererUsingResponse(playerResponse);

        // Get the StoryboardRenderer from Premieres Video.
        // In Android client, YouTube used weird base64-like encoding for PlayerResponse.
        // So additional fetching with WEB client is required for getting unSerialized ones.
        if (playabilityStatus.equals("LIVE_STREAM_OFFLINE"))
            return getTrailerStoryboardRenderer(videoId);
        return null;
    }

    @Nullable
    private static StoryboardRenderer getTrailerStoryboardRenderer(@NonNull String videoId) {
        try {
            final JSONObject playerResponse = fetchPlayerResponse(
                    String.format(WEB_INNER_TUBE_BODY, videoId), false);

            if (playerResponse == null)
                return null;

            JSONObject unSerializedPlayerResponse = playerResponse.getJSONObject("playabilityStatus")
                    .getJSONObject("errorScreen").getJSONObject("ypcTrailerRenderer").getJSONObject("unserializedPlayerResponse");

            if (getPlayabilityStatus(unSerializedPlayerResponse).equals("OK"))
                return getStoryboardRendererUsingResponse(unSerializedPlayerResponse);
            return null;
        } catch (JSONException e) {
            LogHelper.printException(() -> "Failed to get unserializedPlayerResponse", e);
        }

        return null;
    }

    @Nullable
    private static StoryboardRenderer getStoryboardRendererUsingResponse(@NonNull JSONObject playerResponse) {
        try {
            LogHelper.printDebug(() -> "Parsing storyboardRenderer from response: " + playerResponse);
            if (!playerResponse.has("storyboards")) {
                LogHelper.printDebug(() -> "Using empty storyboard");
                return emptyStoryboard;
            }
            final JSONObject storyboards = playerResponse.getJSONObject("storyboards");
            final boolean isLiveStream = storyboards.has("playerLiveStoryboardSpecRenderer");
            final String storyboardsRendererTag = isLiveStream
                    ? "playerLiveStoryboardSpecRenderer"
                    : "playerStoryboardSpecRenderer";

            final var rendererElement = storyboards.getJSONObject(storyboardsRendererTag);
            StoryboardRenderer renderer = new StoryboardRenderer(
                    rendererElement.getString("spec"),
                    isLiveStream,
                    rendererElement.has("recommendedLevel")
                            ? rendererElement.getInt("recommendedLevel")
                            : null
            );

            LogHelper.printDebug(() -> "Fetched: " + renderer);

            return renderer;
        } catch (JSONException e) {
            LogHelper.printException(() -> "Failed to get storyboardRenderer", e);
        }

        return null;
    }

    @Nullable
    public static StoryboardRenderer getStoryboardRenderer(@NonNull String videoId) {
        Objects.requireNonNull(videoId);

        StoryboardRenderer renderer = getStoryboardRendererUsingBody(
                String.format(ANDROID_INNER_TUBE_BODY, videoId), false, videoId);
        if (renderer == null) {
            LogHelper.printDebug(() -> videoId + " not available using Android client");
            renderer = getStoryboardRendererUsingBody(
                    String.format(TV_EMBED_INNER_TUBE_BODY, videoId, videoId), true, videoId);
            if (renderer == null) {
                LogHelper.printDebug(() -> videoId + " not available using TV embedded client");
            }
        }

        return renderer;
    }
}