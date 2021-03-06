/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.distributed.cache.client.AtomicDistributedMapCacheClient;
import org.apache.nifi.expression.AttributeExpression.ResultType;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.standard.WaitNotifyProtocol.Signal;

@EventDriven
@SupportsBatching
@Tags({"map", "cache", "wait", "hold", "distributed", "signal", "release"})
@InputRequirement(Requirement.INPUT_REQUIRED)
@CapabilityDescription("Routes incoming FlowFiles to the 'wait' relationship until a matching release signal "
        + "is stored in the distributed cache from a corresponding Notify processor. "
        + "When a matching release signal is identified, a waiting FlowFile is routed to the 'success' relationship, "
        + "with attributes copied from the FlowFile that produced the release signal from the Notify processor.  "
        + "The release signal entry is then removed from the cache. Waiting FlowFiles will be routed to 'expired' if they exceed the Expiration Duration. "

        + "If you need to wait for more than one signal, specify the desired number of signals via the 'Target Signal Count' property. "
        + "This is particularly useful with processors that split a source flow file into multiple fragments, such as SplitText. "
        + "In order to wait for all fragments to be processed, connect the 'original' relationship to a Wait processor, and the 'splits' relationship to "
        + "a corresponding Notify processor. Configure the Notify and Wait processors to use the '${fragment.identifier}' as the value "
        + "of 'Release Signal Identifier', and specify '${fragment.count}' as the value of 'Target Signal Count' in the Wait processor."
)
@WritesAttributes({
        @WritesAttribute(attribute = "wait.start.timestamp", description = "All FlowFiles will have an attribute 'wait.start.timestamp', which sets the "
        + "initial epoch timestamp when the file first entered this processor.  This is used to determine the expiration time of the FlowFile."),
        @WritesAttribute(attribute = "wait.counter.<counterName>", description = "If a signal exists when the processor runs, "
        + "each count value in the signal is copied.")
})
@SeeAlso(classNames = {"org.apache.nifi.distributed.cache.client.DistributedMapCacheClientService", "org.apache.nifi.distributed.cache.server.map.DistributedMapCacheServer",
        "org.apache.nifi.processors.standard.Notify"})
public class Wait extends AbstractProcessor {

    public static final String WAIT_START_TIMESTAMP = "wait.start.timestamp";

    // Identifies the distributed map cache client
    public static final PropertyDescriptor DISTRIBUTED_CACHE_SERVICE = new PropertyDescriptor.Builder()
            .name("distributed-cache-service")
            .displayName("Distributed Cache Service")
            .description("The Controller Service that is used to check for release signals from a corresponding Notify processor")
            .required(true)
            .identifiesControllerService(AtomicDistributedMapCacheClient.class)
            .build();

    // Selects the FlowFile attribute or expression, whose value is used as cache key
    public static final PropertyDescriptor RELEASE_SIGNAL_IDENTIFIER = new PropertyDescriptor.Builder()
            .name("release-signal-id")
            .displayName("Release Signal Identifier")
            .description("A value, or the results of an Attribute Expression Language statement, which will " +
                "be evaluated against a FlowFile in order to determine the release signal cache key")
            .required(true)
            .addValidator(StandardValidators.createAttributeExpressionLanguageValidator(ResultType.STRING, true))
            .expressionLanguageSupported(true)
            .build();

    public static final PropertyDescriptor TARGET_SIGNAL_COUNT = new PropertyDescriptor.Builder()
            .name("target-signal-count")
            .displayName("Target Signal Count")
            .description("A value, or the results of an Attribute Expression Language statement, which will " +
                    "be evaluated against a FlowFile in order to determine the target signal count. " +
                    "This processor checks whether the signal count has reached this number. " +
                    "If Signal Counter Name is specified, this processor checks a particular counter, " +
                    "otherwise checks against total count in a signal.")
            .required(true)
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(true)
            .defaultValue("1")
            .build();

    public static final PropertyDescriptor SIGNAL_COUNTER_NAME = new PropertyDescriptor.Builder()
            .name("signal-counter-name")
            .displayName("Signal Counter Name")
            .description("A value, or the results of an Attribute Expression Language statement, which will " +
                    "be evaluated against a FlowFile in order to determine the signal counter name. " +
                    "If not specified, this processor checks the total count in a signal.")
            .required(false)
            .addValidator(StandardValidators.createAttributeExpressionLanguageValidator(ResultType.STRING, true))
            .expressionLanguageSupported(true)
            .build();

    // Selects the FlowFile attribute or expression, whose value is used as cache key
    public static final PropertyDescriptor EXPIRATION_DURATION = new PropertyDescriptor.Builder()
            .name("expiration-duration")
            .displayName("Expiration Duration")
            .description("Indicates the duration after which waiting flow files will be routed to the 'expired' relationship")
            .required(true)
            .defaultValue("10 min")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(false)
            .build();

    public static final AllowableValue ATTRIBUTE_COPY_REPLACE = new AllowableValue("replace", "Replace if present",
            "When cached attributes are copied onto released FlowFiles, they replace any matching attributes.");

