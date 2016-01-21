/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge;

import org.jitsi.util.*;
import org.jitsi.videobridge.ratecontrol.*;

import java.util.*;

/**
 * Manages the set of <tt>Endpoint</tt>s whose video streams are being
 * forwarded to a specific <tt>VideoChannel</tt> (i.e. the
 * <tt>VideoChannel</tt>'s <tt>LastN</tt> set).
 *
 * @author Lyubomir Marinov
 * @author George Politis
 * @author Boris Grozev
 */
public class LastNController
{
    /**
     * The <tt>Logger</tt> used by the <tt>VideoChannel</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
         = Logger.getLogger(LastNController.class);

    /**
     * An empty list instance.
     */
    private static final List<String> INITIAL_EMPTY_LIST
            = Collections.unmodifiableList(new LinkedList<String>());

    /**
     * The set of <tt>Endpoints</tt> whose video streams are currently being
     * forwarded.
     */
    private List<String> forwardedEndpoints = INITIAL_EMPTY_LIST;

    /**
     * The list of all <tt>Endpoint</tt>s in the conference, ordered by the
     * last time they were elected dominant speaker.
     */
    private List<String> conferenceSpeechActivityEndpoints = INITIAL_EMPTY_LIST;

    /**
     * The list of endpoints which have been explicitly marked as 'pinned'
     * and whose video streams should always be forwarded.
     */
    private List<String> pinnedEndpoints = INITIAL_EMPTY_LIST;

    /**
     * The maximum number of endpoints whose video streams will be forwarded
     * to the endpoint. A value of {@code -1} means that there is no limit, and
     * all endpoints' video streams will be forwarded.
     */
    private int lastN = -1;

    /**
     * Whether or not adaptive lastN is in use.
     */
    private boolean adaptiveLastN = false;

    /**
     * Whether or not adaptive simulcast is in use.
     */
    private boolean adaptiveSimulcast = false;

    /**
     * The instance which implements <tt>Adaptive LastN</tt> or
     * <tt>Adaptive Simulcast</tt> on our behalf.
     */
    private BitrateController bitrateController = null;

    /**
     * The {@link VideoChannel} which owns this {@link LastNController}.
     */
    private final VideoChannel channel;

    /**
     * The ID of the endpoint of {@link #channel}.
     */
    private String endpointId;

    /**
     * Initializes a new {@link LastNController} instance which is to belong
     * to a particular {@link VideoChannel}.
     * @param channel the owning {@link VideoChannel}.
     */
    public LastNController(VideoChannel channel)
    {
        this.channel = channel;
    }

    /**
     * @return the maximum number of endpoints whose video streams will be
     * forwarded to the endpoint. A value of {@code -1} means that there is no
     * limit.
     */
    public int getLastN()
    {
        return lastN;
    }

    /**
     * @return the set of <tt>Endpoints</tt> whose video streams are currently
     * being forwarded.
     */
    public List<String> getForwardedEndpoints()
    {
        return forwardedEndpoints;
    }

