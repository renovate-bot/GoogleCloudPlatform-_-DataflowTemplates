/*
 * Copyright (C) 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.templates.bigtablechangestreamstopubsub;

import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.teleport.v2.templates.bigtablechangestreamstopubsub.model.InvalidModException;
import com.google.cloud.teleport.v2.templates.bigtablechangestreamstopubsub.model.MessageFormat;
import com.google.cloud.teleport.v2.templates.bigtablechangestreamstopubsub.model.Mod;
import com.google.cloud.teleport.v2.templates.bigtablechangestreamstopubsub.schemautils.PubSubUtils;
import com.google.cloud.teleport.v2.values.FailsafeElement;
import com.google.common.base.Throwables;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class {@link FailsafePublisher} provides methods that generates pubsub message and publishes it
 * to PubSub topic. The data failed to publish is returned as the output.
 */
public final class FailsafePublisher {

  /**
   * Primary class for taking a {@link FailsafeElement} JSON input and converting to a {@link
   * PubsubMessage}.
   */
  public static class PublishModJsonToTopic
      extends PTransform<PCollection<FailsafeElement<String, String>>, PCollectionTuple> {

    private final PubSubUtils pubSubUtils;
    private final TupleTag<FailsafeElement<String, String>> invalidModsTag;
    private final TupleTag<FailsafeElement<String, String>> validModsTag;

    private static final Logger LOG = LoggerFactory.getLogger(PublishModJsonToTopic.class);

    public PublishModJsonToTopic(
        PubSubUtils pubSubUtils,
        TupleTag<FailsafeElement<String, String>> validModsTag,
        TupleTag<FailsafeElement<String, String>> invalidModsTag) {
      this.pubSubUtils = pubSubUtils;
      this.invalidModsTag = invalidModsTag;
      this.validModsTag = validModsTag;
    }

    public PCollectionTuple expand(PCollection<FailsafeElement<String, String>> input) {

      return input.apply(
          ParDo.of(new PublishModJsonToTopicFn(pubSubUtils, validModsTag, invalidModsTag))
              .withOutputTags(validModsTag, TupleTagList.of(invalidModsTag)));
    }

    /**
     * The {@link PublishModJsonToTopicFn} converts a JSON string wrapped in {@link FailsafeElement}
     * to a {@link PubsubMessage} and publishes it to the topic. Invalid mods are sent to
     * invalidModsTag output stream, so they are not retried.
     */
    public static class PublishModJsonToTopicFn
        extends DoFn<FailsafeElement<String, String>, FailsafeElement<String, String>> {
      private final PubSubUtils pubSubUtils;
      private final ThrottledLogger throttled;
      private final TupleTag<FailsafeElement<String, String>> invalidModsTag;
      private final TupleTag<FailsafeElement<String, String>> validModsTag;

      private transient Publisher publisher;

      public PublishModJsonToTopicFn(
          PubSubUtils pubSubUtils,
          TupleTag<FailsafeElement<String, String>> validModsTag,
          TupleTag<FailsafeElement<String, String>> invalidModsTag) {
        this.pubSubUtils = pubSubUtils;
        this.invalidModsTag = invalidModsTag;
        this.validModsTag = validModsTag;
        this.throttled = new ThrottledLogger();
      }

      @Setup
      public void setUp() {
        try {
          final TopicName projectTopicName =
              TopicName.of(
                  pubSubUtils.getDestination().getPubSubProject(),
                  pubSubUtils.getDestination().getPubSubTopicName());
          publisher = Publisher.newBuilder(projectTopicName).build();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }

      @Teardown
      public void tearDown() {
        try {
          if (publisher != null) {
            publisher.shutdown();
            publisher.awaitTermination(5, TimeUnit.MINUTES);
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }

      @ProcessElement
      public void processElement(ProcessContext context) {
        FailsafeElement<String, String> failsafeModJsonString = context.element();

        try {
          PubsubMessage pubSubMessage = newPubsubMessage(failsafeModJsonString.getPayload());
          throttled.success(LOG, publisher.publish(pubSubMessage).get());
        } catch (InvalidModException e) {
          throttled.failure(LOG, e);
          context.output(
              invalidModsTag,
              FailsafeElement.of(failsafeModJsonString)
                  .setErrorMessage(e.getMessage())
                  .setStacktrace(Throwables.getStackTraceAsString(e)));
        } catch (Exception e) {
          throttled.failure(LOG, e);
          context.output(
              validModsTag,
              FailsafeElement.of(failsafeModJsonString)
                  .setErrorMessage(e.getMessage())
                  .setStacktrace(Throwables.getStackTraceAsString(e)));
        }
      }

      /* Schema Details: */
      private PubsubMessage newPubsubMessage(String modJsonString) throws Exception {
        String changeJsonString = Mod.fromJson(modJsonString).getChangeJson();
        MessageFormat messageFormat = pubSubUtils.getDestination().getMessageFormat();

        switch (messageFormat) {
          case AVRO:
            return pubSubUtils.mapChangeJsonStringToPubSubMessageAsAvro(changeJsonString);
          case PROTOCOL_BUFFERS:
            return pubSubUtils.mapChangeJsonStringToPubSubMessageAsProto(changeJsonString);
          case JSON:
            return pubSubUtils.mapChangeJsonStringToPubSubMessageAsJson(changeJsonString);
          default:
            final String errorMessage =
                "Invalid message format:"
                    + messageFormat
                    + ". Supported output formats: "
                    + Arrays.toString(MessageFormat.values());
            throw new IllegalArgumentException(errorMessage);
        }
      }
    }
  }

  private static class ThrottledLogger implements Serializable {
    private static final long ONE_MINUTE_MILLIS = 60000;
    private static long lastSuccessLogged = 0L;
    private static long lastFailureLogged = 0L;

    private static long countPublished = 0L;
    private static long countFailures = 0L;

    public void success(Logger logger, String messageId) {
      long currentTimeMillis = System.currentTimeMillis();
      synchronized (ThrottledLogger.class) {
        countPublished++;
        if (lastSuccessLogged <= currentTimeMillis - ONE_MINUTE_MILLIS) {
          lastSuccessLogged = currentTimeMillis;
          logger.info(
              "Succeeded publishing. Published so far: {}, last messageId={}",
              countPublished,
              messageId);
        }
      }
    }

    public void failure(Logger logger, Exception exception) {
      long currentTimeMillis = System.currentTimeMillis();
      synchronized (ThrottledLogger.class) {
        countFailures++;
        if (lastFailureLogged <= currentTimeMillis - ONE_MINUTE_MILLIS) {

          lastFailureLogged = currentTimeMillis;
          logger.warn("Failed to publish message. Failures so far: {}", countFailures, exception);
        }
      }
    }
  }
}