    public static final AllowableValue ATTRIBUTE_COPY_KEEP_ORIGINAL = new AllowableValue("keeporiginal", "Keep original",
            "Attributes on released FlowFiles are not overwritten by copied cached attributes.");

    public static final PropertyDescriptor ATTRIBUTE_COPY_MODE = new PropertyDescriptor.Builder()
            .name("attribute-copy-mode")
            .displayName("Attribute Copy Mode")
            .description("Specifies how to handle attributes copied from flow files entering the Notify processor")
            .defaultValue(ATTRIBUTE_COPY_KEEP_ORIGINAL.getValue())
            .required(true)
            .allowableValues(ATTRIBUTE_COPY_REPLACE, ATTRIBUTE_COPY_KEEP_ORIGINAL)
            .expressionLanguageSupported(false)
            .build();

    public static final AllowableValue WAIT_MODE_TRANSFER_TO_WAIT = new AllowableValue("wait", "Transfer to wait relationship",
            "Transfer a FlowFile to the 'wait' relationship when whose release signal has not been notified yet." +
                    " This mode allows other incoming FlowFiles to be enqueued by moving FlowFiles into the wait relationship.");

    public static final AllowableValue WAIT_MODE_KEEP_IN_UPSTREAM = new AllowableValue("keep", "Keep in the upstream connection",
            "Transfer a FlowFile to the upstream connection where it comes from when whose release signal has not been notified yet." +
                    " This mode helps keeping upstream connection being full so that the upstream source processor" +
                    " will not be scheduled while back-pressure is active and limit incoming FlowFiles. ");

    public static final PropertyDescriptor WAIT_MODE = new PropertyDescriptor.Builder()
            .name("wait-mode")
            .displayName("Wait Mode")
            .description("Specifies how to handle a FlowFile waiting for a notify signal")
            .defaultValue(WAIT_MODE_TRANSFER_TO_WAIT.getValue())
            .required(true)
            .allowableValues(WAIT_MODE_TRANSFER_TO_WAIT, WAIT_MODE_KEEP_IN_UPSTREAM)
            .expressionLanguageSupported(false)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("A FlowFile with a matching release signal in the cache will be routed to this relationship")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("When the cache cannot be reached, or if the Release Signal Identifier evaluates to null or empty, FlowFiles will be routed to this relationship")
            .build();

    public static final Relationship REL_WAIT = new Relationship.Builder()
            .name("wait")
            .description("A FlowFile with no matching release signal in the cache will be routed to this relationship")
            .build();

    public static final Relationship REL_EXPIRED = new Relationship.Builder()
            .name("expired")
            .description("A FlowFile that has exceeded the configured Expiration Duration will be routed to this relationship")
            .build();

    private final Set<Relationship> relationships;