    /**
     * Sets the value of {@code lastN}, that is, the maximum number of endpoints
     * whose video streams will be forwarded to the endpoint. A value of
     * {@code -1} means that there is no limit.
     * @param lastN the value to set.
     */
    public void setLastN(int lastN)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Setting lastN=" + lastN);
        }

        List<String> endpointsToAskForKeyframe = null;
        synchronized (this)
        {
            // Since we have the lock anyway, call update() inside, so it
            // doesn't have to obtain it again. But keep the call to
            // askForKeyframes() outside.

            if (this.lastN != lastN)
            {
                // If we're just now enabling lastN, we don't need to ask for
                // keyframes as all streams were being forwarded already.
                boolean update = this.lastN != -1;

                this.lastN = lastN;

                if (update)
                {
                    endpointsToAskForKeyframe = update();
                }
            }
        }

        askForKeyframes(endpointsToAskForKeyframe);
    }

    /**
     * Sets the list of "pinned" endpoints (i.e. endpoints for which video
     * should always be forwarded, regardless of {@code lastN}).
     * @param newPinnedEndpointIds the list of endpoint IDs to set.
     */
    public void setPinnedEndpointIds(List<String> newPinnedEndpointIds)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Setting pinned endpoints: "
                                 + newPinnedEndpointIds.toString());
        }
        List<String> endpointsToAskForKeyframe = null;
        synchronized (this)
        {
            // Since we have the lock anyway, call update() inside, so it
            // doesn't have to obtain it again. But keep the call to
            // askForKeyframes() outside.
            if (!pinnedEndpoints.equals(newPinnedEndpointIds))
            {
                pinnedEndpoints
                        = Collections.unmodifiableList(newPinnedEndpointIds);

                endpointsToAskForKeyframe = update();
            }
        }

        askForKeyframes(endpointsToAskForKeyframe);
    }

    /**
     * Checks whether RTP packets from {@code sourceChannel} should be forwarded
     * to {@link #channel}.
     * @param sourceChannel the channel.
     * @return {@code true} iff RTP packets from {@code sourceChannel} should
     * be forwarded to {@link #channel}.
     */
    public boolean isForwarded(Channel sourceChannel)
    {
        if (lastN < 0)
        {
            // If Last-N is disabled, we forward everything.
            return true;
        }

        if (sourceChannel == null)
        {
            logger.warn("Invalid sourceChannel: null.");
            return false;
        }

        Endpoint channelEndpoint = sourceChannel.getEndpoint();
        if (channelEndpoint == null)
        {
            logger.warn("sourceChannel has no endpoint.");
            return false;
        }

        if (forwardedEndpoints == INITIAL_EMPTY_LIST)
        {
            // LastN is enabled, but we haven't yet initialized the list of
            // endpoints in the conference.
            initializeConferenceEndpoints();
        }

        // This may look like a place to optimize, because we query an unordered
        // list (in O(n)) and it executes on each video packet if lastN is
        // enabled. However, the size of  forwardedEndpoints is restricted to
        // lastN and so small enough that it is not worth optimizing.
        return forwardedEndpoints.contains(channelEndpoint.getID());
    }

    /**
     * @return the number of streams currently being forwarded.
     */
    public int getN()
    {
        return forwardedEndpoints.size();
    }

    /**
     * @return the list of "pinned" endpoints.
     */
    public List<String> getPinnedEndpoints()
    {
        return pinnedEndpoints;
    }

    /**
     * Notifies this instance that the ordered list of endpoints in the
     * conference has changed.
     *
     * @param endpoints the new ordered list of endpoints in the conference.
     * @return the list of endpoints which were added to the list of forwarded
     * endpoints as a result of the call, or {@code null} if none were added.
     */
    public List<Endpoint> speechActivityEndpointsChanged(
            List<Endpoint> endpoints)
    {
        List<String> newEndpointIdList = getIDs(endpoints);
        List<String> enteringEndpointIds
            = speechActivityEndpointIdsChanged(newEndpointIdList);

        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "New list of conference endpoints: "
                    + newEndpointIdList.toString() + "; entering endpoints: "
                    + (enteringEndpointIds == null ? "none" :
                        enteringEndpointIds.toString()));
        }

        List<Endpoint> ret = new LinkedList<>();
        if (enteringEndpointIds != null)
        {
            for (Endpoint endpoint : endpoints)
            {
                if (enteringEndpointIds.contains(endpoint.getID()))
                {
                    ret.add(endpoint);
                }
            }
        }

        return ret;
    }

    /**
     * Notifies this instance that the ordered list of endpoints (specified
     * as a list of endpoint IDs) in the conference has changed.
     *
     * @param endpointIds the new ordered list of endpoints (specified as a list
     * of endpoint IDs) in the conference.
     * @return the list of IDs of endpoints which were added to the list of
     * forwarded endpoints as a result of the call.
     */
    private synchronized List<String> speechActivityEndpointIdsChanged(
            List<String> endpointIds)
    {
        if (conferenceSpeechActivityEndpoints.equals(endpointIds))
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Conference endpoints have not changed.");
            }
            return null;
        }
        else
        {
            List<String> newEndpoints = new LinkedList<>(endpointIds);
            newEndpoints.removeAll(conferenceSpeechActivityEndpoints);

            conferenceSpeechActivityEndpoints = endpointIds;

            return update(newEndpoints);
        }
    }

    /**
     * Enables or disables the "adaptive last-n" mode, depending on the value of
     * {@code adaptiveLastN}.
     * @param adaptiveLastN {@code true} to enable, {@code false} to disable
     */
    public void setAdaptiveLastN(boolean adaptiveLastN)
    {
        if (this.adaptiveLastN != adaptiveLastN)
        {
            if (adaptiveLastN && bitrateController == null)
            {
                bitrateController = new BitrateController(this, channel);
            }

            this.adaptiveLastN = adaptiveLastN;
        }
    }

    /**
     * Enables or disables the "adaptive simulcast" mod, depending on the value
     * of {@code adaptiveLastN}.
     * @param adaptiveSimulcast {@code true} to enable, {@code false} to
     * disable.
     */
    public void setAdaptiveSimulcast(boolean adaptiveSimulcast)
    {
        if (this.adaptiveSimulcast != adaptiveSimulcast)
        {
            if (adaptiveSimulcast && bitrateController == null)
            {
                bitrateController = new BitrateController(this, channel);
            }

            this.adaptiveSimulcast = adaptiveSimulcast;
        }
    }

    /**
     * @return {@code true} iff the "adaptive last-n" mode is enabled.
     */
    public boolean getAdaptiveLastN()
    {
        return adaptiveLastN;
    }

    /**
     * @return {@code true} iff the "adaptive simulcast" mode is enabled.
     */
    public boolean getAdaptiveSimulcast()
    {
        return adaptiveSimulcast;
    }

    /**
     * Recalculates the list of forwarded endpoints based on the current values
     * of the various parameters of this instance ({@link #lastN},
     * {@link #conferenceSpeechActivityEndpoints}, {@link #pinnedEndpoints}).
     *
     * @return the list of IDs of endpoints which were added to
     * {@link #forwardedEndpoints} (i.e. of endpoints * "entering last-n") as a
     * result of this call. Returns {@code null} if no endpoints were added.
     */
    private synchronized List<String> update()
    {
        return update(null);
    }

    /**
     * Recalculates the list of forwarded endpoints based on the current values
     * of the various parameters of this instance ({@link #lastN},
     * {@link #conferenceSpeechActivityEndpoints}, {@link #pinnedEndpoints}).
     *
     * @param newConferenceEndpoints A list of endpoints which entered the
     * conference since the last call to this method. They need not be asked
     * for keyframes, because they were never filtered by this
     * {@link #LastNController(VideoChannel)}.
     *
     * @return the list of IDs of endpoints which were added to
     * {@link #forwardedEndpoints} (i.e. of endpoints * "entering last-n") as a
     * result of this call. Returns {@code null} if no endpoints were added.
     */
    private synchronized List<String> update(List<String> newConferenceEndpoints)
    {
        List<String> newForwardedEndpoints = new LinkedList<>();
        String ourEndpointId = getEndpointId();

        if (conferenceSpeechActivityEndpoints == INITIAL_EMPTY_LIST)
        {
            conferenceSpeechActivityEndpoints
                = getIDs(channel.getConferenceSpeechActivity().getEndpoints());
            newConferenceEndpoints = conferenceSpeechActivityEndpoints;
        }

        if (lastN < 0)
        {
            // Last-N is disabled, we forward everything.
            newForwardedEndpoints.addAll(conferenceSpeechActivityEndpoints);
            if (ourEndpointId != null)
            {
                newForwardedEndpoints.remove(ourEndpointId);
            }
        }
        else
        {
            // Pinned endpoints are always forwarded.
            newForwardedEndpoints.addAll(getPinnedEndpoints());
            // As long as they are still endpoints in the conference.
            newForwardedEndpoints.retainAll(conferenceSpeechActivityEndpoints);

            if (newForwardedEndpoints.size() > lastN)
            {
                // What do we want in this case? It looks like a contradictory
                // request from the client, but maybe it makes for a good API
                // on the client to allow the pinned to override last-n.
                // Unfortunately, this will not play well with Adaptive-Last-N
                // or changes to Last-N for other reasons.
            }
            else if (newForwardedEndpoints.size() < lastN)
            {
                for (String endpointId : conferenceSpeechActivityEndpoints)
                {
                    if (newForwardedEndpoints.size() < lastN)
                    {
                        if (!endpointId.equals(ourEndpointId)
                                && !newForwardedEndpoints.contains(endpointId))
                        {
                            newForwardedEndpoints.add(endpointId);
                        }
                    }
                    else
                    {
                        break;
                    }
                }
            }
        }

        List<String> enteringEndpoints;
        if (forwardedEndpoints.equals(newForwardedEndpoints))
        {
            // We want forwardedEndpoints != INITIAL_EMPTY_LIST
            forwardedEndpoints = newForwardedEndpoints;

            enteringEndpoints = null;
        }
        else
        {
            enteringEndpoints = new ArrayList<>(newForwardedEndpoints);
            enteringEndpoints.removeAll(forwardedEndpoints);

            if (logger.isDebugEnabled())
            {
                logger.debug(
                        "Forwarded endpoints changed: "
                        + forwardedEndpoints.toString() + " -> "
                        + newForwardedEndpoints.toString()
                        + ". Entering: " + enteringEndpoints.toString());
            }

            forwardedEndpoints
                    = Collections.unmodifiableList(newForwardedEndpoints);

            // TODO: we may want to do this asynchronously.
            channel.sendLastNEndpointsChangeEventOnDataChannel(
                    forwardedEndpoints, enteringEndpoints);
        }

        // If lastN is disabled, the endpoints entering forwardedEndpoints were
        // never filtered, so they don't need to be asked for keyframes.
        if (lastN < 0)
        {
            enteringEndpoints = null;
        }

        if (enteringEndpoints != null && newConferenceEndpoints != null)
        {
            // Endpoints just entering the conference need not be asked for
            // keyframes.
            enteringEndpoints.removeAll(newConferenceEndpoints);
        }

        return enteringEndpoints;
    }

    /**
     * Sends a keyframe request to the endpoints specified in
     * {@code endpointIds}
     * @param endpointIds the list of IDs of endpoints to which to send a
     * request for a keyframe.
     */
    private void askForKeyframes(List<String> endpointIds)
    {
        // TODO: Execute asynchronously.
        if (endpointIds != null && !endpointIds.isEmpty())
        {
            channel.getContent().askForKeyframesById(endpointIds);
        }
    }

    /**
     * @return the ID of the endpoint of our channel.
     */
    private String getEndpointId()
    {
        if (endpointId == null)
        {
            Endpoint endpoint = channel.getEndpoint();
            if (endpoint != null)
            {
                endpointId = endpoint.getID();
            }
        }
        return endpointId;
    }

    /**
     * Initializes the local list of endpoints
     * ({@link #speechActivityEndpointsChanged(List)}) with the current
     * endpoints from the conference.
     */
    public synchronized void initializeConferenceEndpoints()
    {
        speechActivityEndpointsChanged(
                channel.getConferenceSpeechActivity().getEndpoints());

        if (logger.isDebugEnabled())
        {
            logger.debug("Initialized the list of endpoints: "
                             + conferenceSpeechActivityEndpoints.toString());
        }
    }

    /**
     * Extracts a list of endpoint IDs from a list of {@link Endpoint}s.
     * @param endpoints the list of {@link Endpoint}s.
     * @return the list of IDs of endpoints in {@code endpoints}.
     */
    private List<String> getIDs(List<Endpoint> endpoints)
    {
        if (endpoints != null && !endpoints.isEmpty())
        {
            List<String> endpointIds = new LinkedList<>();
            for (Endpoint endpoint : endpoints)
            {
                endpointIds.add(endpoint.getID());
            }
            return endpointIds;
        }

        return null;
    }
}
