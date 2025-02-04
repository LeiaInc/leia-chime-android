/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.audiovideo

import com.amazonaws.services.chime.sdk.meetings.audiovideo.metric.MetricsObserver
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.RemoteVideoSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSubscriptionConfiguration
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.DefaultCameraCaptureSource
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionCredentials

/**
 * [AudioVideoControllerFacade] manages the signaling and peer connections.
 */
interface AudioVideoControllerFacade {
    /**
     * Starts audio and video.
     */
    fun start()

    /**
     * Starts audio and video with the given configuration.
     *
     * @param audioVideoConfiguration: [AudioVideoConfiguration] - The configuration to be used for
     * audio and video during a meeting session.
     */
    fun start(audioVideoConfiguration: AudioVideoConfiguration)

    /**
     * Stops audio and video.
     * It's important to call this when your meeting connection is no longer needed
     * in order to clean up and explicitly release resources.
     */
    fun stop()

    /**
     * Subscribe to audio, video, and connection events with an [AudioVideoObserver].
     *
     * @param observer: [AudioVideoObserver] - The observer to subscribe to events with.
     */
    fun addAudioVideoObserver(observer: AudioVideoObserver)

    /**
     * Unsubscribes from audio, video, and connection events by removing specified [AudioVideoObserver].
     *
     * @param observer: [AudioVideoObserver] - The observer to unsubscribe from events with.
     */
    fun removeAudioVideoObserver(observer: AudioVideoObserver)

    /**
     * Subscribe to metrics events with an [MetricsObserver].
     *
     * @param observer: [MetricsObserver] - The observer to subscribe to events with.
     */
    fun addMetricsObserver(observer: MetricsObserver)

    /**
     * Unsubscribes from metrics by removing specified [MetricsObserver].
     *
     * @param observer: [MetricsObserver] - The observer to unsubscribe from events with.
     */
    fun removeMetricsObserver(observer: MetricsObserver)

    /**
     * Start local video and begin transmitting frames from an internally held [DefaultCameraCaptureSource].
     * [stopLocalVideo] will stop the internal capture source if being used.
     *
     * Calling this after passing in a custom [VideoSource] will replace it with the internal capture source.
     *
     * This function will only have effect if [start] has already been called
     */
    fun startLocalVideo()

    /**
     * Start local video with a provided custom [VideoSource] which can be used to provide custom
     * [VideoFrame] objects to be transmitted to remote clients
     *
     * Calling this function repeatedly will replace the previous [VideoSource] as the one being
     * transmitted. It will also stop and replace the internal capture source if [startLocalVideo]
     * was called with no arguments.
     *
     * Read [custom video guide](https://github.com/aws/amazon-chime-sdk-android/blob/master/guides/custom_video.md) for details.
     *
     * @param source: [VideoSource] - The source of video frames to be sent to other clients
     */
    fun startLocalVideo(source: VideoSource)

    /**
     * Stops sending video for local attendee. This will additionally stop the internal capture source if being used.
     * If using a custom video source, this will call [VideoSource.removeVideoSink] on the previously provided source.
     */
    fun stopLocalVideo()

    /**
     * Start remote video.
     */
    fun startRemoteVideo()

    /**
     * Stop remote video.
     */
    fun stopRemoteVideo()

    /**
     * Add, update, or remove subscriptions to remote video sources provided via `remoteVideoSourcesDidBecomeAvailable`.
     *
     * This function requires using the `RemoteVideoSource` provided by `OnRemoteVideoSourcesAvailable`, otherwise it will not update properly.
     * This is what allows to use the `RemoteVideoSource` objects as keys in a map.
     *
     * Including a `RemoteVideoSource` in `addedOrUpdated` which was not previously provided will result in the negotiation of media flow for that source. After negotiation has
     * completed,`videoTileDidAdd` on the tile controller will be called with the `TileState` of the source, and applications
     * can render the video via 'bindVideoTile'. Reincluding a `RemoteVideoSource` can be done to update the provided `VideoSubscriptionConfiguration`,
     * but it is not necessary to continue receiving frames.
     *
     * Including a `RemoteVideoSource` in `removed` will stop the flow video from that source, and lead to a `videoTileDidRemove` call on the
     * tile controller to indicate to the application that the tile should be unbound. To restart the flow of media, the source should be re-added by
     * including in `addedOrUpdated`. Note that videos no longer available in a meeting (i.e. listed in
     * `remoteVideoSourcesDidBecomeUnavailable`) do not need to be removed, as they will be automatically unsubscribed from.
     *
     * Note that before this function is called for the first time, the client will automatically subscribe to all video sources.
     * However this behavior will cease upon first call (e.g. if there are 10 videos in the meeting, the controller will subscribe to all 10, however if
     * `updateVideoSourceSubscriptions` is called with a single video in `addedOrUpdated`, the client will unsubscribe from the other 9.
     * This automatic subscription behavior may be removed in future major version updates, builders should avoid relying on the logic
     * and instead explicitly call `updateVideoSourceSubscriptions` with the sources they want to receive.
     *
     * @param addedOrUpdated: [Map] - updated or added video source configurations.
     * @param removed: [Array] - video sources to remove.
     */
    fun updateVideoSourceSubscriptions(addedOrUpdated: Map<RemoteVideoSource, VideoSubscriptionConfiguration>, removed: Array<RemoteVideoSource>)

    /**
     * Allows an attendee in a Replica meeting to immediately transition to a Primary meeting attendee
     * without need for reconnection.
     *
     *  [PrimaryMeetingPromotionObserver.onPrimaryMeetingPromotion] will be called exactly once on `observer` for each call. If
     *  the promotion is successful,  [PrimaryMeetingPromotionObserver.onPrimaryMeetingDemotion] will be called exactly once
     *  if/when the attendee is demoted. See the observer documentation for possible status codes.
     *
     * Application code may also receive a callback on `AudioVideoObserver.onVideoSessionStarted` without
     * [MeetingSessionStatusCode.VideoAtCapacityViewOnly] to indicate they can begin to share video.
     *
     * `chime::DeleteAttendee` on the Primary meeting attendee will result in [PrimaryMeetingPromotionObserver.onPrimaryMeetingDemotion]
     * being called on `observer` to indicate the attendee is no longer able to share.
     *
     * Any disconnection will trigger an automatic demotion to avoid unexpected or unwanted promotion state on reconnection.
     * This will also call [PrimaryMeetingPromotionObserver.onPrimaryMeetingDemotion];  if the attendee still needs to be
     * an interactive participant in the Primary meeting, [promoteToPrimaryMeeting] should be called again with the same credentials.
     *
     * Note that given the asynchronous nature of this function, this should not be called a second time before
     * [PrimaryMeetingPromotionObserver.onPrimaryMeetingPromotion] is called for the first time. Doing so may result in unexpected
     * behavior.
     *
     * @param credentials The credentials for the primary meeting.  This needs to be obtained out of band.
     * @param observer Function to be called on promotion and possibly demotion; see note above.
     */
    fun promoteToPrimaryMeeting(credentials: MeetingSessionCredentials, observer: PrimaryMeetingPromotionObserver)

    /**
     * Remove the promoted attendee from the Primary meeting. This client will stop sharing audio, video, and data messages.
     * This will revert the end-user to precisely the state they were before a call to [promoteToPrimaryMeeting]
     *
     * This will have no effect if there was no previous successful call to [promoteToPrimaryMeeting]. This
     * may result in [PrimaryMeetingPromotionObserver.onPrimaryMeetingDemotion] but there is no need to wait for that callback
     * to revert UX, etc.
     */
    fun demoteFromPrimaryMeeting()
}