    public Wait() {
        final Set<Relationship> rels = new HashSet<>();
        rels.add(REL_SUCCESS);
        rels.add(REL_WAIT);
        rels.add(REL_EXPIRED);
        rels.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(rels);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(RELEASE_SIGNAL_IDENTIFIER);
        descriptors.add(TARGET_SIGNAL_COUNT);
        descriptors.add(SIGNAL_COUNTER_NAME);
        descriptors.add(EXPIRATION_DURATION);
        descriptors.add(DISTRIBUTED_CACHE_SERVICE);
        descriptors.add(ATTRIBUTE_COPY_MODE);
        descriptors.add(WAIT_MODE);
        return descriptors;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {

        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final ComponentLog logger = getLogger();

        // Signal id is computed from attribute 'RELEASE_SIGNAL_IDENTIFIER' with expression language support
        final String signalId = context.getProperty(RELEASE_SIGNAL_IDENTIFIER).evaluateAttributeExpressions(flowFile).getValue();

        // if the computed value is null, or empty, we transfer the flow file to failure relationship
        if (StringUtils.isBlank(signalId)) {
            logger.error("FlowFile {} has no attribute for given Release Signal Identifier", new Object[] {flowFile});
            flowFile = session.penalize(flowFile);
            session.transfer(flowFile, REL_FAILURE);
            return;
        }

        // the cache client used to interact with the distributed cache
        final AtomicDistributedMapCacheClient cache = context.getProperty(DISTRIBUTED_CACHE_SERVICE).asControllerService(AtomicDistributedMapCacheClient.class);
        final WaitNotifyProtocol protocol = new WaitNotifyProtocol(cache);

        String attributeCopyMode = context.getProperty(ATTRIBUTE_COPY_MODE).getValue();
        final boolean replaceOriginalAttributes = ATTRIBUTE_COPY_REPLACE.getValue().equals(attributeCopyMode);

        Signal signal = null;
        try {
            // get notifying signal
            signal = protocol.getSignal(signalId);

            // check for expiration
            String waitStartTimestamp = flowFile.getAttribute(WAIT_START_TIMESTAMP);
            if (waitStartTimestamp == null) {
                waitStartTimestamp = String.valueOf(System.currentTimeMillis());
                flowFile = session.putAttribute(flowFile, WAIT_START_TIMESTAMP, waitStartTimestamp);
            }

            long lWaitStartTimestamp = 0L;
            try {
                lWaitStartTimestamp = Long.parseLong(waitStartTimestamp);
            } catch (NumberFormatException nfe) {
                logger.error("{} has an invalid value '{}' on FlowFile {}", new Object[] {WAIT_START_TIMESTAMP, waitStartTimestamp, flowFile});
                flowFile = session.penalize(flowFile);

                flowFile = copySignalAttributes(session, flowFile, signal, replaceOriginalAttributes);
                session.transfer(flowFile, REL_FAILURE);
                return;
            }
            long expirationDuration = context.getProperty(EXPIRATION_DURATION)
                    .asTimePeriod(TimeUnit.MILLISECONDS);
            long now = System.currentTimeMillis();
            if (now > (lWaitStartTimestamp + expirationDuration)) {
                logger.warn("FlowFile {} expired after {}ms", new Object[] {flowFile, (now - lWaitStartTimestamp)});
                flowFile = copySignalAttributes(session, flowFile, signal, replaceOriginalAttributes);
                session.transfer(flowFile, REL_EXPIRED);
                return;
            }

            if (signal == null) {
                // If there's no signal yet, then we don't have to evaluate target counts. Return immediately.
                if (logger.isDebugEnabled()) {
                    logger.debug("No release signal yet for {} on FlowFile {}", new Object[] {signalId, flowFile});
                }


                final String waitMode = context.getProperty(WAIT_MODE).getValue();
                if (WAIT_MODE_TRANSFER_TO_WAIT.getValue().equals(waitMode)) {
                    session.transfer(flowFile, REL_WAIT);
                } else if (WAIT_MODE_KEEP_IN_UPSTREAM.getValue().equals(waitMode)) {
                    // Transfer to self.
                    session.transfer(flowFile);
                } else {
                    throw new ProcessException("Unsupported wait mode " + waitMode + " was specified.");
                }
                return;
            }

            final String targetCounterName = context.getProperty(SIGNAL_COUNTER_NAME).evaluateAttributeExpressions(flowFile).getValue();
            final Long targetCount = Long.valueOf(context.getProperty(TARGET_SIGNAL_COUNT).evaluateAttributeExpressions(flowFile).getValue());
            final boolean reachedToTargetCount = StringUtils.isBlank(targetCounterName)
                    ? signal.isTotalCountReached(targetCount)
                    : signal.isCountReached(targetCounterName, targetCount);

            if (!reachedToTargetCount) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Release signal count {} hasn't reached {} for {} on FlowFile {}",
                            new Object[] {targetCounterName, targetCount, signalId, flowFile});
                }
                flowFile = copySignalAttributes(session, flowFile, signal, replaceOriginalAttributes);
                session.transfer(flowFile, REL_WAIT);
                return;
            }


            flowFile = copySignalAttributes(session, flowFile, signal, replaceOriginalAttributes);
            session.transfer(flowFile, REL_SUCCESS);

            protocol.complete(signalId);

        } catch (final NumberFormatException e) {
            flowFile = copySignalAttributes(session, flowFile, signal, replaceOriginalAttributes);
            flowFile = session.penalize(flowFile);
            session.transfer(flowFile, REL_FAILURE);
            logger.error("Failed to parse targetCount when processing {} due to {}", new Object[] {flowFile, e});

        } catch (final IOException e) {
            flowFile = copySignalAttributes(session, flowFile, signal, replaceOriginalAttributes);
            flowFile = session.penalize(flowFile);
            session.transfer(flowFile, REL_FAILURE);
            logger.error("Unable to communicate with cache when processing {} due to {}", new Object[] {flowFile, e});
        }
    }

    private FlowFile copySignalAttributes(final ProcessSession session, final FlowFile flowFile, final Signal signal, final boolean replaceOriginal) {
        if (signal == null) {
            return flowFile;
        }

        // copy over attributes from release signal flow file, if provided
        final Map<String, String> attributesToCopy;
        if (replaceOriginal) {
            attributesToCopy = new HashMap<>(signal.getAttributes());
            attributesToCopy.remove("uuid");
        } else {
            // if the current flow file does *not* have the cached attribute, copy it
            attributesToCopy = signal.getAttributes().entrySet().stream()
                    .filter(e -> flowFile.getAttribute(e.getKey()) == null)
                    .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
        }

        // Copy counter attributes
        final Map<String, Long> counts = signal.getCounts();
        final long totalCount = counts.entrySet().stream().mapToLong(e -> {
            final Long count = e.getValue();
            attributesToCopy.put("wait.counter." + e.getKey(), String.valueOf(count));
            return count;
        }).sum();
        attributesToCopy.put("wait.counter.total", String.valueOf(totalCount));

        return session.putAllAttributes(flowFile, attributesToCopy);
    }

}
